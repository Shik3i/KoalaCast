# KoalaCast Documentation (`docs`)

Specifications and design docs for KoalaCast. Start with the root
[README](../README.md) for setup; this directory is the deeper reference.

## Index

| Document | What it covers |
| :--- | :--- |
| [current-status.md](current-status.md) | Live feature matrix — what's implemented vs. planned |
| [implementation-plan.md](implementation-plan.md) | Phased implementation roadmap |
| [architecture/overview.md](architecture/overview.md) | System architecture and configuration precedence |
| [sync-protocol/specification.md](sync-protocol/specification.md) | Cross-device synchronization engine protocol |
| [feed-compatibility/rss-spec.md](feed-compatibility/rss-spec.md) | RSS/Atom parsing rules and feed aliasing |
| [privacy/privacy-policy.md](privacy/privacy-policy.md) | Privacy principles and data retention |
| [android-architecture.md](android-architecture.md) | Native Android client architecture and shipped boundaries |
| [web-app-parity.md](web-app-parity.md) | Where mobile web and the Android app differ on purpose, and what is still outstanding |

## Directory Map

```text
docs/
├── current-status.md
├── implementation-plan.md
├── android-architecture.md
├── web-app-parity.md
├── architecture/overview.md
├── sync-protocol/specification.md
├── feed-compatibility/rss-spec.md
└── privacy/privacy-policy.md
```

## Conventions

- One topic per document; link between docs rather than duplicating.
- When behavior changes in code, update the matching spec **and**
  [current-status.md](current-status.md) in the same pull request.
- Keep diagrams as fenced ```text blocks so they render everywhere.
