//! The listening history page: newest-first reading, a summary of what the
//! server holds, and deleting a time range (docs/analytics.md, Privacy
//! controls). Sync itself stays in events.rs.

use axum::Json;
use axum::extract::{Query, State};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};
use serde_json::json;
use uuid::Uuid;

use super::events::{Event, MIN_AT_MS, now_ms};
use crate::AppState;
use crate::auth::AuthUser;
use crate::error::{ApiError, ApiResult};

/// Event types that are listening history: what "delete history" removes.
/// Library state (tracks, likes, crates) is not history and is never deleted
/// here, or deleting a month of plays would also undo that month's crate edits.
pub const HISTORY_TYPES: &[&str] = &["play", "transition", "dj_session", "app_session", "search"];
/// The tombstone the server writes into the log when history is deleted.
pub const HISTORY_DELETED: &str = "history_deleted";

const DEFAULT_PAGE: i64 = 50;
const MAX_PAGE: i64 = 200;

#[derive(Deserialize)]
pub struct ReadQuery {
    before: Option<String>,
    limit: Option<i64>,
    /// Comma-separated subset of the history types. Default `play`.
    types: Option<String>,
}

#[derive(Serialize)]
pub struct HistoryPage {
    events: Vec<Event>,
    /// Pass as `before` for the next (older) page; absent on the last page.
    #[serde(skip_serializing_if = "Option::is_none")]
    next_cursor: Option<String>,
}

/// History newest first, by when it happened. Devices hold the whole log once
/// they have pulled it; this lets a newly signed-in device show history
/// straight away while that pull runs.
pub async fn read(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Query(q): Query<ReadQuery>,
) -> ApiResult<Json<HistoryPage>> {
    let types = parse_types(q.types.as_deref())?;
    let (before_ms, before_id) = match q.before.as_deref() {
        None | Some("") => (i64::MAX, Uuid::max()),
        Some(c) => decode_cursor(c).ok_or_else(|| ApiError::BadRequest("invalid cursor".into()))?,
    };
    let limit = q.limit.unwrap_or(DEFAULT_PAGE).clamp(1, MAX_PAGE);
    let mut rows = sqlx::query_as::<_, Event>(
        r#"
        SELECT id, type, v, at_ms, tz_min, device_id, session_id, track_id, track_key, payload
        FROM events
        WHERE user_id = $1 AND type = ANY($2) AND (at_ms, id) < ($3, $4)
        ORDER BY at_ms DESC, id DESC
        LIMIT $5
        "#,
    )
    .bind(user_id)
    .bind(&types)
    .bind(before_ms)
    .bind(before_id)
    .bind(limit + 1)
    .fetch_all(&state.db)
    .await?;
    let has_more = rows.len() as i64 > limit;
    rows.truncate(limit as usize);
    let next_cursor = has_more
        .then(|| rows.last().map(|e| encode_cursor(e.at_ms, e.id)))
        .flatten();
    Ok(Json(HistoryPage {
        events: rows,
        next_cursor,
    }))
}

fn parse_types(raw: Option<&str>) -> ApiResult<Vec<String>> {
    let Some(raw) = raw.filter(|r| !r.is_empty()) else {
        return Ok(vec!["play".into()]);
    };
    raw.split(',')
        .map(|t| {
            let t = t.trim();
            HISTORY_TYPES
                .contains(&t)
                .then(|| t.to_string())
                .ok_or_else(|| {
                    ApiError::BadRequest(format!(
                        "types must be from: {}",
                        HISTORY_TYPES.join(", ")
                    ))
                })
        })
        .collect()
}

fn encode_cursor(at_ms: i64, id: Uuid) -> String {
    URL_SAFE_NO_PAD.encode(format!("h1:{at_ms}:{id}"))
}

fn decode_cursor(cursor: &str) -> Option<(i64, Uuid)> {
    let raw = URL_SAFE_NO_PAD.decode(cursor).ok()?;
    let (at, id) = std::str::from_utf8(&raw)
        .ok()?
        .strip_prefix("h1:")?
        .split_once(':')?;
    Some((at.parse().ok()?, id.parse().ok()?))
}

#[derive(Serialize)]
pub struct Summary {
    /// Every event the server holds for this account, of any type.
    events: i64,
    plays: i64,
    listened_ms: i64,
    first_at_ms: Option<i64>,
    last_at_ms: Option<i64>,
    /// When the server last received an upload from any device.
    last_upload_at_ms: Option<i64>,
    /// Approximate size of this account's synced history.
    bytes: i64,
    devices: Vec<DeviceSummary>,
}

#[derive(Serialize, sqlx::FromRow)]
pub struct DeviceSummary {
    device_id: String,
    events: i64,
    last_upload_at_ms: i64,
}

/// What the server holds, for the history and server details screens.
pub async fn summary(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
) -> ApiResult<Json<Summary>> {
    let (events, plays, listened_ms, first_at_ms, last_at_ms, last_upload_at_ms, bytes): (
        i64,
        i64,
        i64,
        Option<i64>,
        Option<i64>,
        Option<i64>,
        i64,
    ) = sqlx::query_as(
        r#"
        SELECT count(*),
               count(*) FILTER (WHERE type = 'play'),
               COALESCE(sum(CASE WHEN type = 'play' AND jsonb_typeof(payload->'listened_ms') = 'number'
                                 THEN (payload->>'listened_ms')::numeric END), 0)::bigint,
               min(at_ms) FILTER (WHERE type <> $2),
               max(at_ms) FILTER (WHERE type <> $2),
               (extract(epoch FROM max(received_at)) * 1000)::bigint,
               COALESCE(sum(pg_column_size(events.*)), 0)::bigint
        FROM events WHERE user_id = $1
        "#,
    )
    .bind(user_id)
    .bind(HISTORY_DELETED)
    .fetch_one(&state.db)
    .await?;
    let devices = sqlx::query_as::<_, DeviceSummary>(
        r#"
        SELECT device_id, count(*) AS events,
               (extract(epoch FROM max(received_at)) * 1000)::bigint AS last_upload_at_ms
        FROM events WHERE user_id = $1 AND type <> $2
        GROUP BY device_id ORDER BY last_upload_at_ms DESC
        "#,
    )
    .bind(user_id)
    .bind(HISTORY_DELETED)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(Summary {
        events,
        plays,
        listened_ms,
        first_at_ms,
        last_at_ms,
        last_upload_at_ms,
        bytes,
        devices,
    }))
}

/// Both bounds optional: none deletes all listening history.
#[derive(Deserialize)]
pub struct DeleteRange {
    #[serde(default)]
    from_ms: Option<i64>,
    #[serde(default)]
    to_ms: Option<i64>,
}

#[derive(Serialize)]
pub struct Deleted {
    deleted: u64,
    from_ms: i64,
    to_ms: i64,
    /// Id of the `history_deleted` event other devices will pull.
    tombstone_id: Uuid,
}

/// Deletes listening history with `from_ms <= at_ms < to_ms`. The end is
/// clamped to now, so new listening is never swallowed by an old deletion.
pub async fn delete(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Json(req): Json<DeleteRange>,
) -> ApiResult<Json<Deleted>> {
    let now = now_ms();
    let from_ms = req.from_ms.unwrap_or(MIN_AT_MS).max(0);
    let to_ms = req.to_ms.unwrap_or(now).min(now);
    if from_ms >= to_ms {
        return Err(ApiError::BadRequest(
            "from_ms must be before to_ms and in the past".into(),
        ));
    }
    let tombstone_id = Uuid::now_v7();
    let mut tx = state.db.begin().await?;
    // The same per-user lock as uploads: no upload can slip events into the
    // range between the delete and the tombstone.
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    let deleted = sqlx::query(
        "DELETE FROM events WHERE user_id = $1 AND type = ANY($2) AND at_ms >= $3 AND at_ms < $4",
    )
    .bind(user_id)
    .bind(HISTORY_TYPES)
    .bind(from_ms)
    .bind(to_ms)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    sqlx::query(
        "INSERT INTO history_deletions (id, user_id, from_ms, to_ms) VALUES ($1, $2, $3, $4)",
    )
    .bind(tombstone_id)
    .bind(user_id)
    .bind(from_ms)
    .bind(to_ms)
    .execute(&mut *tx)
    .await
    .map_err(account_gone)?;
    sqlx::query(
        r#"
        INSERT INTO events (user_id, id, type, v, at_ms, tz_min, device_id, payload)
        VALUES ($1, $2, $3, 1, $4, 0, 'server', $5)
        "#,
    )
    .bind(user_id)
    .bind(tombstone_id)
    .bind(HISTORY_DELETED)
    .bind(now)
    .bind(json!({ "from_ms": from_ms, "to_ms": to_ms, "types": HISTORY_TYPES }))
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    tracing::info!(%user_id, deleted, "listening history deleted");
    Ok(Json(Deleted {
        deleted,
        from_ms,
        to_ms,
        tombstone_id,
    }))
}

fn account_gone(e: sqlx::Error) -> ApiError {
    if super::auth::is_constraint(&e, "history_deletions_user_id_fkey") {
        ApiError::Unauthorized("account no longer exists")
    } else {
        e.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cursor_round_trip() {
        let id = Uuid::now_v7();
        assert_eq!(decode_cursor(&encode_cursor(42, id)), Some((42, id)));
        assert_eq!(decode_cursor("garbage"), None);
    }

    #[test]
    fn types_default_to_plays_and_stay_within_history() {
        assert_eq!(parse_types(None).unwrap(), vec!["play"]);
        assert_eq!(
            parse_types(Some("play, transition")).unwrap(),
            vec!["play", "transition"]
        );
        assert!(parse_types(Some("crate_changed")).is_err());
    }
}
