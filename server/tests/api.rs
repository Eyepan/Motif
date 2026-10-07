//! End-to-end tests through the router against a real Postgres.
//! Set TEST_DATABASE_URL (CI does); without it these tests are skipped.

use axum::Router;
use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use motif_server::{AppState, Config, MIGRATOR, router};
use serde_json::{Value, json};
use tower::ServiceExt;
use uuid::Uuid;

async fn app() -> Option<Router> {
    let Ok(url) = std::env::var("TEST_DATABASE_URL") else {
        eprintln!("TEST_DATABASE_URL not set; skipping database tests");
        return None;
    };
    let config = Config {
        database_url: url.clone(),
        jwt_secret: b"test-secret-test-secret-test-secret!".to_vec(),
        apple_audiences: vec![],
        google_audiences: vec![],
        dev_auth: true,
        db_max_connections: 5,
        db_statement_cache: true,
    };
    let pool = sqlx::PgPool::connect(&url).await.unwrap();
    MIGRATOR.run(&pool).await.unwrap();
    Some(router(AppState::new(&config).unwrap()))
}

async fn call(
    app: &Router,
    method: &str,
    uri: &str,
    token: Option<&str>,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut req = Request::builder().method(method).uri(uri);
    if let Some(t) = token {
        req = req.header("authorization", format!("Bearer {t}"));
    }
    let req = match body {
        Some(b) => req
            .header("content-type", "application/json")
            .body(Body::from(b.to_string())),
        None => req.body(Body::empty()),
    }
    .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    let value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap()
    };
    (status, value)
}

async fn sign_in(app: &Router, subject: &str) -> Value {
    let (status, session) = call(
        app,
        "POST",
        "/v1/auth/token",
        None,
        Some(json!({"provider": "dev", "id_token": subject})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{session}");
    session
}

fn play(device: &str) -> Value {
    json!({
        "id": Uuid::now_v7(),
        "type": "play",
        "v": 1,
        "at_ms": 1_790_000_000_000i64,
        "tz_min": 330,
        "device_id": device,
        "track_key": "sha256:abc",
        "payload": {"listened_ms": 212000, "end_reason": "completed"},
        "synced": 0
    })
}

#[tokio::test]
async fn health_reports_database() {
    let Some(app) = app().await else { return };
    let (status, body) = call(&app, "GET", "/health", None, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["database"], "ok");
}

#[tokio::test]
async fn sign_in_is_stable_and_refresh_rotates() {
    let Some(app) = app().await else { return };
    let subject = format!("user-{}", Uuid::new_v4());
    let first = sign_in(&app, &subject).await;
    let second = sign_in(&app, &subject).await;
    assert_eq!(first["user_id"], second["user_id"]);

    let (status, me) = call(&app, "GET", "/v1/me", first["access_token"].as_str(), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(me["identities"][0]["provider"], "dev");

    let old = first["refresh_token"].clone();
    let (status, rotated) = call(
        &app,
        "POST",
        "/v1/auth/refresh",
        None,
        Some(json!({"refresh_token": old})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_ne!(rotated["refresh_token"], old);

    // Reusing the rotated token fails; the new one still works (grace window).
    let (status, _) = call(
        &app,
        "POST",
        "/v1/auth/refresh",
        None,
        Some(json!({"refresh_token": old})),
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _) = call(
        &app,
        "POST",
        "/v1/auth/refresh",
        None,
        Some(json!({"refresh_token": rotated["refresh_token"]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

    let (status, _) = call(&app, "GET", "/v1/me", Some("not-a-token"), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn logout_revokes_the_session() {
    let Some(app) = app().await else { return };
    let session = sign_in(&app, &format!("user-{}", Uuid::new_v4())).await;
    let token = json!({"refresh_token": session["refresh_token"]});
    let (status, _) = call(&app, "POST", "/v1/auth/logout", None, Some(token.clone())).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(&app, "POST", "/v1/auth/refresh", None, Some(token)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn upload_is_idempotent_and_pull_pages_by_cursor() {
    let Some(app) = app().await else { return };
    let session = sign_in(&app, &format!("user-{}", Uuid::new_v4())).await;
    let token = session["access_token"].as_str();

    let mac: Vec<Value> = (0..3).map(|_| play("mac")).collect();
    let phone = play("phone");
    let mut bad = play("mac");
    bad["id"] = json!(Uuid::new_v4());
    let batch = json!({"events": [mac[0], mac[1], mac[2], phone, bad, mac[0]]});

    let (status, res) = call(&app, "POST", "/v1/events", token, Some(batch.clone())).await;
    assert_eq!(status, StatusCode::OK, "{res}");
    assert_eq!(res["inserted"], 4);
    assert_eq!(res["duplicates"], 0);
    assert_eq!(res["rejected"][0]["index"], 4);

    // Retrying the whole batch inserts nothing.
    let (_, res) = call(&app, "POST", "/v1/events", token, Some(batch)).await;
    assert_eq!(res["inserted"], 0);
    assert_eq!(res["duplicates"], 4);

    // Page through everything, two at a time.
    let (_, page1) = call(&app, "GET", "/v1/events?limit=2", token, None).await;
    assert_eq!(page1["events"].as_array().unwrap().len(), 2);
    assert_eq!(page1["has_more"], true);
    assert_eq!(page1["events"][0]["id"], mac[0]["id"]);
    assert!(page1["events"][0].get("synced").is_none());
    let uri = format!(
        "/v1/events?limit=2&after={}",
        page1["next_cursor"].as_str().unwrap()
    );
    let (_, page2) = call(&app, "GET", &uri, token, None).await;
    assert_eq!(page2["events"].as_array().unwrap().len(), 2);
    assert_eq!(page2["has_more"], false);
    assert_eq!(page2["events"][1]["payload"]["end_reason"], "completed");

    // The phone only needs what other devices recorded.
    let (_, from_others) = call(&app, "GET", "/v1/events?exclude_device=phone", token, None).await;
    assert_eq!(from_others["events"].as_array().unwrap().len(), 3);

    // Another user sees none of it.
    let other = sign_in(&app, &format!("user-{}", Uuid::new_v4())).await;
    let (_, theirs) = call(
        &app,
        "GET",
        "/v1/events",
        other["access_token"].as_str(),
        None,
    )
    .await;
    assert!(theirs["events"].as_array().unwrap().is_empty());

    let (status, _) = call(&app, "GET", "/v1/events", None, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[test]
fn strips_the_function_prefix() {
    let strip = motif_server::strip_path_prefix("/api/motif");
    let req = strip(
        Request::builder()
            .uri("/api/motif/v1/events?after=x")
            .body(Body::empty())
            .unwrap(),
    );
    assert_eq!(req.uri(), "/v1/events?after=x");
    let req = strip(
        Request::builder()
            .uri("/api/motif")
            .body(Body::empty())
            .unwrap(),
    );
    assert_eq!(req.uri(), "/");
    let req = strip(
        Request::builder()
            .uri("/health")
            .body(Body::empty())
            .unwrap(),
    );
    assert_eq!(req.uri(), "/health");
}

#[tokio::test]
async fn password_accounts() {
    let Some(app) = app().await else { return };
    let name = format!("Pan-{}", &Uuid::new_v4().simple().to_string()[..8]);
    let creds = json!({"username": name, "password": "correct horse"});

    let (status, session) =
        call(&app, "POST", "/v1/auth/register", None, Some(creds.clone())).await;
    assert_eq!(status, StatusCode::CREATED, "{session}");
    let (status, _) = call(&app, "POST", "/v1/auth/register", None, Some(creds.clone())).await;
    assert_eq!(status, StatusCode::CONFLICT);
    let (status, res) = call(
        &app,
        "POST",
        "/v1/auth/register",
        None,
        Some(json!({"username": "x y", "password": "correct horse"})),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "{res}");

    // Usernames are case-insensitive.
    let login = json!({"username": name.to_uppercase(), "password": "correct horse"});
    let (status, again) = call(&app, "POST", "/v1/auth/login", None, Some(login)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(again["user_id"], session["user_id"]);

    let (_, me) = call(
        &app,
        "GET",
        "/v1/me",
        session["access_token"].as_str(),
        None,
    )
    .await;
    assert_eq!(me["username"], name.to_lowercase());

    // Wrong password and unknown user look the same.
    let (status, wrong) = call(
        &app,
        "POST",
        "/v1/auth/login",
        None,
        Some(json!({"username": name, "password": "wrong horse"})),
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (_, unknown) = call(
        &app,
        "POST",
        "/v1/auth/login",
        None,
        Some(json!({"username": "nobody-here", "password": "wrong horse"})),
    )
    .await;
    assert_eq!(wrong, unknown);

    // Changing the password signs out the other sessions.
    let change = json!({"current_password": "correct horse", "new_password": "battery staple"});
    let (status, changed) = call(
        &app,
        "POST",
        "/v1/auth/password",
        session["access_token"].as_str(),
        Some(change),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{changed}");
    let (status, _) = call(
        &app,
        "POST",
        "/v1/auth/refresh",
        None,
        Some(json!({"refresh_token": again["refresh_token"]})),
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _) = call(&app, "POST", "/v1/auth/login", None, Some(creds)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _) = call(
        &app,
        "POST",
        "/v1/auth/login",
        None,
        Some(json!({"username": name, "password": "battery staple"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn repeated_wrong_passwords_lock_the_username() {
    let Some(app) = app().await else { return };
    let name = format!("lock-{}", &Uuid::new_v4().simple().to_string()[..8]);
    let (status, _) = call(
        &app,
        "POST",
        "/v1/auth/register",
        None,
        Some(json!({"username": name, "password": "correct horse"})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    for _ in 0..10 {
        let (status, _) = call(
            &app,
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({"username": name, "password": "wrong horse"})),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }
    let (status, _) = call(
        &app,
        "POST",
        "/v1/auth/login",
        None,
        Some(json!({"username": name, "password": "correct horse"})),
    )
    .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn unconfigured_server_names_the_problem() {
    let app = motif_server::unconfigured_router("DATABASE_URL is not set".into());
    let (status, body) = call(&app, "GET", "/health", None, None).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(
        body["error"]["message"]
            .as_str()
            .unwrap()
            .contains("DATABASE_URL")
    );
}
