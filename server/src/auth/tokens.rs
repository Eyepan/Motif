use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use jsonwebtoken::{Algorithm, DecodingKey, EncodingKey, Header, Validation};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub const ACCESS_TTL_SECS: i64 = 15 * 60;
pub const REFRESH_TTL_DAYS: i64 = 90;

const ISSUER: &str = "motif";
const AUDIENCE: &str = "motif-api";
const REFRESH_PREFIX: &str = "mrt_";

#[derive(Serialize, Deserialize)]
struct AccessClaims {
    sub: Uuid,
    iss: String,
    aud: String,
    iat: i64,
    exp: i64,
}

/// Issues and checks access tokens (HS256 JWTs, stateless) and mints refresh
/// tokens (random, stored hashed in `refresh_tokens`).
pub struct TokenIssuer {
    encoding: EncodingKey,
    decoding: DecodingKey,
    validation: Validation,
}

impl TokenIssuer {
    pub fn new(secret: &[u8]) -> Self {
        let mut validation = Validation::new(Algorithm::HS256);
        validation.set_issuer(&[ISSUER]);
        validation.set_audience(&[AUDIENCE]);
        validation.leeway = 30;
        Self {
            encoding: EncodingKey::from_secret(secret),
            decoding: DecodingKey::from_secret(secret),
            validation,
        }
    }

    pub fn issue_access(&self, user: Uuid, now: i64) -> String {
        let claims = AccessClaims {
            sub: user,
            iss: ISSUER.into(),
            aud: AUDIENCE.into(),
            iat: now,
            exp: now + ACCESS_TTL_SECS,
        };
        jsonwebtoken::encode(&Header::new(Algorithm::HS256), &claims, &self.encoding)
            .expect("HS256 encoding cannot fail")
    }

    pub fn verify_access(&self, token: &str) -> Result<Uuid, jsonwebtoken::errors::Error> {
        jsonwebtoken::decode::<AccessClaims>(token, &self.decoding, &self.validation)
            .map(|data| data.claims.sub)
    }
}

/// A new refresh token: 256 random bits, prefixed so it is recognisable in logs
/// and secret scanners.
pub fn new_refresh_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    format!("{REFRESH_PREFIX}{}", URL_SAFE_NO_PAD.encode(bytes))
}

pub fn hash_refresh_token(token: &str) -> Vec<u8> {
    Sha256::digest(token.as_bytes()).to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn access_token_round_trip() {
        let issuer = TokenIssuer::new(&[7u8; 32]);
        let user = Uuid::now_v7();
        let now = jsonwebtoken::get_current_timestamp() as i64;
        let token = issuer.issue_access(user, now);
        assert_eq!(issuer.verify_access(&token).unwrap(), user);
    }

    #[test]
    fn rejects_expired_and_foreign_tokens() {
        let issuer = TokenIssuer::new(&[7u8; 32]);
        let other = TokenIssuer::new(&[8u8; 32]);
        let user = Uuid::now_v7();
        let now = jsonwebtoken::get_current_timestamp() as i64;
        assert!(
            issuer
                .verify_access(&other.issue_access(user, now))
                .is_err()
        );
        let stale = issuer.issue_access(user, now - ACCESS_TTL_SECS - 120);
        assert!(issuer.verify_access(&stale).is_err());
    }

    #[test]
    fn refresh_tokens_are_unique_and_prefixed() {
        let a = new_refresh_token();
        let b = new_refresh_token();
        assert_ne!(a, b);
        assert!(a.starts_with(REFRESH_PREFIX));
        assert_eq!(hash_refresh_token(&a).len(), 32);
    }
}
