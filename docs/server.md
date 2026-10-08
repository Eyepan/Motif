# Server and sync

Status: first version in `server/`. Covers what the server does, how it runs on Vercel, how the apps talk to it, and the decisions made so far.

## What the server is for

Motif stays local-first. Playback, the library, analysis and DJ mixing run on the device and work with no network and no account. The server adds the things a single device cannot do on its own:

- **Accounts**: a username and password, so a user's devices can find each other.
- **The listening history log**: every device uploads its events and pulls the others', so each device ends up holding the full merged log (docs/analytics.md). Recaps are computed from that log.
- **Library metadata, playlists and crates**: the same log, see Decisions. Crates already write `crate_changed` events (docs/crates.md); library edits and playlists are next.

It does not store or stream audio. Files stay on each device. Syncing audio through user-owned storage such as S3 is a later step and does not change anything here.

## Stack

| Piece | Choice | Why |
| --- | --- | --- |
| Language | Rust, axum 0.8, tokio | Same language as `core/`; future `core/recap` code can run on the server unchanged |
| Database | Postgres via sqlx (runtime queries, no compile-time DB needed) | Relational data plus `jsonb` payloads; Neon on Vercel |
| Hosting | One Vercel Function using the Rust runtime (`vercel_runtime` 2 with its axum adapter) | Push-to-deploy, preview URL per PR, no servers to run |
| Migrations | `sqlx` migrations embedded in the binary | Applied by each function instance on cold start, and by `motif-server migrate` elsewhere |
| Contract | `schemas/api/openapi.yaml` | Shared by the Apple and Android clients like the other schemas |

The same axum router is built into two binaries: `api/motif.rs` (the Vercel Function) and `motif-server` (a plain HTTP server for local development, tests, and hosting anywhere else). Nothing in the handlers knows about Vercel, so moving to Fly, Railway, a container or a home server later is a build-target change, not a rewrite.

## Running on Vercel

- **One function for the whole API.** `vercel.json` rewrites every path to `api/motif`, and axum routes inside it. One function means one cold start and one connection pool instead of one per endpoint. If the platform hands the function the rewritten path instead of the original, a small layer strips the `/api/motif` prefix, so both work; the first preview deploy will show which one Vercel does.
- **Fluid compute.** The Rust runtime runs the binary as a long-lived HTTP server that handles many requests concurrently per instance. The database pool and the cached Apple and Google signing keys are shared across those requests.
- **Limits that shape the API.** Request bodies are capped at 4.5 MB, so uploads are limited to 1,000 events and 4 MB per batch. Function duration stays at the platform default; no request should come close. (Vercel rejects a `functions` entry for a `.rs` file, so per-function settings are not available for the Rust runtime.) There are no long-lived connections, so sync is pull-based (on launch, on foreground, after an upload), not a live socket.
- **Cold starts.** The pool connects lazily, so a cold instance answers `/health` and token checks without waiting on the database.
- **Build.** Vercel builds the release binary on each deploy (thin LTO). CI builds the same binary on every PR so a broken build never reaches Vercel.

### Postgres and connection pooling

Recommended: **Neon from the Vercel Marketplace**. Connecting it sets `DATABASE_URL` (the pooled `-pooler` endpoint, PgBouncer in transaction mode) and `DATABASE_URL_UNPOOLED` (direct) on the project.

- The API uses the pooled URL with a small per-instance pool (`MOTIF_DB_MAX_CONNECTIONS`, default 5). Many function instances then share Neon's pooler instead of each holding direct Postgres connections.
- Migrations use the direct URL, because they hold a session-level advisory lock that a transaction-mode pooler does not keep.
- If the pooler ever rejects prepared statements, `MOTIF_DB_STATEMENT_CACHE=0` turns off sqlx's statement cache without a code change.
- The function region (`regions` in `vercel.json`) and the Neon region sit next to each other, because every request does at least one round trip between them. Both are in Singapore: Neon has no India region, and Singapore is the closest to Chennai that both offer.

Supabase and other Postgres hosts work the same way: anything with a pooled and a direct connection string.

### Deploying

The project owner does this once (steps in `server/README.md`): import the repo in Vercel with Root Directory `server`, connect Neon, and set `MOTIF_JWT_SECRET`. After that, merging to `main` deploys production and every PR gets a preview. Each function instance applies pending migrations when it starts, so a new deployment, or a fresh Neon preview branch, never serves against an old schema. Concurrent cold starts wait on the migrator's advisory lock; when nothing is pending the check is a single query. `MOTIF_MIGRATE_ON_START=0` turns it off.

During a rollout, instances of the previous deployment keep serving after the new one has migrated, so for a short time old code runs on the new schema and possibly new code on the old one. Migrations therefore stay additive: add a column or table in one release, start using it, and remove the old one in a later release.

## API

Full contract: `schemas/api/openapi.yaml`. All bodies are JSON; errors are `{"error": {"code", "message"}}`.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness plus a database round trip and the serving region; 503 when the database is down |
| `POST /v1/auth/register` | Create an account with a username and password; returns a session |
| `POST /v1/auth/login` | Sign in with a username and password |
| `POST /v1/auth/password` | Change the password; signs out every other device |
| `POST /v1/auth/token` | Exchange an Apple or Google ID token for a session; off unless configured |
| `POST /v1/auth/refresh` | Rotate the refresh token |
| `POST /v1/auth/logout` | Revoke this device's session |
| `GET /v1/auth/username?username=` | Sign-up form check: is the username valid and free |
| `GET /v1/me` | Account details: username, display name, member since, linked sign-in methods |
| `PATCH /v1/me` | Change the username or display name |
| `DELETE /v1/me` | Delete the account and its synced history; needs the password |
| `GET /v1/sessions` | Devices signed in to the account, with "this device" marked |
| `DELETE /v1/sessions/{id}` | Sign one device out |
| `DELETE /v1/sessions` | Sign out every other device |
| `GET /v1/history?before=&limit=&types=` | Listening history newest first, for the history screen |
| `GET /v1/history/summary` | What the server holds: plays, listening time, devices, storage used |
| `DELETE /v1/history` | Delete listening history in a time range, or all of it |
| `POST /v1/events` | Upload a batch of history events, idempotent on event id |
| `GET /v1/events?after=&limit=&exclude_device=` | Pull events in server arrival order |

### Accounts and tokens

Accounts are a username and a password. Nothing costs money and nothing depends on Apple or Google.

- **Usernames** are 3 to 32 characters of letters, digits, `.`, `_` and `-`, case-insensitive (stored lowercased).
- **Passwords** are at least 8 characters, hashed with Argon2id (19 MiB, 2 passes, the OWASP baseline) on a blocking thread so hashing never stalls other requests. Only the hash is stored.
- **Login** answers "invalid username or password" for both an unknown username and a wrong password, and spends the same hashing time on both, so it does not reveal which usernames exist. Ten wrong passwords in a row lock that username for 15 minutes.
- **No email, so no reset.** A forgotten password cannot be recovered. Nothing is lost: the history stays on each device and uploads again to a new account. Changing the password (while signed in) signs out every other device.
- **Account details**: a changeable username and an optional display name (up to 64 characters, any script). Member since comes from the account's creation time.
- **Deleting the account** needs the password, then deletes the user and, by cascade, their synced history, sign-ins and devices. Nothing on the devices is touched; the app goes back to working signed out. App Store review requires this for any app that creates accounts.
- **Settings that sync** across devices travel as events in the history log, like crates (decision 3 below), so there is no settings endpoint. Device-only settings (audio output, cache size) stay on the device.
- **Wrong password while signed in** (changing the password, deleting the account) is 403, not 401, so a client never mistakes it for an expired token and refreshes.
- **Apple and Google sign-in** are still in the server (`POST /v1/auth/token`, verifying ID tokens against the providers' published keys), switched off until `MOTIF_APPLE_AUDIENCES` or `MOTIF_GOOGLE_CLIENT_IDS` is set. They are free to use if wanted later. Linking them to an existing username account is not built yet; today each would create its own account.

Every sign-in method ends in the same session:

- **Access token**: an HS256 JWT signed with `MOTIF_JWT_SECRET`, valid 15 minutes, checked without a database lookup.
- **Refresh token**: 256 random bits, stored only as a SHA-256 hash, valid 90 days, rotated on every use. Presenting a token that was already rotated signs out that whole sign-in (it was probably copied), except within 60 seconds of the rotation, which covers an app retrying after a dropped response.
- **Signed-in devices**: every sign-in (one refresh token family) may carry `device: {name, platform}`, stored in `sessions` and listed by `GET /v1/sessions`. The access token carries the sign-in's id (`sid`), so the list marks the caller's own device and "sign out every other device" keeps it. Signing a device out revokes its refresh token at once; its access token still works for up to 15 minutes, which is the price of checking access tokens without a database lookup. Changing the password keeps the device's name on its new sign-in.
- `provider: "dev"` accepts any subject, for local development and tests. It is off unless `MOTIF_DEV_AUTH=1`, and the server refuses to start with it on in a Vercel production deployment.

### History sync

This implements the sync section of docs/analytics.md.

- **Upload** is a set union. The primary key is `(user_id, id)` with the device-minted UUIDv7 id, and inserts use `ON CONFLICT DO NOTHING`, so retrying a batch, or the same event arriving twice, never duplicates. The response counts `inserted` and `duplicates` (both mean "mark synced") and lists `rejected` events with a reason. A rejected event will never be accepted as sent, so the device keeps it locally and stops retrying it, instead of blocking the queue behind it.
- **Pull** pages by server arrival order (`seq`), not by event time. A phone that was offline for a week uploads old events late; a time-based cursor on the Mac would skip them, an arrival cursor does not. Uploads for one user take a per-user advisory lock, so that user's `seq` values become visible strictly in order and a cursor never jumps past a transaction that commits later. Cursors are opaque strings; the device stores the last `next_cursor` and pulls until `has_more` is false. `exclude_device` skips the caller's own events.
- **Validation** is on the envelope only: UUIDv7 id, snake_case `type`, `v ≥ 1`, sane `at_ms` and `tz_min`, a JSON object payload of at most 16 KiB. Payloads are stored as `jsonb` without interpreting them, so a new event type or payload version needs no server change. Unknown envelope fields, such as the device's local `synced` flag, are ignored.

### Listening history screen

Each device holds the whole merged log once it has pulled it, so the history screen normally reads the local `history.db`. The server adds three things the device cannot do alone:

- **Newest-first reading** (`GET /v1/history`), ordered by when listening happened, so a newly signed-in device shows history before its pull finishes. Defaults to plays; `types` adds transitions, app sessions or searches.
- **A summary** (`GET /v1/history/summary`) of what is synced: plays, total listening time, first and last listen, storage used, and each device's event count and last upload. Together with `/health` (status, version, region) this fills the server details screen.
- **Deleting history** (`DELETE /v1/history`, docs/analytics.md Privacy controls). It removes `play`, `transition`, `app_session` and `search` events in `[from_ms, to_ms)`, or all of them. Library events (tracks, likes, crates) are state rather than history and stay, so deleting a month of plays does not undo that month's crate edits. The end of the range is clamped to now. The server records the range and appends a `history_deleted` event (`schemas/events/history_deleted.v1.schema.json`); other devices pull it and delete their local copies, unsynced ones included. A device that was offline and uploads deleted events later gets them acknowledged as duplicates and dropped, so deleted history never comes back. Clients cannot upload `history_deleted` themselves.

## How the apps connect

Recommended: **a native client on each platform, written against the OpenAPI contract**, not a shared Rust client.

The API is about fifteen small JSON endpoints. The hard parts of the client are platform parts: background scheduling, secure token storage, native sign-in and system networking. A Rust client would still need all of those from each platform, and would add a second HTTP and TLS stack next to URLSession and OkHttp.

| | Apple (MotifKit) | Android |
| --- | --- | --- |
| Sign-in | Username and password form; offer to save it with Password AutoFill | Username and password form; Credential Manager saves the password |
| Tokens | Keychain, `kSecAttrAccessibleAfterFirstUnlock` so background sync can read them | DataStore encrypted with an Android Keystore key |
| HTTP | `URLSession` + `Codable` | OkHttp or Ktor + kotlinx.serialization |
| Background upload | `BGAppRefreshTask`, plus on launch and network change | WorkManager with a network constraint |
| Sync state | `sync_state` table in `history.db` holding the pull cursor | same |

What stays shared is the contract and the data: `schemas/api/openapi.yaml`, the event schemas in `schemas/events/`, and JSON fixtures both clients' tests decode. Generating Swift and Kotlin types from the OpenAPI file is possible later; for this many endpoints hand-written `Codable` and `@Serializable` types are smaller.

The sync loop on each device: upload unsynced rows in batches of up to 1,000 and mark acknowledged ones synced; then pull from the stored cursor until `has_more` is false, inserting pulled events into `history.db` (duplicates ignored); save the cursor. On a 401, refresh once and retry; if the refresh fails, keep everything local and show "signed out" in Settings.

## Decisions (Pan, 2026-10-07)

1. **Sign-in is a username and password.** No paid or third-party auth service. Apple and Google stay available in the server, switched off.
2. **Native clients.** Each app talks to the API with its own small client against `schemas/api/openapi.yaml`.
3. **Everything syncs through the history log.** Library edits, playlists and crates become events in the same log (`track_updated`, `crate_changed` and the like in docs/analytics.md). Each device rebuilds playlists and crates from the merged log, with the latest event winning per field. The server needs no new endpoints for them.
4. **Preview databases are optional and free at this size.** See Cost.
5. **Region: Singapore** for both the function (`sin1`) and Neon (`aws-ap-southeast-1`).

## Cost

Everything fits in free plans for a personal project:

- **Vercel Hobby** is free for non-commercial use and runs the Rust function. A commercial launch would need Pro.
- **Neon Free** includes 10 branches, 100 compute-hours a month and 0.5 GB of storage per project. Databases scale to zero after 5 idle minutes, so an idle branch uses no compute. A heavy listener produces about 10 MB of history a year (docs/analytics.md), so 0.5 GB lasts a long time for a few users.
- **Preview branches** (one database branch per PR preview) count toward the 10 branches and share the compute-hours. With the integration set to delete a branch when its preview goes away, a few open PRs at a time stays free. Branches beyond 10 cost $1.50 a month each on paid plans. Turning previews off entirely also works; previews then share the production database.

## Not built yet

- **Rate limiting**: Vercel's firewall rules first; per-user limits in the server if needed.
- **Recap on the server**: not needed while each device holds the full log and runs `core/recap`. Because the server is Rust, it can link `core/recap` later for things like shareable recap pages.
