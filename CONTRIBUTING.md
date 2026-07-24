# Contributing to KoalaCast

Thanks for your interest in improving KoalaCast! This guide covers everything you need to land a change.

By participating, you agree to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Ways to Contribute

- **Report bugs** — open an issue with reproduction steps, expected vs. actual behavior, and environment details.
- **Suggest features** — open an issue describing the problem you're solving (not just the solution).
- **Improve docs** — typo fixes to whole guides are all welcome.
- **Submit code** — bug fixes and features via pull request (see below).

---

## Development Setup

Requirements: **Go 1.25+**, **Node.js 20+**, and optionally **Docker 24+**.

```bash
git clone https://github.com/Shik3i/KoalaCast.git
cd KoalaCast

# Backend on :8080
cd services/api && SESSION_SECRET=dev-secret-with-at-least-32-characters go run ./cmd/server

# Frontend on :5173 (Vite proxies /api to :8080)
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
npm run check         # svelte-check type verification
npm run build         # production build must succeed
```

Shortcut: `make test` runs Go race tests + `svelte-check` from the repo root.

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
