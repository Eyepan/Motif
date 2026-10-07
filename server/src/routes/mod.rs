mod auth;
mod events;
mod health;
mod password;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::routing::{get, post};
use tower_http::trace::TraceLayer;

use crate::AppState;

/// Vercel Functions accept request bodies up to 4.5 MB; stay under it so the
/// error is ours, not the platform's.
pub const MAX_BODY_BYTES: usize = 4 * 1024 * 1024;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(health::index))
        .route("/health", get(health::health))
        .route("/v1/auth/register", post(password::register))
        .route("/v1/auth/login", post(password::login))
        .route("/v1/auth/password", post(password::change))
        .route("/v1/auth/token", post(auth::token))
        .route("/v1/auth/refresh", post(auth::refresh))
        .route("/v1/auth/logout", post(auth::logout))
        .route("/v1/me", get(auth::me))
        .route("/v1/events", post(events::upload).get(events::pull))
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
        .layer(TraceLayer::new_for_http())
}
