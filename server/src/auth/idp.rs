//! Verifies ID tokens from Sign in with Apple and Google.

use std::time::{Duration, Instant};

use jsonwebtoken::jwk::JwkSet;
use jsonwebtoken::{Algorithm, DecodingKey, Validation};
use serde::Deserialize;
use tokio::sync::RwLock;

use crate::config::Config;

const JWKS_TTL: Duration = Duration::from_secs(60 * 60);
const JWKS_MIN_REFETCH: Duration = Duration::from_secs(60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProviderKind {
    Apple,
    Google,
    /// Local development and tests only: the "ID token" is taken as the subject.
    Dev,
}

impl ProviderKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ProviderKind::Apple => "apple",
            ProviderKind::Google => "google",
            ProviderKind::Dev => "dev",
        }
    }
}

#[derive(Debug, Clone)]
pub struct VerifiedIdentity {
    pub provider: ProviderKind,
    pub subject: String,
    pub email: Option<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum IdpError {
    #[error("sign-in provider is not enabled")]
    Disabled,
    #[error("invalid ID token: {0}")]
    Invalid(String),
    #[error("could not fetch the provider's signing keys: {0}")]
    Keys(String),
}

pub enum KeySource {
    Remote(String),
    /// Fixed keys, for tests.
    Static(JwkSet),
}

pub struct Provider {
    kind: ProviderKind,
    issuers: Vec<String>,
    audiences: Vec<String>,
    keys: KeySource,
    cache: RwLock<Option<(JwkSet, Instant)>>,
}

impl Provider {
    pub fn new(
        kind: ProviderKind,
        issuers: &[&str],
        audiences: Vec<String>,
        keys: KeySource,
    ) -> Self {
        Self {
            kind,
            issuers: issuers.iter().map(|s| s.to_string()).collect(),
            audiences,
            keys,
            cache: RwLock::new(None),
        }
    }

    pub fn apple(audiences: Vec<String>) -> Self {
        Self::new(
            ProviderKind::Apple,
            &["https://appleid.apple.com"],
            audiences,
            KeySource::Remote("https://appleid.apple.com/auth/keys".into()),
        )
    }

    pub fn google(audiences: Vec<String>) -> Self {
        Self::new(
            ProviderKind::Google,
            &["https://accounts.google.com", "accounts.google.com"],
            audiences,
            KeySource::Remote("https://www.googleapis.com/oauth2/v3/certs".into()),
        )
    }

    async fn verify(
        &self,
        http: &reqwest::Client,
        token: &str,
    ) -> Result<VerifiedIdentity, IdpError> {
        if self.audiences.is_empty() {
            return Err(IdpError::Disabled);
        }
        let header =
            jsonwebtoken::decode_header(token).map_err(|e| IdpError::Invalid(e.to_string()))?;
        if header.alg != Algorithm::RS256 {
            return Err(IdpError::Invalid("unexpected algorithm".into()));
        }
        let kid = header
            .kid
            .ok_or_else(|| IdpError::Invalid("missing kid".into()))?;
        let key = self.key_for(http, &kid).await?;

        let mut validation = Validation::new(Algorithm::RS256);
        validation.set_issuer(&self.issuers);
        validation.set_audience(&self.audiences);
        validation.set_required_spec_claims(&["exp", "iss", "aud", "sub"]);
        validation.leeway = 60;

        let data = jsonwebtoken::decode::<IdClaims>(token, &key, &validation)
            .map_err(|e| IdpError::Invalid(e.to_string()))?;
        let claims = data.claims;
        // Apple sends email_verified as a string, Google as a bool.
        let verified = matches!(claims.email_verified, Some(serde_json::Value::Bool(true)))
            || matches!(claims.email_verified, Some(serde_json::Value::String(ref s)) if s == "true");
        Ok(VerifiedIdentity {
            provider: self.kind,
            subject: claims.sub,
            email: claims.email.filter(|_| verified),
        })
    }

    async fn key_for(&self, http: &reqwest::Client, kid: &str) -> Result<DecodingKey, IdpError> {
        let url = match &self.keys {
            KeySource::Static(set) => return decoding_key(set, kid),
            KeySource::Remote(url) => url,
        };
        if let Some((set, fetched)) = self.cache.read().await.as_ref()
            && fetched.elapsed() < JWKS_TTL
            && set.find(kid).is_some()
        {
            return decoding_key(set, kid);
        }

        let mut cache = self.cache.write().await;
        // Another request may have refreshed while we waited. Keys rotate
        // rarely, so an unknown kid only triggers a refetch once a minute.
        let stale = match cache.as_ref() {
            None => true,
            Some((set, fetched)) => {
                fetched.elapsed() >= JWKS_TTL
                    || (set.find(kid).is_none() && fetched.elapsed() >= JWKS_MIN_REFETCH)
            }
        };
        if stale {
            let set: JwkSet = http
                .get(url)
                .timeout(Duration::from_secs(5))
                .send()
                .await
                .and_then(|r| r.error_for_status())
                .map_err(|e| IdpError::Keys(e.to_string()))?
                .json()
                .await
                .map_err(|e| IdpError::Keys(e.to_string()))?;
            *cache = Some((set, Instant::now()));
        }
        let (set, _) = cache.as_ref().expect("cache was just filled");
        decoding_key(set, kid)
    }
}

fn decoding_key(set: &JwkSet, kid: &str) -> Result<DecodingKey, IdpError> {
    let jwk = set
        .find(kid)
        .ok_or_else(|| IdpError::Invalid("unknown signing key".into()))?;
    DecodingKey::from_jwk(jwk).map_err(|e| IdpError::Invalid(e.to_string()))
}

#[derive(Deserialize)]
struct IdClaims {
    sub: String,
    email: Option<String>,
    email_verified: Option<serde_json::Value>,
}

pub struct IdentityVerifier {
    http: reqwest::Client,
    apple: Provider,
    google: Provider,
    dev: bool,
}

impl IdentityVerifier {
    pub fn new(apple: Provider, google: Provider, dev: bool) -> Self {
        Self {
            http: reqwest::Client::new(),
            apple,
            google,
            dev,
        }
    }

    pub fn from_config(config: &Config) -> Self {
        Self::new(
            Provider::apple(config.apple_audiences.clone()),
            Provider::google(config.google_audiences.clone()),
            config.dev_auth,
        )
    }

    pub async fn verify(
        &self,
        provider: ProviderKind,
        token: &str,
    ) -> Result<VerifiedIdentity, IdpError> {
        match provider {
            ProviderKind::Apple => self.apple.verify(&self.http, token).await,
            ProviderKind::Google => self.google.verify(&self.http, token).await,
            ProviderKind::Dev if self.dev => {
                if token.is_empty() || token.len() > 128 {
                    return Err(IdpError::Invalid("dev subject must be 1-128 bytes".into()));
                }
                Ok(VerifiedIdentity {
                    provider,
                    subject: token.to_string(),
                    email: None,
                })
            }
            ProviderKind::Dev => Err(IdpError::Disabled),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use jsonwebtoken::{EncodingKey, Header};
    use serde_json::json;

    // A throwaway RSA key generated for these tests only.
    const TEST_KEY_PEM: &str = include_str!("../../tests/fixtures/test-rsa.pem");
    const TEST_JWKS: &str = include_str!("../../tests/fixtures/test-jwks.json");

    fn provider() -> Provider {
        let set: JwkSet = serde_json::from_str(TEST_JWKS).unwrap();
        Provider::new(
            ProviderKind::Apple,
            &["https://appleid.apple.com"],
            vec!["app.motif.Motif".into()],
            KeySource::Static(set),
        )
    }

    fn sign(claims: serde_json::Value, kid: &str) -> String {
        let mut header = Header::new(Algorithm::RS256);
        header.kid = Some(kid.into());
        let key = EncodingKey::from_rsa_pem(TEST_KEY_PEM.as_bytes()).unwrap();
        jsonwebtoken::encode(&header, &claims, &key).unwrap()
    }

    fn claims(aud: &str) -> serde_json::Value {
        let now = jsonwebtoken::get_current_timestamp();
        json!({
            "iss": "https://appleid.apple.com",
            "aud": aud,
            "sub": "001234.abcdef",
            "email": "x@privaterelay.appleid.com",
            "email_verified": "true",
            "iat": now,
            "exp": now + 600,
        })
    }

    #[tokio::test]
    async fn accepts_a_valid_apple_token() {
        let http = reqwest::Client::new();
        let id = provider()
            .verify(&http, &sign(claims("app.motif.Motif"), "test-key"))
            .await
            .unwrap();
        assert_eq!(id.subject, "001234.abcdef");
        assert_eq!(id.email.as_deref(), Some("x@privaterelay.appleid.com"));
    }

    #[tokio::test]
    async fn rejects_wrong_audience_and_unknown_key() {
        let http = reqwest::Client::new();
        let p = provider();
        assert!(
            p.verify(&http, &sign(claims("com.example.other"), "test-key"))
                .await
                .is_err()
        );
        assert!(
            p.verify(&http, &sign(claims("app.motif.Motif"), "nope"))
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn rejects_expired_token() {
        let http = reqwest::Client::new();
        let mut c = claims("app.motif.Motif");
        c["exp"] = json!(jsonwebtoken::get_current_timestamp() - 3600);
        assert!(
            provider()
                .verify(&http, &sign(c, "test-key"))
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn dev_provider_only_when_enabled() {
        let off = IdentityVerifier::new(Provider::apple(vec![]), Provider::google(vec![]), false);
        assert!(off.verify(ProviderKind::Dev, "me").await.is_err());
        let on = IdentityVerifier::new(Provider::apple(vec![]), Provider::google(vec![]), true);
        assert_eq!(
            on.verify(ProviderKind::Dev, "me").await.unwrap().subject,
            "me"
        );
        // A provider with no configured audience is off.
        assert!(matches!(
            on.verify(ProviderKind::Google, "x").await,
            Err(IdpError::Disabled)
        ));
    }
}
