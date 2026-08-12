# OpenAPI Contract (`packages/openapi`)

The authoritative machine-readable contract for the KoalaCast REST API.

```text
packages/openapi/
└── openapi.yaml     OpenAPI 3 specification for /api/v1
```

## Purpose

`openapi.yaml` is the single source of truth for endpoint shapes, request/response
schemas, and status codes served by [`services/api`](../../services/api). Use it to:

- Generate typed clients for third-party or native (Android) apps.
- Validate request/response payloads.
- Render human-friendly API docs.

## Keeping It in Sync

> **Any change to the REST API must update `openapi.yaml` in the same pull request.**

The path-filtered CI suite lints the spec whenever OpenAPI, API, web, container
or workflow inputs change, using
[Redocly CLI](https://redocly.com/docs/cli/) (see
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)).

## Working With It Locally

```bash
# Lint (same check CI runs)
npx -y @redocly/cli lint packages/openapi/openapi.yaml

# Preview interactive docs
npx -y @redocly/cli preview-docs packages/openapi/openapi.yaml
```
