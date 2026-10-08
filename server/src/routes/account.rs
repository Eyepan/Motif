//! Account management behind the Settings screens: account details, the list
//! of signed-in devices, and deleting the account.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use serde::{Deserialize, Deserializer, Serialize};
use uuid::Uuid;

use super::auth::{is_constraint, revoke_family};
use super::password::verify_blocking;
use crate::AppState;
use crate::auth::password::normalize_username;
use crate::auth::{AuthSession, AuthUser};
use crate::error::{ApiError, ApiResult};

pub const MAX_DISPLAY_NAME_CHARS: usize = 64;

#[derive(Serialize)]
pub struct Me {
    user_id: Uuid,
    username: Option<String>,
    display_name: Option<String>,
    created_at_ms: i64,
    identities: Vec<LinkedIdentity>,
}

#[derive(Serialize, sqlx::FromRow)]
pub struct LinkedIdentity {
    provider: String,
    email: Option<String>,
}

pub async fn me(State(state): State<AppState>, AuthUser(user_id): AuthUser) -> ApiResult<Json<Me>> {
    Ok(Json(load_me(&state, user_id).await?))
}

async fn load_me(state: &AppState, user_id: Uuid) -> ApiResult<Me> {
    let user: Option<(Option<String>, Option<String>, i64)> = sqlx::query_as(
        r#"
        SELECT p.username, u.display_name, (extract(epoch FROM u.created_at) * 1000)::bigint
        FROM users u LEFT JOIN password_credentials p ON p.user_id = u.id WHERE u.id = $1
        "#,
    )
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((username, display_name, created_at_ms)) = user else {
        return Err(ApiError::NotFound);
    };
    let identities = sqlx::query_as::<_, LinkedIdentity>(
        "SELECT provider, email FROM identities WHERE user_id = $1 ORDER BY created_at",
    )
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;
    Ok(Me {
        user_id,
        username,
        display_name,
        created_at_ms,
        identities,
    })
}

/// A missing field leaves the value alone; `null` clears it.
#[derive(Deserialize)]
pub struct UpdateMe {
    #[serde(default)]
    username: Option<String>,
    #[serde(default, deserialize_with = "present")]
    display_name: Option<Option<String>>,
}

fn present<'de, D, T>(d: D) -> Result<Option<Option<T>>, D::Error>
where
    D: Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(d).map(Some)
}

/// Trims a display name; empty clears it.
fn clean_display_name(raw: &str) -> Result<Option<String>, &'static str> {
    let name = raw.trim();
    if name.chars().any(char::is_control) {
        return Err("display name cannot contain control characters");
    }
    if name.chars().count() > MAX_DISPLAY_NAME_CHARS {
        return Err("display name must be at most 64 characters");
    }
    Ok((!name.is_empty()).then(|| name.to_string()))
}

pub async fn update_me(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Json(req): Json<UpdateMe>,
) -> ApiResult<Json<Me>> {
    let username = req
        .username
        .as_deref()
        .map(normalize_username)
        .transpose()
        .map_err(bad_request)?;
    let display_name = req
        .display_name
        .map(|n| n.as_deref().map(clean_display_name).transpose())
        .transpose()
        .map_err(bad_request)?
        .map(Option::flatten);

    let mut tx = state.db.begin().await?;
    if let Some(name) = display_name {
        let updated = sqlx::query("UPDATE users SET display_name = $2 WHERE id = $1")
            .bind(user_id)
            .bind(name)
            .execute(&mut *tx)
            .await?;
        if updated.rows_affected() == 0 {
            return Err(ApiError::NotFound);
        }
    }
    if let Some(username) = username {
        let updated = sqlx::query(
            "UPDATE password_credentials SET username = $2, updated_at = now() WHERE user_id = $1",
        )
        .bind(user_id)
        .bind(&username)
        .execute(&mut *tx)
        .await
        .map_err(|e| {
            if is_constraint(&e, "password_credentials_username_key") {
                ApiError::Conflict("username is taken")
            } else {
                e.into()
            }
        })?;
        if updated.rows_affected() == 0 {
            return Err(ApiError::BadRequest("this account has no username".into()));
        }
    }
    tx.commit().await?;
    Ok(Json(load_me(&state, user_id).await?))
}

#[derive(Deserialize)]
pub struct DeleteMe {
    #[serde(default)]
    password: Option<String>,
}

/// Deletes the account and, by cascade, its synced history and sign-ins.
/// Accounts with a password must confirm it. What is on the devices stays.
pub async fn delete_me(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Json(req): Json<DeleteMe>,
) -> ApiResult<StatusCode> {
    let stored: Option<String> =
        sqlx::query_scalar("SELECT password_hash FROM password_credentials WHERE user_id = $1")
            .bind(user_id)
            .fetch_optional(&state.db)
            .await?;
    if let Some(stored) = stored {
        let Some(password) = req.password else {
            return Err(ApiError::BadRequest("password is required".into()));
        };
        if !verify_blocking(password, stored).await? {
            return Err(ApiError::Forbidden("password is wrong"));
        }
    }
    let deleted = sqlx::query("DELETE FROM users WHERE id = $1")
        .bind(user_id)
        .execute(&state.db)
        .await?;
    if deleted.rows_affected() == 0 {
        return Err(ApiError::NotFound);
    }
    tracing::info!(%user_id, "account deleted");
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct UsernameQuery {
    username: String,
}

#[derive(Serialize)]
pub struct UsernameAvailability {
    username: String,
    available: bool,
}

/// For the sign-up form: is this username valid and free? Registering already
/// says "taken", so this reveals nothing new.
pub async fn username_available(
    State(state): State<AppState>,
    Query(q): Query<UsernameQuery>,
) -> ApiResult<Json<UsernameAvailability>> {
    let username = normalize_username(&q.username).map_err(bad_request)?;
    let taken: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM password_credentials WHERE username = $1)",
    )
    .bind(&username)
    .fetch_one(&state.db)
    .await?;
    Ok(Json(UsernameAvailability {
        username,
        available: !taken,
    }))
}

#[derive(Serialize, sqlx::FromRow)]
pub struct DeviceSession {
    id: Uuid,
    device_name: Option<String>,
    platform: Option<String>,
    created_at_ms: i64,
    last_used_at_ms: i64,
    #[sqlx(skip)]
    current: bool,
}

#[derive(Serialize)]
pub struct DeviceSessions {
    sessions: Vec<DeviceSession>,
}

/// The devices signed in to this account, most recently used first. A sign-in
/// is live while its newest refresh token is unrevoked and unexpired.
pub async fn list_sessions(
    State(state): State<AppState>,
    AuthSession(access): AuthSession,
) -> ApiResult<Json<DeviceSessions>> {
    let mut sessions = sqlx::query_as::<_, DeviceSession>(
        r#"
        SELECT t.family_id AS id, s.device_name, s.platform,
               (extract(epoch FROM COALESCE(s.created_at, t.created_at)) * 1000)::bigint AS created_at_ms,
               (extract(epoch FROM GREATEST(s.last_used_at, t.created_at)) * 1000)::bigint AS last_used_at_ms
        FROM refresh_tokens t LEFT JOIN sessions s ON s.family_id = t.family_id
        WHERE t.user_id = $1 AND t.revoked_at IS NULL AND t.expires_at > now()
        ORDER BY last_used_at_ms DESC, id
        "#,
    )
    .bind(access.user)
    .fetch_all(&state.db)
    .await?;
    for s in &mut sessions {
        s.current = Some(s.id) == access.session;
    }
    Ok(Json(DeviceSessions { sessions }))
}

/// Signs one device out. Its access token keeps working until it expires
/// (at most 15 minutes); its refresh token stops at once. Always 204.
pub async fn delete_session(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Path(id): Path<Uuid>,
) -> ApiResult<StatusCode> {
    // Scoped to the caller, so another account's session id does nothing.
    sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = $1 AND user_id = $2 AND revoked_at IS NULL",
    )
    .bind(id)
    .bind(user_id)
    .execute(&state.db)
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize)]
pub struct SignedOut {
    signed_out: u64,
}

/// Signs out every device except the caller's.
pub async fn delete_other_sessions(
    State(state): State<AppState>,
    AuthSession(access): AuthSession,
) -> ApiResult<Json<SignedOut>> {
    let Some(current) = access.session else {
        // Tokens from before device sessions existed cannot tell which sign-in
        // is theirs; they expire within 15 minutes of the upgrade.
        return Err(ApiError::Unauthorized(
            "access token is too old; refresh it",
        ));
    };
    let mut tx = state.db.begin().await?;
    let families: Vec<Uuid> = sqlx::query_scalar(
        r#"
        SELECT DISTINCT family_id FROM refresh_tokens
        WHERE user_id = $1 AND family_id <> $2 AND revoked_at IS NULL AND expires_at > now()
        "#,
    )
    .bind(access.user)
    .bind(current)
    .fetch_all(&mut *tx)
    .await?;
    for &family in &families {
        revoke_family(&mut tx, family).await?;
    }
    tx.commit().await?;
    Ok(Json(SignedOut {
        signed_out: families.len() as u64,
    }))
}

fn bad_request(message: &'static str) -> ApiError {
    ApiError::BadRequest(message.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_names() {
        assert_eq!(
            clean_display_name("  Pan  ").unwrap().as_deref(),
            Some("Pan")
        );
        assert_eq!(clean_display_name("   ").unwrap(), None);
        assert_eq!(clean_display_name("பான்").unwrap().as_deref(), Some("பான்"));
        assert!(clean_display_name("a\u{0}b").is_err());
        assert!(clean_display_name(&"x".repeat(65)).is_err());
        assert!(clean_display_name(&"ப".repeat(64)).is_ok());
    }

    #[test]
    fn update_distinguishes_missing_from_null() {
        let missing: UpdateMe = serde_json::from_str("{}").unwrap();
        assert!(missing.display_name.is_none());
        let null: UpdateMe = serde_json::from_str(r#"{"display_name": null}"#).unwrap();
        assert_eq!(null.display_name, Some(None));
    }
}
