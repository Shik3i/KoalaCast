# Future Native Android Architecture Specification

While the initial MVP focuses on the SvelteKit web application and Go REST API, KoalaCast is designed from inception to support a full-featured native Android client.

## Architectural Principles

1. **Language & UI**: Kotlin + Jetpack Compose.
2. **Audio Playback Engine**: AndroidX Media3 (`ExoPlayer` + `MediaLibraryService`).
3. **Local Database**: Room persistence library storing subscriptions, episode metadata, queue, and playback state in millisecond precision (`int64` / `Long`).
4. **Background Operations**: `WorkManager` for periodic feed syncing, queue management, and offline download management.
5. **Network & Sync**: Ktor or Retrofit client communicating directly with the `/api/v1/sync` endpoint using revocable device tokens (`device_credentials` table).
6. **Direct Publisher Audio Streaming**: Streams episode audio directly from original publisher enclosure URLs to ExoPlayer without passing audio binary through KoalaCast servers.

## Authentication & Token Flow for Android

Unlike the browser web player (which uses same-origin HttpOnly session cookies), native Android clients authenticate via scoped device tokens:
1. Android app registers device name and client type (`android`) via `POST /api/v1/auth/device/login`.
2. Server returns a revocable `device_token` associated with a record in `device_credentials`.
3. Requests to `/api/v1/sync` include `Authorization: Bearer <device_token>`.
4. Users can inspect, rename, or revoke active Android device tokens at any time via the web settings interface.
