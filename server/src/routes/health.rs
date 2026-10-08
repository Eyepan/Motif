use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde_json::{Value, json};

use crate::AppState;

pub async fn index() -> Json<Value> {
    Json(json!({ "service": "motif", "version": env!("CARGO_PKG_VERSION") }))
}

/// Liveness plus a database round trip. 503 when the database is unreachable.
pub async fn health(State(state): State<AppState>) -> (StatusCode, Json<Value>) {
    let db_ok = sqlx::query("SELECT 1").execute(&state.db).await.is_ok();
    let status = if db_ok {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    };
    let body = json!({
        "status": if db_ok { "ok" } else { "degraded" },
        "version": env!("CARGO_PKG_VERSION"),
        "database": if db_ok { "ok" } else { "unreachable" },
        // Set by Vercel (e.g. "sin1"); absent when running elsewhere.
        "region": std::env::var("VERCEL_REGION").ok(),
    });
    (status, Json(body))
}
