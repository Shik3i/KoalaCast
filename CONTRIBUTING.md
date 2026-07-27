# Contributing to KoalaCast

Thanks for your interest in improving KoalaCast! This guide covers everything you need to land a change.

By participating, you agree to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Ways to Contribute

- **Report bugs** — open an issue with reproduction steps, expected vs. actual behavior, and environment details.
- **Suggest features** — open an issue describing the problem you're solving (not just the solution).
- **Improve docs** — typo fixes to whole guides are all welcome.
- **Translate the app** — add or improve a language. It is one JSON file and one
  line of registration, no build tooling required — see [docs/i18n.md](docs/i18n.md).
  Partial translations are welcome; untranslated strings fall back to English.
- **Submit code** — bug fixes and features via pull request (see below).

---

## Development Setup

Requirements: **Go 1.25+**, **Node.js 24+**, and optionally **Docker 24+**.

```bash
git clone https://github.com/Shik3i/KoalaCast.git
cd KoalaCast

# Backend on :3000
cd services/api && SESSION_SECRET=dev-secret-with-at-least-32-characters go run ./cmd/server

# Frontend on :5173 (Vite proxies /api to :3000)
cd apps/web && npm install && npm run dev
```

See the root [README](README.md#development) and each directory's README for details.

---

## Pull Request Workflow

1. **Fork** the repository and create a topic branch from `main`:
   `git checkout -b fix/feed-parser-timezone`
2. **Make focused changes** — one logical change per PR. Keep diffs small and reviewable.
3. **Add or update tests** for any behavior change.
4. **Run the full check suite locally** (see below) — it must pass.
5. **Write a clear PR description**: what changed, why, and how you verified it. Link related issues.
6. **Keep the branch up to date** with `main` and resolve conflicts before requesting review.

CI (`.github/workflows/ci.yml`) runs on every PR and must be green before merge.

---

## Local Checks (must pass before pushing)

```bash
# Backend
cd services/api
gofmt -l .            # must print nothing
go vet ./...
go test -race ./...

# Frontend
cd apps/web
npm test              # unit tests (i18n catalogues & runtime)
npm run check         # svelte-check type verification
npm run check:docs    # Markdown links and current-state assertions
npm run check:i18n    # translation catalogues must be structurally valid
npm run check:release-policy # GitHub Releases stay Android-only
npm run check:seo     # sitemap, robots, llms and social metadata
npm run build         # production build must succeed
```

Shortcut: `make test` runs Go race tests, web unit tests, types, documentation,
translation, and SEO checks from the repo root.

---

## Translations

Add a language by copying `apps/web/src/lib/i18n/messages/en.json`, translating
the values, and registering it in `apps/web/src/lib/i18n/registry.ts`. Full
walkthrough: [docs/i18n.md](docs/i18n.md).

Two rules worth knowing before you start:

- **Keep every `{placeholder}` exactly as it appears.** `npm test` and
  `npm run check:i18n` fail the build if one is missing, misspelled, or invented.
- **Every key must be present and non-empty.** The test suite runs over every
  registered language and rejects blanks, `TODO` markers, stray whitespace,
  unknown keys, and plural forms your language never uses — so a new language is
  held to the same standard automatically.
- **Legal text is English-only.** The privacy policy and links naming legal
  documents are deliberately not translated — a mistranslated clause is a legal
  problem, not a cosmetic one.

---

## Coding Standards

### Go (`services/api`)
- Format with `gofmt` (CI enforces it). Prefer standard-library idioms.
- Keep handlers thin; put reusable logic in `internal/` packages.
- Return errors with context (`fmt.Errorf("...: %w", err)`); never panic in request paths.
- All outbound feed fetches **must** use the SSRF-safe client in `internal/rss`.
- Add table-driven tests next to the code they cover.

### TypeScript / Svelte (`apps/web`)
- Svelte 5 runes (`$state`, `$derived`, `$effect`). Keep components small and typed.
- No new runtime dependencies without discussion.
- `npm run check` must report **0 errors**.

### Releases

- `v*` tags publish Docker images to GHCR. They must never create a GitHub
  Release; keep those tags for image provenance and rollback.
- `android-v*` tags are the only tags allowed to create a GitHub Release, and
  every such release must contain an APK or AAB.
- Run `npm run check:release-policy` after changing any workflow.

---

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`, `perf`.

Examples:
- `fix(api): resolve iTunes collection IDs to feeds on demand`
- `feat(web): add playback speed persistence`
- `docs(readme): document the same-origin proxy setup`

---

## API Contract Changes

If you change REST endpoints, update the OpenAPI spec in
[`packages/openapi/openapi.yaml`](packages/openapi/openapi.yaml) in the same PR.
CI lints the spec with Redocly.

---

## Reporting Security Issues

Do **not** open public issues for vulnerabilities. Follow [SECURITY.md](SECURITY.md).

---

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
