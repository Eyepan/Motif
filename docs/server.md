# Server and sync

Status: first version scaffolded in `server/`. Covers what the server does, how it runs on Vercel, how the apps talk to it, and the decisions still open.

## What the server is for

Motif stays local-first. Playback, the library, analysis and DJ mixing run on the device and work with no network and no account. The server adds the things a single device cannot do on its own:

- **Accounts**: sign in with Apple or Google, so a user's devices can find each other.
- **The listening history log**: every device uploads its events and pulls the others', so each device ends up holding the full merged log (docs/analytics.md). Recaps are computed from that log.
- **Library metadata, playlists and crates** (next): the same mechanism, see Open decisions.

It does not store or stream audio. Files stay on each device. Syncing audio through user-owned storage such as S3 is a later step and does not change anything here.

## Stack

| Piece | Choice | Why |
| --- | --- | --- |
| Language | Rust, axum 0.8, tokio | Same language as `core/`; future `core/recap` code can run on the server unchanged |
| Database | Postgres via sqlx (runtime queries, no compile-time DB needed) | Relational data plus `jsonb` payloads; Neon on Vercel |
| Hosting | One Vercel Function using the Rust runtime (`vercel_runtime` 2 with its axum adapter) | Push-to-deploy, preview URL per PR, no servers to run |
| Migrations | `sqlx` migrations embedded in the binary, run by `motif-server migrate` | Applied by a GitHub Action, never on a cold start |
| Contract | `schemas/api/openapi.yaml` | Shared by the Apple and Android clients like the other schemas |

The same axum router is built into two binaries: `api/motif.rs` (the Vercel Function) and `motif-server` (a plain HTTP server for local development, tests, and hosting anywhere else). Nothing in the handlers knows about Vercel, so moving to Fly, Railway, a container or a home server later is a build-target change, not a rewrite.

## Running on Vercel

- **One function for the whole API.** `vercel.json` rewrites every path to `api/motif`, and axum routes inside it. One function means one cold start and one connection pool instead of one per endpoint. If the platform hands the function the rewritten path instead of the original, a small layer strips the `/api/motif` prefix, so both work; the first preview deploy will show which one Vercel does.
- **Fluid compute.** The Rust runtime runs the binary as a long-lived HTTP server that handles many requests concurrently per instance. The database pool and the cached Apple and Google signing keys are shared across those requests.
- **Limits that shape the API.** Request bodies are capped at 4.5 MB, so uploads are limited to 1,000 events and 4 MB per batch. `maxDuration` is set to 30 s; nothing should come close. There are no long-lived connections, so sync is pull-based (on launch, on foreground, after an upload), not a live socket.
- **Cold starts.** The pool connects lazily, so a cold instance answers `/health` and token checks without waiting on the database.
- **Build.** Vercel builds the release binary on each deploy (thin LTO). CI builds the same binary on every PR so a broken build never reaches Vercel.

### Postgres and connection pooling

Recommended: **Neon from the Vercel Marketplace**. Connecting it sets `DATABASE_URL` (the pooled `-pooler` endpoint, PgBouncer in transaction mode) and `DATABASE_URL_UNPOOLED` (direct) on the project.

- The API uses the pooled URL with a small per-instance pool (`MOTIF_DB_MAX_CONNECTIONS`, default 5). Many function instances then share Neon's pooler instead of each holding direct Postgres connections.
- Migrations use the direct URL, because they hold a session-level advisory lock that a transaction-mode pooler does not keep.
- If the pooler ever rejects prepared statements, `MOTIF_DB_STATEMENT_CACHE=0` turns off sqlx's statement cache without a code change.
- Put the function region (`regions` in `vercel.json`) and the Neon region next to each other; every request does at least one round trip between them.

Supabase and other Postgres hosts work the same way: anything with a pooled and a direct connection string.

### Deploying

The project owner does this once (steps in `server/README.md`): import the repo in Vercel with Root Directory `server`, connect Neon, set `MOTIF_JWT_SECRET` and the sign-in audiences, and add the direct database URL as the `MOTIF_DATABASE_URL` GitHub secret. After that, merging to `main` deploys production and the **Server migrations** workflow applies new migrations.

Vercel deploys the new code while the workflow migrates, so for a short time old code runs on the new schema and possibly new code on the old one. Migrations therefore stay additive: add a column or table in one release, start using it, and remove the old one in a later release.

## API

Full contract: `schemas/api/openapi.yaml`. All bodies are JSON; errors are `{"error": {"code", "message"}}`.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness plus a database round trip; 503 when the database is down |
| `POST /v1/auth/token` | Exchange an Apple or Google ID token for a Motif session; creates the account on first sign-in |
| `POST /v1/auth/refresh` | Rotate the refresh token |
| `POST /v1/auth/logout` | Revoke this device's session |
| `GET /v1/me` | The account and its linked sign-in methods |
| `POST /v1/events` | Upload a batch of history events, idempotent on event id |
| `GET /v1/events?after=&limit=&exclude_device=` | Pull events in server arrival order |

### Accounts and tokens

The apps sign in natively and send the provider's ID token. The server checks its signature against the provider's published keys (cached for an hour), its issuer, audience and expiry, then finds or creates the user by `(provider, subject)`. Motif never handles passwords.

- **Access token**: an HS256 JWT signed with `MOTIF_JWT_SECRET`, valid 15 minutes, checked without a database lookup.
- **Refresh token**: 256 random bits, stored only as a SHA-256 hash, valid 90 days, rotated on every use. Presenting a token that was already rotated signs out that whole sign-in (it was probably copied), except within 60 seconds of the rotation, which covers an app retrying after a dropped response.
- Email is kept only when the provider marks it verified. Apple often gives a private relay address; nothing depends on email.
- `provider: "dev"` accepts any subject, for local development and tests. It is off unless `MOTIF_DEV_AUTH=1`, and the server refuses to start with it on in a Vercel production deployment.

### History sync

This implements the sync section of docs/analytics.md.

- **Upload** is a set union. The primary key is `(user_id, id)` with the device-minted UUIDv7 id, and inserts use `ON CONFLICT DO NOTHING`, so retrying a batch, or the same event arriving twice, never duplicates. The response counts `inserted` and `duplicates` (both mean "mark synced") and lists `rejected` events with a reason. A rejected event will never be accepted as sent, so the device keeps it locally and stops retrying it, instead of blocking the queue behind it.
- **Pull** pages by server arrival order (`seq`), not by event time. A phone that was offline for a week uploads old events late; a time-based cursor on the Mac would skip them, an arrival cursor does not. Uploads for one user take a per-user advisory lock, so that user's `seq` values become visible strictly in order and a cursor never jumps past a transaction that commits later. Cursors are opaque strings; the device stores the last `next_cursor` and pulls until `has_more` is false. `exclude_device` skips the caller's own events.
- **Validation** is on the envelope only: UUIDv7 id, snake_case `type`, `v ≥ 1`, sane `at_ms` and `tz_min`, a JSON object payload of at most 16 KiB. Payloads are stored as `jsonb` without interpreting them, so a new event type or payload version needs no server change. Unknown envelope fields, such as the device's local `synced` flag, are ignored.

## How the apps connect

Recommended: **a native client on each platform, written against the OpenAPI contract**, not a shared Rust client.

The API is seven small JSON endpoints. The hard parts of the client are platform parts: background scheduling, secure token storage, native sign-in and system networking. A Rust client would still need all of those from each platform, and would add a second HTTP and TLS stack next to URLSession and OkHttp.

| | Apple (MotifKit) | Android |
| --- | --- | --- |
| Sign-in | `ASAuthorizationAppleIDProvider` gives `identityToken`; Google Sign-In optional | Credential Manager with `GetGoogleIdOption` (server client id) gives a Google ID token |
| Tokens | Keychain, `kSecAttrAccessibleAfterFirstUnlock` so background sync can read them | DataStore encrypted with an Android Keystore key |
| HTTP | `URLSession` + `Codable` | OkHttp or Ktor + kotlinx.serialization |
| Background upload | `BGAppRefreshTask`, plus on launch and network change | WorkManager with a network constraint |
| Sync state | `sync_state` table in `history.db` holding the pull cursor | same |

What stays shared is the contract and the data: `schemas/api/openapi.yaml`, the event schemas in `schemas/events/`, and JSON fixtures both clients' tests decode. Generating Swift and Kotlin types from the OpenAPI file is possible later; for this many endpoints hand-written `Codable` and `@Serializable` types are smaller.

The sync loop on each device: upload unsynced rows in batches of up to 1,000 and mark acknowledged ones synced; then pull from the stored cursor until `has_more` is false, inserting pulled events into `history.db` (duplicates ignored); save the cursor. On a 401, refresh once and retry; if the refresh fails, keep everything local and show "signed out" in Settings.

## Open decisions for Pan

1. **Sign-in methods.** Recommended: Sign in with Apple on Apple platforms and Google on Android, both already supported by the server. App Store rules require Sign in with Apple whenever another social login is offered on iOS, so Google on iOS is optional extra work. The alternative is a hosted auth service (Clerk, Supabase Auth, Neon Auth), which adds a dependency and per-user pricing for little gain here.
2. **API client.** Recommended: native per platform against the OpenAPI contract, as above. Alternative: a `core/sync` Rust crate exposed through UniFFI, sharing the sync loop at the cost of a second networking stack in each app.
3. **Region.** Pick one region for the function and Neon together, near where you and your users are. `vercel.json` has no `regions` yet, so Vercel's default applies until this is set.
4. **Preview databases.** Recommended: let the Neon integration create a database branch per preview deployment, so PR previews never write to production data. Alternative: previews share the production database.
5. **Library, playlist and crate sync.** Recommended: express them as events in the same log (`track_updated`, `crate_changed` and similar are already defined in docs/analytics.md), and have each device rebuild its playlists and crates from the merged log, with the latest event winning per field. One sync mechanism, already idempotent and offline-safe. Alternative: separate state endpoints per object with version numbers, which is more code and needs conflict handling of its own.

## Not built yet

- **Account deletion** (`DELETE /v1/me`): required by App Store review for any app that creates accounts. Deletes the user and, by cascade, their events and tokens.
- **History deletion** (`DELETE /v1/events?from_ms&to_ms`, docs/analytics.md Privacy controls): needs a tombstone so other devices learn about the deletion on their next pull.
- **Rate limiting**: Vercel's firewall rules first; per-user limits in the server if needed.
- **Recap on the server**: not needed while each device holds the full log and runs `core/recap`. Because the server is Rust, it can link `core/recap` later for things like shareable recap pages.
