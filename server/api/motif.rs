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
    let config = Config::from_env()?;
    let state = AppState::new(&config)?;
    let app = ServiceBuilder::new()
        .layer(VercelLayer::new())
        .map_request(motif_server::strip_path_prefix(FUNCTION_PATH))
        .service(router(state));
    vercel_runtime::run(app).await
}
