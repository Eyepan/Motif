//! Vercel Function entry point. vercel.json rewrites every path here; the
//! axum router in the library does the routing, exactly as `motif-server` does.

use motif_server::{AppState, Config, router, telemetry};
use tower::ServiceBuilder;
use vercel_runtime::axum::VercelLayer;

/// The function's own path, in case the platform passes the rewrite
/// destination instead of the original path.
const FUNCTION_PATH: &str = "/api/motif";

#[tokio::main]
async fn main() -> Result<(), vercel_runtime::Error> {
    telemetry::init();
    // A missing setting answers every request with a 503 that names it,
    // instead of crashing into an opaque FUNCTION_INVOCATION_FAILED.
    let app = match Config::from_env().and_then(|config| AppState::new(&config)) {
        Ok(state) => router(state),
        Err(problem) => {
            tracing::error!(%problem, "server is not configured");
            motif_server::unconfigured_router(problem)
        }
    };
    let app = ServiceBuilder::new()
        .layer(VercelLayer::new())
        .map_request(motif_server::strip_path_prefix(FUNCTION_PATH))
        .service(app);
    vercel_runtime::run(app).await
}
