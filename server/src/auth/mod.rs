//! Sign-in and session tokens.
//!
//! Apps sign in natively (AuthenticationServices on Apple, Credential Manager
//! on Android) and send the provider's ID token to `POST /v1/auth/token`. The
//! server verifies it against the provider's published keys and answers with
//! its own short-lived access token and a rotating refresh token. Motif never
//! sees or stores a password.

pub mod idp;
pub mod tokens;

use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use uuid::Uuid;

use crate::AppState;
use crate::error::ApiError;

/// The signed-in user, taken from `Authorization: Bearer <access token>`.
pub struct AuthUser(pub Uuid);

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        let header = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or(ApiError::Unauthorized("missing bearer token"))?;
        let token = header
            .strip_prefix("Bearer ")
            .ok_or(ApiError::Unauthorized("missing bearer token"))?;
        let user = state
            .tokens
            .verify_access(token)
            .map_err(|_| ApiError::Unauthorized("invalid or expired access token"))?;
        Ok(AuthUser(user))
    }
}
