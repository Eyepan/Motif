//! Username and password accounts. There is no email, so a forgotten password
//! cannot be reset: the history stays on the user's devices and uploads again
//! to a new account.

use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde::Deserialize;
use uuid::Uuid;

use super::auth::{Session, is_constraint, start_session};
use crate::AppState;
use crate::auth::AuthUser;
use crate::auth::password;
use crate::error::{ApiError, ApiResult};

/// Wrong passwords in a row before the username is locked.
const MAX_FAILED_ATTEMPTS: i32 = 10;
const LOCKOUT_MINUTES: i32 = 15;
const INVALID_LOGIN: &str = "invalid username or password";

#[derive(Deserialize)]
pub struct Credentials {
    username: String,
    password: String,
}

#[derive(Deserialize)]
pub struct ChangePassword {
    current_password: String,
    new_password: String,
}

pub async fn register(
    State(state): State<AppState>,
    Json(req): Json<Credentials>,
) -> ApiResult<(StatusCode, Json<Session>)> {
    let username = password::normalize_username(&req.username).map_err(bad_request)?;
    password::check_password(&req.password).map_err(bad_request)?;
    let hash = hash_blocking(req.password).await?;

    let mut tx = state.db.begin().await?;
    let user_id = Uuid::now_v7();
    sqlx::query("INSERT INTO users (id) VALUES ($1)")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO password_credentials (user_id, username, password_hash) VALUES ($1, $2, $3)",
    )
    .bind(user_id)
    .bind(&username)
    .bind(&hash)
    .execute(&mut *tx)
    .await
    .map_err(|e| {
        if is_constraint(&e, "password_credentials_username_key") {
            ApiError::Conflict("username is taken")
        } else {
            e.into()
        }
    })?;
    let session = start_session(&state, &mut tx, user_id, Uuid::now_v7()).await?;
    tx.commit().await?;
    Ok((StatusCode::CREATED, Json(session)))
}

pub async fn login(
    State(state): State<AppState>,
    Json(req): Json<Credentials>,
) -> ApiResult<Json<Session>> {
    let row: Option<(Uuid, String, bool)> = match password::normalize_username(&req.username) {
        Ok(username) => {
            sqlx::query_as(
                r#"
                SELECT user_id, password_hash, COALESCE(locked_until > now(), false)
                FROM password_credentials WHERE username = $1
                "#,
            )
            .bind(username)
            .fetch_optional(&state.db)
            .await?
        }
        Err(_) => None,
    };

    let Some((user_id, stored, locked)) = row else {
        let pw = req.password;
        let _ = tokio::task::spawn_blocking(move || password::verify_dummy(&pw)).await;
        return Err(ApiError::Unauthorized(INVALID_LOGIN));
    };
    if locked {
        return Err(ApiError::TooManyRequests(
            "too many wrong passwords; try again in 15 minutes",
        ));
    }

    if !verify_blocking(req.password, stored).await? {
        // Every 10th consecutive failure locks the username for 15 minutes.
        sqlx::query(
            r#"
            UPDATE password_credentials SET
                locked_until = CASE WHEN failed_attempts + 1 >= $2
                                    THEN now() + make_interval(mins => $3) ELSE locked_until END,
                failed_attempts = CASE WHEN failed_attempts + 1 >= $2 THEN 0 ELSE failed_attempts + 1 END
            WHERE user_id = $1
            "#,
        )
        .bind(user_id)
        .bind(MAX_FAILED_ATTEMPTS)
        .bind(LOCKOUT_MINUTES)
        .execute(&state.db)
        .await?;
        return Err(ApiError::Unauthorized(INVALID_LOGIN));
    }

    let mut tx = state.db.begin().await?;
    sqlx::query("UPDATE password_credentials SET failed_attempts = 0, locked_until = NULL WHERE user_id = $1")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    let session = start_session(&state, &mut tx, user_id, Uuid::now_v7()).await?;
    tx.commit().await?;
    Ok(Json(session))
}

/// Changes the password and signs out every other device.
pub async fn change(
    State(state): State<AppState>,
    AuthUser(user_id): AuthUser,
    Json(req): Json<ChangePassword>,
) -> ApiResult<Json<Session>> {
    password::check_password(&req.new_password).map_err(bad_request)?;
    let stored: Option<String> =
        sqlx::query_scalar("SELECT password_hash FROM password_credentials WHERE user_id = $1")
            .bind(user_id)
            .fetch_optional(&state.db)
            .await?;
    let Some(stored) = stored else {
        return Err(ApiError::BadRequest("this account has no password".into()));
    };
    if !verify_blocking(req.current_password, stored).await? {
        return Err(ApiError::Unauthorized("current password is wrong"));
    }
    let hash = hash_blocking(req.new_password).await?;

    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE password_credentials SET password_hash = $2, updated_at = now() WHERE user_id = $1",
    )
    .bind(user_id)
    .bind(&hash)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL",
    )
    .bind(user_id)
    .execute(&mut *tx)
    .await?;
    let session = start_session(&state, &mut tx, user_id, Uuid::now_v7()).await?;
    tx.commit().await?;
    Ok(Json(session))
}

fn bad_request(message: &'static str) -> ApiError {
    ApiError::BadRequest(message.into())
}

async fn hash_blocking(pw: String) -> ApiResult<String> {
    tokio::task::spawn_blocking(move || password::hash(&pw))
        .await
        .map_err(|e| ApiError::Internal(e.to_string()))
}

async fn verify_blocking(pw: String, stored: String) -> ApiResult<bool> {
    tokio::task::spawn_blocking(move || password::verify(&pw, &stored))
        .await
        .map_err(|e| ApiError::Internal(e.to_string()))
}
