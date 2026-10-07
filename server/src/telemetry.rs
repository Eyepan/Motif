use tracing_subscriber::EnvFilter;

/// Logs as JSON lines on Vercel (searchable in the dashboard), plain text locally.
pub fn init() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    let builder = tracing_subscriber::fmt().with_env_filter(filter);
    let result = if std::env::var_os("VERCEL").is_some() {
        builder.json().with_current_span(false).try_init()
    } else {
        builder.try_init()
    };
    let _ = result;
}
