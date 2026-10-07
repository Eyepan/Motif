use std::env;

/// Settings read from the environment. On Vercel these are project
/// environment variables; the Neon integration sets DATABASE_URL itself.
#[derive(Clone)]
pub struct Config {
    /// Pooled connection string used by the API.
    pub database_url: String,
    /// HMAC key for access tokens, at least 32 bytes.
    pub jwt_secret: Vec<u8>,
    /// Accepted `aud` values for Sign in with Apple ID tokens (bundle ids).
    pub apple_audiences: Vec<String>,
    /// Accepted `aud` values for Google ID tokens (OAuth client ids).
    pub google_audiences: Vec<String>,
    /// Accept `provider: "dev"` sign-ins with any subject. Never in production.
    pub dev_auth: bool,
    pub db_max_connections: u32,
    /// Set MOTIF_DB_STATEMENT_CACHE=0 if the pooler rejects named prepared statements.
    pub db_statement_cache: bool,
}

impl Config {
    pub fn from_env() -> Result<Self, String> {
        let database_url = env::var("DATABASE_URL")
            .or_else(|_| env::var("POSTGRES_URL"))
            .map_err(|_| "DATABASE_URL is not set".to_string())?;

        let jwt_secret = env::var("MOTIF_JWT_SECRET")
            .map_err(|_| "MOTIF_JWT_SECRET is not set".to_string())?
            .into_bytes();
        if jwt_secret.len() < 32 {
            return Err("MOTIF_JWT_SECRET must be at least 32 bytes".into());
        }

        let dev_auth = flag("MOTIF_DEV_AUTH", false);
        if dev_auth && env::var("VERCEL_ENV").as_deref() == Ok("production") {
            return Err("MOTIF_DEV_AUTH must not be enabled in production".into());
        }

        Ok(Self {
            database_url,
            jwt_secret,
            apple_audiences: list("MOTIF_APPLE_AUDIENCES", &["app.motif.Motif"]),
            google_audiences: list("MOTIF_GOOGLE_CLIENT_IDS", &[]),
            dev_auth,
            db_max_connections: env::var("MOTIF_DB_MAX_CONNECTIONS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(5),
            db_statement_cache: flag("MOTIF_DB_STATEMENT_CACHE", true),
        })
    }
}

fn flag(name: &str, default: bool) -> bool {
    match env::var(name).as_deref() {
        Ok("1" | "true" | "yes") => true,
        Ok("0" | "false" | "no") => false,
        _ => default,
    }
}

fn list(name: &str, default: &[&str]) -> Vec<String> {
    match env::var(name) {
        Ok(v) => v
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(String::from)
            .collect(),
        Err(_) => default.iter().map(|s| s.to_string()).collect(),
    }
}
