//! Motif sync service.
//!
//! The apps are offline-first: everything works without this service. It adds
//! accounts and merges what each device records (today the listening history
//! log) across a user's devices. Design: docs/server.md.

pub mod auth;
pub mod config;
pub mod error;
pub mod routes;
pub mod telemetry;

use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use sqlx::PgPool;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};

pub use config::Config;

/// Tolerates migrations in the database that this build does not know. During
/// a rollout, and whenever a preview deployment shares the database, older
/// code runs against a newer schema; migrations are additive, so that works,
/// and refusing to start would take the older deployment down.
pub static MIGRATOR: sqlx::migrate::Migrator = sqlx::migrate::Migrator {
    ignore_missing: true,
    ..sqlx::migrate!("./migrations")
};

/// Applies pending migrations over a direct connection. Migrations hold a
/// session-level advisory lock, which a transaction-mode pooler does not keep,
/// so this prefers DATABASE_URL_UNPOOLED. Concurrent callers wait on that lock
/// and then find nothing left to apply.
pub async fn migrate() -> Result<(), String> {
    let url = std::env::var("DATABASE_URL_UNPOOLED")
        .or_else(|_| std::env::var("DATABASE_URL"))
        .map_err(|_| "DATABASE_URL is not set".to_string())?;
    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(1)
        .acquire_timeout(Duration::from_secs(10))
        .connect(&url)
        .await
        .map_err(|e| format!("database connection failed: {e}"))?;
    let result = MIGRATOR
        .run(&pool)
        .await
        .map_err(|e| format!("migration failed: {e}"));
    pool.close().await;
    result
}

#[derive(Clone)]
pub struct AppState {
    pub db: PgPool,
    pub tokens: Arc<auth::tokens::TokenIssuer>,
    pub identity: Arc<auth::idp::IdentityVerifier>,
}

impl AppState {
    /// Builds the state without touching the network, so a cold start answers
    /// as soon as the binary is up. The pool connects on first use.
    pub fn new(config: &Config) -> Result<Self, String> {
        let mut options: PgConnectOptions = config
            .database_url
            .parse()
            .map_err(|e| format!("DATABASE_URL: {e}"))?;
        if !config.db_statement_cache {
            options = options.statement_cache_capacity(0);
        }
        let db = PgPoolOptions::new()
            // Each function instance keeps its own small pool and serves many
            // requests concurrently (Fluid compute). The database's pooler
            // (Neon's -pooler endpoint) multiplexes instances onto Postgres.
            .max_connections(config.db_max_connections)
            .min_connections(0)
            .acquire_timeout(Duration::from_secs(5))
            .idle_timeout(Duration::from_secs(60))
            .connect_lazy_with(options);
        Ok(Self {
            db,
            tokens: Arc::new(auth::tokens::TokenIssuer::new(&config.jwt_secret)),
            identity: Arc::new(auth::idp::IdentityVerifier::from_config(config)),
        })
    }
}

pub fn router(state: AppState) -> Router {
    routes::router().with_state(state)
}

/// Answers every request with 503 and the configuration problem, so a
/// deployment missing an environment variable says which one.
pub fn unconfigured_router(problem: String) -> Router {
    Router::new().fallback(move || {
        let body = serde_json::json!({
            "status": "unconfigured",
            "error": { "code": "unavailable", "message": format!("server is not configured: {problem}") },
        });
        async move { (axum::http::StatusCode::SERVICE_UNAVAILABLE, axum::Json(body)) }
    })
}

/// Returns a request mapper that removes `prefix` from the path, so the same
/// router serves `/v1/...` whether or not a platform rewrite left its
/// destination in the URL.
pub fn strip_path_prefix(
    prefix: &'static str,
) -> impl Fn(axum::extract::Request) -> axum::extract::Request + Clone {
    move |mut req| {
        let uri = req.uri();
        if let Some(rest) = uri.path().strip_prefix(prefix) {
            let rest = if rest.is_empty() { "/" } else { rest };
            let path_and_query = match uri.query() {
                Some(q) => format!("{rest}?{q}"),
                None => rest.to_string(),
            };
            let mut parts = uri.clone().into_parts();
            if let Ok(pq) = path_and_query.parse() {
                parts.path_and_query = Some(pq);
                if let Ok(new_uri) = axum::http::Uri::from_parts(parts) {
                    *req.uri_mut() = new_uri;
                }
            }
        }
        req
    }
}
