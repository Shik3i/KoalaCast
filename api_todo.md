# API TODO for Android completeness

This file is the hand-off contract for the agent working on the Go API and web
client. Paths are relative to `/api/v1`. Keep the Android client local-first:
none of these endpoints may make an account mandatory for browsing or playback.

## P0 — make incremental sync lossless

### Problem

`GET /sync` reads at most 500 rows but currently returns the account-wide
`current_cursor`. A client that stores that cursor skips every change after row
500. The existing `410 FULL_RESYNC_REQUIRED` response also has no endpoint from
which a client can obtain the required snapshot.

### Required contract

`GET /sync?since_cursor={cursor}&limit={1..500}`

```json
{
  "since_cursor": 1200,
  "next_cursor": 1700,
  "current_cursor": 1942,
  "has_more": true,
  "changesets": []
}
```

- `next_cursor`: cursor of the last returned changeset, or `since_cursor` when
  the page is empty.
- `current_cursor`: current account head; informational only.
- `has_more`: `next_cursor < current_cursor`.
- Clients continue with `since_cursor=next_cursor` until `has_more=false`.
- Never tell a client to persist a cursor beyond the last returned row.

Add `GET /sync/snapshot` (Bearer/cookie auth):

```json
{
  "cursor": 1942,
  "subscriptions": [],
  "favorites": [],
  "queue": [],
  "playback_states": [],
  "listening_sessions": [],
  "podcast_settings": []
}
```

- Complete current non-deleted state at one transactionally consistent cursor.
- Stable queue ordering and stable entity IDs.
- A client receiving `410 FULL_RESYNC_REQUIRED` replaces synced state from this
  snapshot, stores `cursor`, then resumes incremental pulls.
- Paginate large listening-session collections if necessary, but use one frozen
  snapshot cursor for every page.

### Acceptance tests

- Push 501 operations, pull with `limit=500`, then pull from `next_cursor`;
  every operation appears exactly once.
- Empty page: `next_cursor == since_cursor`.
- Compacted cursor returns `410`; `/sync/snapshot` reconstructs the same
  materialized state as a fresh replay of the retained log.
- Update `packages/openapi/openapi.yaml`.

## P0 — persist and expose Podcasting 2.0 chapters

### Problem

`rss.Episode.ChaptersURL` is parsed, but `ingestFeedURL` discards it. The generic
`GET /proxy/chapters?url=...` exists, yet neither web nor Android can discover a
chapter source from `GET /episodes/{id}`.

### Required storage and episode response

- Add `episodes.chapters_url TEXT NOT NULL DEFAULT ''` in a numbered migration.
- Persist it during feed ingestion and refresh.
- Do not use `INSERT OR IGNORE` as the final refresh behavior: update mutable
  episode metadata, including `chapters_url` and `transcripts`, when the stable
  episode identity already exists.
- Add `chapters_available: boolean` to both episode-list and episode-detail DTOs.
  Do not expose a publisher URL when an episode-bound endpoint can fetch it.

Add `GET /episodes/{id}/chapters`:

```json
{
  "chapters": [
    {
      "start_time_ms": 0,
      "title": "Intro",
      "image_url": "",
      "url": ""
    }
  ]
}
```

- Fetch only the URL already stored for this episode; never accept a URL query.
- Reuse the existing SSRF-safe transport, redirect checks, 2 MiB cap, timeouts,
  rate limit, and cache.
- Normalize Podcasting 2.0 `startTime` seconds to integer `start_time_ms`.
- Sort ascending; discard non-finite/negative timestamps; trim titles; return
  `404` if the episode or its chapter source does not exist and `502` for an
  upstream failure.

### Acceptance tests

- Wrapped `{ "chapters": [...] }` and raw arrays.
- Fractional seconds convert exactly to milliseconds.
- Loopback/private-network targets and redirects remain blocked.
- A feed refresh can add or replace chapters/transcripts on an existing episode.
- Update `packages/openapi/openapi.yaml`.

## P1 — batch the subscription inbox

### Problem

Web and Android currently issue one
`GET /podcasts/{id}/episodes?limit=...` request per subscription. Android caps
this at six concurrent feeds and 15 episodes per feed. Large libraries are slow,
waste radio/battery, and can silently omit older unplayed items.

Add unauthenticated `POST /episodes/inbox`:

```json
{
  "podcasts": [
    { "podcast_id": "uuid", "limit": 50 }
  ],
  "published_after": 0,
  "limit": 500,
  "cursor": ""
}
```

```json
{
  "episodes": [],
  "podcasts_not_found": [],
  "next_cursor": ""
}
```

- Maximum 200 podcast IDs and 500 returned episodes.
- Deduplicate podcast IDs; stable global order:
  `pub_date DESC, episode_id ASC`.
- Cursor-based pagination; never offset pagination over a changing feed.
- No account requirement: the request contains the local subscription IDs.
- Return normal `EpisodeResponse` fields plus enough podcast metadata to build a
  track without N additional `GET /podcasts/{id}` calls.
- This endpoint does not apply played/unplayed or per-show `inbox_mode`; those
  remain local/synced user state.

### Acceptance tests

- Mixed known/unknown IDs, duplicate IDs, undated episodes, deterministic ties.
- Limit enforcement and invalid cursor.
- One response is behaviorally equivalent to merging the individual episode
  lists.
- Update `packages/openapi/openapi.yaml`.

## P1 — sync complete per-podcast settings

The database already has `per_podcast_settings`, and sync comments already name
`settings`, but `SyncHandler.Push` does not materialize this entity type.

Support `entity_type: "podcast_settings"` with payload:

```json
{
  "podcast_id": "uuid",
  "skip_intro_seconds": 0,
  "skip_outro_seconds": 0,
  "playback_speed": null,
  "auto_queue_new": false,
  "inbox_mode": "all"
}
```

- Clamp skips to `0..600`.
- `playback_speed`: `null` or `0.5..3.0`.
- `inbox_mode`: `all | latest`.
- Last explicit write wins by `(client_timestamp, server_timestamp)`.
- Include the entity in `/sync/snapshot`.
- Keep local downloads and device storage settings out of sync.
- Update the web client and Android DTO contract together after the backend
  lands.

### Acceptance tests

- Cross-device upsert and reset-to-default.
- Invalid values rejected with `400`; do not append invalid operations to
  `sync_log`.
- Snapshot and incremental replay yield identical settings.

## P1 — expose instance-owned legal metadata

### Problem

The Android privacy screen can only link to the official KoalaCast operator.
That is wrong when the user selects a self-hosted instance with a different
operator or privacy policy.

Add unauthenticated `GET /instance`:

```json
{
  "service_name": "KoalaCast",
  "operator_name": "Example e.V.",
  "privacy_policy_url": "https://cast.example.org/privacy",
  "legal_notice_url": "https://cast.example.org/legal",
  "registration_enabled": true
}
```

- Values come from server configuration, not request headers.
- URLs must be absolute HTTPS URLs, except HTTP on loopback in development.
- Empty `legal_notice_url` is allowed; privacy policy and operator are required.
- Android should show this metadata before registration and use the URLs in
  Settings instead of hard-coded official-instance text.
- Update `packages/openapi/openapi.yaml`.

### Acceptance tests

- Official defaults and custom self-host configuration.
- Invalid or relative configured URLs fail startup validation.
- Response is available without authentication and contains no deployment
  secrets.

## P2 — account data control

Add authenticated endpoints needed for a complete in-app privacy/account screen:

- `GET /auth/export`: downloadable JSON containing the user's account metadata
  and all synchronized records; never include password hashes, recovery-code
  hashes, session secrets, or device-token hashes.
- `DELETE /auth/account`: require password or recovery code in the request body,
  delete the account and all dependent rows transactionally, and revoke every
  session/device token.

These are destructive/security-sensitive endpoints: rate-limit them, require
fresh credentials, audit only the event (not supplied credentials), and add
tests proving a different user cannot export/delete the account.

## Existing endpoints Android already uses — do not duplicate

- Discovery/search/feed ingestion and podcast/episode reads.
- Episode transcript: `GET /episodes/{id}/transcript?i=0`.
- Device login, registration, recovery, auth status/logout, session listing and
  revocation.
- Sync pull/push/merge.
- OPML import; Android intentionally generates local OPML export itself.
- Global-statistics preference and public aggregates.
- Image proxy. Audio must continue to stream/download directly from publishers.
