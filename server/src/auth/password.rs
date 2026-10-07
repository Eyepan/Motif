//! Username and password sign-in: validation and Argon2id hashing.

use std::sync::OnceLock;

use argon2::Argon2;
use argon2::password_hash::{PasswordHasher, PasswordVerifier};

pub const MIN_PASSWORD_CHARS: usize = 8;
pub const MAX_PASSWORD_BYTES: usize = 256;

/// Lowercases and checks a username: 3-32 of `a-z 0-9 . _ -`, starting with a
/// letter or digit. Usernames are case-insensitive.
pub fn normalize_username(raw: &str) -> Result<String, &'static str> {
    let name = raw.trim().to_ascii_lowercase();
    let ok = (3..=32).contains(&name.len())
        && name.starts_with(|c: char| c.is_ascii_alphanumeric())
        && name
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '.' | '_' | '-'));
    if ok {
        Ok(name)
    } else {
        Err("username must be 3-32 characters: letters, digits, '.', '_' or '-'")
    }
}

pub fn check_password(password: &str) -> Result<(), &'static str> {
    if password.chars().count() < MIN_PASSWORD_CHARS {
        return Err("password must be at least 8 characters");
    }
    if password.len() > MAX_PASSWORD_BYTES {
        return Err("password must be at most 256 bytes");
    }
    Ok(())
}

/// Argon2id with the crate defaults (19 MiB, 2 passes), the OWASP baseline.
/// CPU-heavy, so callers run it on the blocking pool.
pub fn hash(password: &str) -> String {
    Argon2::default()
        .hash_password(password.as_bytes())
        .expect("argon2 with default params cannot fail")
        .to_string()
}

pub fn verify(password: &str, stored: &str) -> bool {
    Argon2::default()
        .verify_password(password.as_bytes(), stored)
        .is_ok()
}

/// Verifies against a fixed hash so an unknown username takes as long as a
/// wrong password, and response time does not reveal which usernames exist.
pub fn verify_dummy(password: &str) {
    static DUMMY: OnceLock<String> = OnceLock::new();
    let stored = DUMMY.get_or_init(|| hash("motif-dummy-password"));
    let _ = verify(password, stored);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usernames_are_case_insensitive_and_restricted() {
        assert_eq!(normalize_username(" Pan.DJ ").unwrap(), "pan.dj");
        assert!(normalize_username("ab").is_err());
        assert!(normalize_username("_pan").is_err());
        assert!(normalize_username("pan pan").is_err());
        assert!(normalize_username("பான்").is_err());
        assert!(normalize_username(&"a".repeat(33)).is_err());
    }

    #[test]
    fn password_length_rules() {
        assert!(check_password("short").is_err());
        assert!(check_password("long enough").is_ok());
        assert!(check_password(&"x".repeat(MAX_PASSWORD_BYTES + 1)).is_err());
    }

    #[test]
    fn hash_and_verify() {
        let stored = hash("correct horse");
        assert!(stored.starts_with("$argon2id$"));
        assert!(verify("correct horse", &stored));
        assert!(!verify("wrong horse", &stored));
        assert!(!verify("correct horse", "not a hash"));
    }
}
