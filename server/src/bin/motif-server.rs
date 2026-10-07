//! Runs the API as a plain HTTP server, or applies database migrations.
//!
//!     motif-server            serve on $PORT (default 8080)
//!     motif-server migrate    apply migrations/ to DATABASE_URL_UNPOOLED or DATABASE_URL

use motif_server::{AppState, Config, MIGRATOR, router, telemetry};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    telemetry::init();
    match std::env::args().nth(1).as_deref() {
        None | Some("serve") => serve().await,
        Some("migrate") => migrate().await,
        Some(other) => Err(format!("unknown command {other:?}; expected serve or migrate").into()),
    }
}

async fn serve() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let config = Config::from_env()?;
    let state = AppState::new(&config)?;
    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8080);
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await?;
    tracing::info!(port, "motif-server listening");
    axum::serve(listener, router(state))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}

async fn migrate() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    // Migrations take a session-level advisory lock, which a transaction-mode
    // pooler does not keep; use the direct connection when there is one.
    let url = std::env::var("DATABASE_URL_UNPOOLED")
        .or_else(|_| std::env::var("DATABASE_URL"))
        .map_err(|_| "DATABASE_URL is not set")?;
    let pool = sqlx::PgPool::connect(&url).await?;
    MIGRATOR.run(&pool).await?;
    tracing::info!("migrations applied");
    Ok(())
}
