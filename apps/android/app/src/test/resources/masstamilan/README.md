# Fixture provenance

These HTML fixtures were extracted from the maintainer's `www.masstamilan.dev` browser HAR on 27 October 2026 (Asia/Kolkata):

- `home.html`: `GET /`
- `search.html`: `GET /search?keyword=mascara`
- `track.html`: `GET /996/mascara-pottu-mp3-song-1` (tracking query removed)

Scripts and head contents were removed. Actual signed `/downloader/…` URLs were replaced with deterministic `fixture-N` paths; they are test placeholders, not live endpoints. The track fixture includes the current recording and the site's actual embedded list of four other Salim recordings. It is not a captured standalone album response. No audio, cookies, personal network addresses, or live downloader tokens are included.

The Android resource directory carries the same HTML fixtures. Keep the copies identical when refreshing them from normal authorized website access.
