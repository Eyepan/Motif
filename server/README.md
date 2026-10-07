# Motif server

The sync service: accounts, and the listening history log merged across a user's devices. Rust (axum) on Postgres, deployed as one Vercel Function. The apps work fully offline without it. Design and open decisions: [docs/server.md](../docs/server.md). API contract: [schemas/api/openapi.yaml](../schemas/api/openapi.yaml).

## Run locally

Needs Rust and a Postgres 14+.

```sh
cd server
createdb motif                      # or: docker run -e POSTGRES_PASSWORD=motif -p 5432:5432 postgres:16
cp .env.example .env.local          # fill in MOTIF_JWT_SECRET
set -a; . ./.env.local; set +a
cargo run --bin motif-server migrate
cargo run --bin motif-server        # http://localhost:8080
```

```sh
curl localhost:8080/health
curl -X POST localhost:8080/v1/auth/register -H 'content-type: application/json' \
  -d '{"username":"pan","password":"correct horse"}'
```

## Tests

```sh
cargo test                                          # unit tests
TEST_DATABASE_URL=postgres://... cargo test         # plus end-to-end tests against Postgres
```

`tests/fixtures/test-rsa.pem` is a throwaway key that signs fake Apple tokens in tests. It is not used anywhere else.

## Deploy to Vercel

One-time setup, done by the project owner:

1. Import the GitHub repo in Vercel and set **Root Directory** to `server`. Vercel detects the Rust runtime from `Cargo.toml` and `api/motif.rs`.
2. Add **Neon** (Free plan) from the Vercel Marketplace (Storage tab), choose region **AWS Asia Pacific (Singapore)**, and connect it to the project. It sets `DATABASE_URL` and `DATABASE_URL_UNPOOLED`. The function runs in Vercel's Singapore region (`sin1` in `vercel.json`), next to the database. Optionally turn on the integration's preview branches, with automatic deletion of obsolete branches.
3. Set `MOTIF_JWT_SECRET`. Leave `MOTIF_DEV_AUTH` unset (the server refuses to start with it on in production). `MOTIF_APPLE_AUDIENCES` and `MOTIF_GOOGLE_CLIENT_IDS` stay empty unless Apple or Google sign-in is added later.
4. Add `DATABASE_URL_UNPOOLED` as a GitHub Actions secret named `MOTIF_DATABASE_URL` so the **Server migrations** workflow can migrate on merge.

Every push to `main` then deploys production and every PR gets a preview URL. The same binary also runs anywhere else: `cargo build --release --bin motif-server` and run it with the same environment.
