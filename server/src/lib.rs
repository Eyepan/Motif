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

pub static MIGRATOR: sqlx::migrate::Migrator = sqlx::migrate!("./migrations");

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
