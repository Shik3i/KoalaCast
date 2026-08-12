# Native Android Architecture

KoalaCast ships a native Kotlin/Compose Android client in `apps/android`.
P0–P7 are implemented. The detailed feature checklist and remaining test and
parity work live in [`apps/android/README.md`](../apps/android/README.md).

## Architectural Principles

1. **Language & UI**: Kotlin + Jetpack Compose.
2. **Audio Playback Engine**: AndroidX Media3 (`ExoPlayer` + `MediaSessionService`).
3. **Local Database**: Room persistence library storing subscriptions, episode metadata, queue, and playback state in millisecond precision (`int64` / `Long`).
4. **Background Operations**: `WorkManager` for resumable offline downloads and constraints.
5. **Network & Sync**: Retrofit/OkHttp client communicating with `/api/v1/*` using revocable device tokens (`device_credentials` table).
6. **Direct Publisher Audio Streaming**: Streams episode audio directly from original publisher enclosure URLs to ExoPlayer without passing audio binary through KoalaCast servers.

## Authentication & Token Flow for Android

Unlike the browser web player (which uses same-origin HttpOnly session cookies), native Android clients authenticate via scoped device tokens:
1. Android app registers device name and client type (`android`) via `POST /api/v1/auth/device/login`.
2. Server returns a revocable `device_token` associated with a record in `device_credentials`.
3. Requests to `/api/v1/sync` (and any authed endpoint) include `Authorization: Bearer <device_token>`.
4. Users can **inspect and revoke** active device tokens: `GET /api/v1/auth/sessions` lists both web
   sessions and device credentials (each carries a `kind` of `"session"` or `"device"`), and
   `DELETE /api/v1/auth/sessions/{id}` revokes either kind. `POST /api/v1/auth/logout` from a native
   client revokes that device's own token. (Renaming a device is not yet exposed via the API.)
