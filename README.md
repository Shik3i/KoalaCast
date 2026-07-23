# KoalaCast

**KoalaCast** is a completely free, open-source, privacy-first podcast player for the web, designed for calm, distraction-free listening and optional cross-device synchronization.

---

## Product Principles

1. **100% Free and Open Source**: Licensed under the [MIT License](LICENSE).
2. **No Ads, No Tracking, No Premium Tier**: Zero behavioral analytics or commercial restrictions.
3. **Local-First Capabilities**: Use the app completely without an account in Local Browser Mode.
4. **Optional Cross-Device Sync**: Accounts exist solely for synchronizing subscriptions, queue, favorites, and playback progress across browsers and future Android clients.
5. **Direct Publisher Audio**: Podcast audio is streamed directly from publishers; KoalaCast does not proxy or store audio files.
6. **RSS as Authoritative Source**: Preserves standard RSS feeds and Podcasting 2.0 tags.
7. **Parity in Self-Hosting**: Self-hosted instances have the exact same capabilities as official instances.

---

## Project Status

KoalaCast is currently in **Phase 1 / Phase 2 early development**.
- Core Go backend foundation, SQLite database engine, SSRF-safe network transport, RSS/Atom parser, and SvelteKit web app scaffold are **implemented and verified**.
- Authentication, cross-device sync engine, and full player UI controls are fully **specified** in documentation and scheduled for implementation in upcoming phases.

For a detailed feature breakdown, see [docs/current-status.md](docs/current-status.md).

---

## Architecture Overview

```text
SvelteKit Web Client ──────┐
                           ├── Go REST API (chi) ── SQLite (WAL mode)
Future Native Android Client─┘        │
                                      ├── RSS Feed Fetcher & Worker Pool
                                      └── Podcast Search Provider (Podcast Index)

Web / Native Audio Player ──────────────────────► Direct Podcast Audio Stream (Publisher CDN)
```

For detailed architectural specs, sync protocol logic, and privacy rules, see the [docs/](docs/) directory:
- [Architecture & Config Precedence](docs/architecture/overview.md)
- [Privacy Policy & Data Retention](docs/privacy/privacy-policy.md)
- [Synchronization Engine Protocol](docs/sync-protocol/specification.md)
- [Feed Compatibility & Parsing](docs/feed-compatibility/rss-spec.md)
- [Future Android Architecture Plan](docs/android-architecture.md)

---

## Getting Started (Self-Hosting with Docker)

```bash
# 1. Clone the repository
git clone git@github.com:Shik3i/KoalaCast.git
cd KoalaCast

# 2. Configure environment
cp .env.example .env

# 3. Launch single-command deployment with Caddy same-origin reverse proxy
docker compose up -d
```

Access the player at `http://localhost:8080`.

---

## Development Setup

### Requirements
- **Go**: 1.22 or higher
- **Node.js**: 20 or higher
- **Docker**: 24+ with Compose

### Running Backend Service
```bash
cd services/api
go run ./cmd/server
```

### Running Web Application
```bash
cd apps/web
npm install
npm run dev
```

---

## License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE) for full details.
