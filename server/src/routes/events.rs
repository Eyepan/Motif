//! The listening history log: idempotent batch upload and cursor pull.
//! Event shape and semantics: docs/analytics.md.

use axum::Json;
use axum::extract::{Query, State};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

use super::history::{HISTORY_DELETED, HISTORY_TYPES};
use crate::AppState;
use crate::auth::AuthUser;
use crate::error::{ApiError, ApiResult};

pub const MAX_BATCH: usize = 1000;
pub const MAX_PAYLOAD_BYTES: usize = 16 * 1024;
const DEFAULT_PULL: i64 = 500;
const MAX_PULL: i64 = 1000;
/// 2020-01-01, earlier than any Motif event can be.
pub(super) const MIN_AT_MS: i64 = 1_577_836_800_000;
/// Device clocks drift; anything further ahead than this is a broken clock.
const MAX_FUTURE_MS: i64 = 7 * 24 * 60 * 60 * 1000;

/// One event as the device stores it. Unknown fields (such as the local
/// `synced` flag) are ignored.
#[derive(Debug, Deserialize, Serialize, sqlx::FromRow)]
pub struct Event {
    pub id: Uuid,
    #[serde(rename = "type")]
    #[sqlx(rename = "type")]
    pub kind: String,
    pub v: i32,
    pub at_ms: i64,
    pub tz_min: i32,
    pub device_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub track_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub track_key: Option<String>,
    pub payload: Value,
}

#[derive(Deserialize)]
pub struct UploadRequest {
    events: Vec<Value>,
}

#[derive(Serialize)]
pub struct Rejected {
    index: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<Value>,
    reason: String,
}

/// `inserted` + `duplicates` are acknowledged: the device marks them synced.
/// `rejected` events will never be accepted as they are; the device should
/// keep them locally and stop retrying them.
#[derive(Serialize)]
pub struct UploadResponse {
    inserted: u64,
    duplicates: u64,
    rejected: Vec<Rejected>,
}

pub async fn upload(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Json(req): Json<UploadRequest>,
) -> ApiResult<Json<UploadResponse>> {
    if req.events.len() > MAX_BATCH {
        return Err(ApiError::BadRequest(format!(
            "at most {MAX_BATCH} events per batch"
        )));
    }
    let now_ms = now_ms();
    let mut valid = Vec::with_capacity(req.events.len());
    let mut rejected = Vec::new();
    for (index, raw) in req.events.into_iter().enumerate() {
        let id = raw.get("id").cloned();
        match serde_json::from_value::<Event>(raw)
            .map_err(|e| e.to_string())
            .and_then(|e| validate(e, now_ms))
        {
            Ok(event) => valid.push(event),
            Err(reason) => rejected.push(Rejected { index, id, reason }),
        }
    }
    // A batch can repeat an id (a client retrying inside one batch); keep the first.
    let mut seen = std::collections::HashSet::with_capacity(valid.len());
    valid.retain(|e| seen.insert(e.id));

    let inserted = insert(&state, user_id, &valid).await?;
    Ok(Json(UploadResponse {
        inserted,
        duplicates: valid.len() as u64 - inserted,
        rejected,
    }))
}

async fn insert(state: &AppState, user_id: Uuid, events: &[Event]) -> ApiResult<u64> {
    if events.is_empty() {
        return Ok(0);
    }
    let mut tx = state.db.begin().await?;
    // Serialize uploads per user so seq values commit in order (see migration).
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;

    let col = |f: fn(&Event) -> Option<String>| events.iter().map(f).collect::<Vec<_>>();
    let result = sqlx::query(
        r#"
        INSERT INTO events (user_id, id, type, v, at_ms, tz_min, device_id, session_id, track_id, track_key, payload)
        SELECT $1, e.* FROM UNNEST($2::uuid[], $3::text[], $4::int[], $5::bigint[], $6::int[], $7::text[],
                                   $8::text[], $9::text[], $10::text[], $11::jsonb[])
            AS e(id, type, v, at_ms, tz_min, device_id, session_id, track_id, track_key, payload)
        -- History the user deleted stays deleted when an offline device uploads
        -- its copy later; those count as duplicates so the device marks them synced.
        WHERE NOT (e.type = ANY($12) AND EXISTS (
            SELECT 1 FROM history_deletions d
            WHERE d.user_id = $1 AND e.at_ms >= d.from_ms AND e.at_ms < d.to_ms))
        ON CONFLICT (user_id, id) DO NOTHING
        "#,
    )
    .bind(user_id)
    .bind(events.iter().map(|e| e.id).collect::<Vec<_>>())
    .bind(events.iter().map(|e| e.kind.clone()).collect::<Vec<_>>())
    .bind(events.iter().map(|e| e.v).collect::<Vec<_>>())
    .bind(events.iter().map(|e| e.at_ms).collect::<Vec<_>>())
    .bind(events.iter().map(|e| e.tz_min).collect::<Vec<_>>())
    .bind(events.iter().map(|e| e.device_id.clone()).collect::<Vec<_>>())
    .bind(col(|e| e.session_id.clone()))
    .bind(col(|e| e.track_id.clone()))
    .bind(col(|e| e.track_key.clone()))
    .bind(events.iter().map(|e| e.payload.clone()).collect::<Vec<_>>())
    .bind(HISTORY_TYPES)
    .execute(&mut *tx)
    .await
    .map_err(|e| {
        // The account was deleted while this device still held an access token.
        if super::auth::is_constraint(&e, "events_user_id_fkey") {
            ApiError::Unauthorized("account no longer exists")
        } else {
            e.into()
        }
    })?;
    tx.commit().await?;
    Ok(result.rows_affected())
}

fn validate(e: Event, now_ms: i64) -> Result<Event, String> {
    if e.id.get_version_num() != 7 {
        return Err("id must be a UUIDv7".into());
    }
    let type_ok = !e.kind.is_empty()
        && e.kind.len() <= 64
        && e.kind.starts_with(|c: char| c.is_ascii_lowercase())
        && e.kind
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_');
    if !type_ok {
        return Err("type must be snake_case, at most 64 characters".into());
    }
    if e.kind == HISTORY_DELETED {
        return Err("history_deleted is written by the server; use DELETE /v1/history".into());
    }
    if e.v < 1 {
        return Err("v must be at least 1".into());
    }
    if e.at_ms < MIN_AT_MS || e.at_ms > now_ms + MAX_FUTURE_MS {
        return Err("at_ms is out of range".into());
    }
    if !(-18 * 60..=18 * 60).contains(&e.tz_min) {
        return Err("tz_min is out of range".into());
    }
    if e.device_id.is_empty() || e.device_id.len() > 64 {
        return Err("device_id must be 1-64 bytes".into());
    }
    for (name, value) in [
        ("session_id", &e.session_id),
        ("track_id", &e.track_id),
        ("track_key", &e.track_key),
    ] {
        if value.as_ref().is_some_and(|v| v.len() > 128) {
            return Err(format!("{name} is longer than 128 bytes"));
        }
    }
    if !e.payload.is_object() {
        return Err("payload must be a JSON object".into());
    }
    if e.payload.to_string().len() > MAX_PAYLOAD_BYTES {
        return Err(format!("payload is larger than {MAX_PAYLOAD_BYTES} bytes"));
    }
    Ok(e)
}

#[derive(Deserialize)]
pub struct PullQuery {
    after: Option<String>,
    limit: Option<i64>,
    /// Skip events this device uploaded itself.
    exclude_device: Option<String>,
}

#[derive(Serialize)]
pub struct PullResponse {
    events: Vec<Event>,
    /// Pass as `after` next time. Stored by the device between syncs.
    next_cursor: String,
    has_more: bool,
}

#[derive(sqlx::FromRow)]
struct Row {
    seq: i64,
    #[sqlx(flatten)]
    event: Event,
}

/// Events in server arrival order after `after`. Late uploads from a device
/// that was offline still show up, because the cursor follows arrival, not
/// event time.
pub async fn pull(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Query(q): Query<PullQuery>,
) -> ApiResult<Json<PullResponse>> {
    let after = match q.after.as_deref() {
        None | Some("") => 0,
        Some(c) => decode_cursor(c).ok_or_else(|| ApiError::BadRequest("invalid cursor".into()))?,
    };
    let limit = q.limit.unwrap_or(DEFAULT_PULL).clamp(1, MAX_PULL);

    let mut rows = sqlx::query_as::<_, Row>(
        r#"
        SELECT seq, id, type, v, at_ms, tz_min, device_id, session_id, track_id, track_key, payload
        FROM events
        WHERE user_id = $1 AND seq > $2 AND ($3::text IS NULL OR device_id <> $3)
        ORDER BY seq
        LIMIT $4
        "#,
    )
    .bind(user_id)
    .bind(after)
    .bind(&q.exclude_device)
    .bind(limit + 1)
    .fetch_all(&state.db)
    .await?;

    let has_more = rows.len() as i64 > limit;
    rows.truncate(limit as usize);
    let last = rows.last().map_or(after, |r| r.seq);
    Ok(Json(PullResponse {
        events: rows.into_iter().map(|r| r.event).collect(),
        next_cursor: encode_cursor(last),
        has_more,
    }))
}

fn encode_cursor(seq: i64) -> String {
    URL_SAFE_NO_PAD.encode(format!("v1:{seq}"))
}

fn decode_cursor(cursor: &str) -> Option<i64> {
    let raw = URL_SAFE_NO_PAD.decode(cursor).ok()?;
    let seq = std::str::from_utf8(&raw)
        .ok()?
        .strip_prefix("v1:")?
        .parse()
        .ok()?;
    (seq >= 0).then_some(seq)
}

pub(super) fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn event() -> Event {
        serde_json::from_value(json!({
            "id": Uuid::now_v7(),
            "type": "play",
            "v": 1,
            "at_ms": now_ms(),
            "tz_min": 330,
            "device_id": "dev-a",
            "track_key": "abc",
            "payload": { "listened_ms": 1000, "end_reason": "completed" },
            "synced": 0,
        }))
        .unwrap()
    }

    #[test]
    fn accepts_a_play_event() {
        assert!(validate(event(), now_ms()).is_ok());
    }

    #[test]
    fn rejects_bad_envelopes() {
        let now = now_ms();
        let mut e = event();
        e.id = Uuid::new_v4();
        assert!(validate(e, now).is_err());
        let mut e = event();
        e.kind = "Play".into();
        assert!(validate(e, now).is_err());
        let mut e = event();
        e.at_ms = now + MAX_FUTURE_MS + 1;
        assert!(validate(e, now).is_err());
        let mut e = event();
        e.payload = json!([1, 2]);
        assert!(validate(e, now).is_err());
        let mut e = event();
        e.payload = json!({ "blob": "x".repeat(MAX_PAYLOAD_BYTES) });
        assert!(validate(e, now).is_err());
    }

    #[test]
    fn cursor_round_trip() {
        assert_eq!(decode_cursor(&encode_cursor(42)), Some(42));
        assert_eq!(decode_cursor("garbage"), None);
        assert_eq!(decode_cursor(&URL_SAFE_NO_PAD.encode("v1:-3")), None);
    }
}
