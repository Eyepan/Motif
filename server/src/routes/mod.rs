mod account;
mod auth;
mod events;
mod health;
mod history;
mod password;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::routing::{delete, get, post};
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
        .route("/v1/auth/username", get(account::username_available))
        .route(
            "/v1/me",
            get(account::me)
                .patch(account::update_me)
                .delete(account::delete_me),
        )
        .route(
            "/v1/sessions",
            get(account::list_sessions).delete(account::delete_other_sessions),
        )
        .route("/v1/sessions/{id}", delete(account::delete_session))
        .route("/v1/events", post(events::upload).get(events::pull))
        .route("/v1/history", get(history::read).delete(history::delete))
        .route("/v1/history/summary", get(history::summary))
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
        .layer(TraceLayer::new_for_http())
}
