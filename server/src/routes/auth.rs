use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::AppState;
use crate::auth::AuthUser;
use crate::auth::idp::{IdpError, ProviderKind, VerifiedIdentity};
use crate::auth::tokens::{
    ACCESS_TTL_SECS, REFRESH_TTL_DAYS, hash_refresh_token, new_refresh_token,
};
use crate::error::{ApiError, ApiResult};

#[derive(Deserialize)]
pub struct TokenRequest {
    provider: ProviderKind,
    id_token: String,
}

#[derive(Deserialize)]
pub struct RefreshRequest {
    refresh_token: String,
}

#[derive(Serialize)]
pub struct Session {
    user_id: Uuid,
    access_token: String,
    token_type: &'static str,
    expires_in: i64,
    refresh_token: String,
}

/// Exchanges a provider ID token for a Motif session. Creates the account on
/// first sign-in.
pub async fn token(
    State(state): State<AppState>,
    Json(req): Json<TokenRequest>,
) -> ApiResult<Json<Session>> {
    let identity = state
        .identity
        .verify(req.provider, &req.id_token)
        .await
        .map_err(|e| match e {
            IdpError::Disabled => ApiError::BadRequest("sign-in provider is not enabled".into()),
            IdpError::Invalid(_) => ApiError::Unauthorized("invalid ID token"),
            IdpError::Keys(detail) => {
                tracing::error!(%detail, "jwks fetch failed");
                ApiError::Unavailable
            }
        })?;

    // Two first sign-ins for one subject can race; the loser hits the
    // identities primary key and simply runs again, finding the winner's row.
    let mut attempt = 0;
    loop {
        attempt += 1;
        match sign_in(&state, &identity).await {
            Err(e) if attempt == 1 && is_constraint(&e, "identities_pkey") => continue,
            result => return Ok(Json(result?)),
        }
    }
}

async fn sign_in(state: &AppState, identity: &VerifiedIdentity) -> Result<Session, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let new_user = Uuid::now_v7();
    // Upsert keyed on (provider, subject); the insert into users only happens
    // for a subject we have not seen.
    let user_id: Uuid = sqlx::query_scalar(
        r#"
        WITH existing AS (
            UPDATE identities SET last_seen_at = now(), email = COALESCE($3, email)
            WHERE provider = $1 AND subject = $2
            RETURNING user_id
        ), created AS (
            INSERT INTO users (id) SELECT $4 WHERE NOT EXISTS (SELECT 1 FROM existing)
            RETURNING id
        ), linked AS (
            INSERT INTO identities (provider, subject, user_id, email)
            SELECT $1, $2, id, $3 FROM created
            RETURNING user_id
        )
        SELECT user_id FROM existing UNION ALL SELECT user_id FROM linked
        "#,
    )
    .bind(identity.provider.as_str())
    .bind(&identity.subject)
    .bind(&identity.email)
    .bind(new_user)
    .fetch_one(&mut *tx)
    .await?;

    let session = start_session(state, &mut tx, user_id, Uuid::now_v7()).await?;
    tx.commit().await?;
    Ok(session)
}

/// Rotates a refresh token. Each token works once; reusing a rotated token
/// revokes every token from that sign-in.
pub async fn refresh(
    State(state): State<AppState>,
    Json(req): Json<RefreshRequest>,
) -> ApiResult<Json<Session>> {
    let mut tx = state.db.begin().await?;
    let row: Option<(Uuid, Uuid, Uuid, bool, bool, bool)> = sqlx::query_as(
        r#"
        SELECT id, family_id, user_id, revoked_at IS NOT NULL,
               COALESCE(revoked_at > now() - interval '60 seconds', false), expires_at < now()
        FROM refresh_tokens WHERE token_hash = $1 FOR UPDATE
        "#,
    )
    .bind(hash_refresh_token(&req.refresh_token))
    .fetch_optional(&mut *tx)
    .await?;

    let Some((id, family, user_id, revoked, just_rotated, expired)) = row else {
        return Err(ApiError::Unauthorized("invalid refresh token"));
    };
    // A retry after a dropped response presents the token it just rotated.
    // Inside a short grace window that is refused without ending the session.
    if revoked && just_rotated {
        return Err(ApiError::Unauthorized("refresh token already used"));
    }
    if revoked {
        revoke_family(&mut tx, family).await?;
        tx.commit().await?;
        tracing::warn!(%user_id, "refresh token reuse; session revoked");
        return Err(ApiError::Unauthorized("invalid refresh token"));
    }
    if expired {
        return Err(ApiError::Unauthorized("refresh token expired"));
    }

    sqlx::query("UPDATE refresh_tokens SET revoked_at = now() WHERE id = $1")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    let session = start_session(&state, &mut tx, user_id, family).await?;
    tx.commit().await?;
    Ok(Json(session))
}

/// Signs this device out. Always 204, so it is safe to retry.
pub async fn logout(
    State(state): State<AppState>,
    Json(req): Json<RefreshRequest>,
) -> ApiResult<StatusCode> {
    let mut tx = state.db.begin().await?;
    let family: Option<Uuid> =
        sqlx::query_scalar("SELECT family_id FROM refresh_tokens WHERE token_hash = $1")
            .bind(hash_refresh_token(&req.refresh_token))
            .fetch_optional(&mut *tx)
            .await?;
    if let Some(family) = family {
        revoke_family(&mut tx, family).await?;
    }
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize)]
pub struct Me {
    user_id: Uuid,
    username: Option<String>,
    identities: Vec<LinkedIdentity>,
}

#[derive(Serialize, sqlx::FromRow)]
pub struct LinkedIdentity {
    provider: String,
    email: Option<String>,
}

pub async fn me(State(state): State<AppState>, AuthUser(user_id): AuthUser) -> ApiResult<Json<Me>> {
    let user: Option<(Option<String>,)> = sqlx::query_as(
        "SELECT p.username FROM users u LEFT JOIN password_credentials p ON p.user_id = u.id WHERE u.id = $1",
    )
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((username,)) = user else {
        return Err(ApiError::NotFound);
    };
    let identities = sqlx::query_as::<_, LinkedIdentity>(
        "SELECT provider, email FROM identities WHERE user_id = $1 ORDER BY created_at",
    )
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(Me {
        user_id,
        username,
        identities,
    }))
}

pub(super) async fn start_session(
    state: &AppState,
    tx: &mut sqlx::PgConnection,
    user_id: Uuid,
    family: Uuid,
) -> Result<Session, sqlx::Error> {
    let refresh_token = new_refresh_token();
    sqlx::query(
        r#"
        INSERT INTO refresh_tokens (id, family_id, user_id, token_hash, expires_at)
        VALUES ($1, $2, $3, $4, now() + make_interval(days => $5))
        "#,
    )
    .bind(Uuid::now_v7())
    .bind(family)
    .bind(user_id)
    .bind(hash_refresh_token(&refresh_token))
    .bind(REFRESH_TTL_DAYS as i32)
    .execute(&mut *tx)
    .await?;

    let now = jsonwebtoken::get_current_timestamp() as i64;
    Ok(Session {
        user_id,
        access_token: state.tokens.issue_access(user_id, now),
        token_type: "Bearer",
        expires_in: ACCESS_TTL_SECS,
        refresh_token,
    })
}

pub(super) async fn revoke_family(tx: &mut sqlx::PgConnection, family: Uuid) -> ApiResult<()> {
    sqlx::query(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = $1 AND revoked_at IS NULL",
    )
    .bind(family)
    .execute(&mut *tx)
    .await?;
    Ok(())
}

pub(super) fn is_constraint(e: &sqlx::Error, name: &str) -> bool {
    e.as_database_error().and_then(|d| d.constraint()) == Some(name)
}
