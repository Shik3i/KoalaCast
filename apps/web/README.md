# KoalaCast Web (`apps/web`)

The KoalaCast web client — a SvelteKit 5 single-page app with a local-first data
layer (IndexedDB) and an optional account-backed sync mode.

- **Framework:** SvelteKit + Svelte 5 (runes)
- **Adapter:** `@sveltejs/adapter-node` (runs as a Node server in production)
- **Language:** TypeScript
- **Bundler:** Vite

---

## Layout

```text
apps/web/
├── src/
│   ├── app.html                 HTML shell (fonts, favicon, anti-FOUC theme script)
│   ├── lib/
│   │   ├── components/          Player, Footer, …
│   │   ├── data/                Static fallback data (featured podcasts)
│   │   ├── idb/                 IndexedDB engine (local subscriptions/queue/progress)
│   │   ├── styles/              Global CSS + Forest Green design tokens
│   │   └── theme.ts             Light/dark theme handling
│   └── routes/                  Pages: /, /search, /library, /podcast/[id], /episode/[id], /settings, /admin
├── static/                      favicon.svg, placeholder.svg (served at site root)
├── svelte.config.js             adapter-node config
└── vite.config.ts               Dev server + /api → :8080 proxy
```

---

## Development

```bash
npm install
npm run dev        # http://localhost:5173
```

In dev, Vite proxies `/api`, `/healthz`, and `/readyz` to the Go API on
`http://localhost:8080` (see [`vite.config.ts`](vite.config.ts)), so run the
backend alongside it (`make dev-api`).

### Scripts

| Script | Purpose |
| :--- | :--- |
| `npm run dev` | Dev server with HMR on `:5173` |
| `npm run build` | Production build (`build/`) |
| `npm run preview` | Preview the production build |
| `npm run check` | `svelte-check` type verification (must be 0 errors) |

---

## Production

`npm run build` emits a Node server into `build/`, started with `node build`.
The `PORT`, `NODE_ENV`, and `ORIGIN` environment variables configure it — see the
[web Dockerfile](../../infrastructure/docker/Dockerfile.web) and
[`docker-compose.yml`](../../docker-compose.yml).

In the container stack, the app is served behind the
[Caddy proxy](../../infrastructure/README.md) on a single origin
(`http://localhost:3000`), which forwards `/api/*` to the backend. That is why
the frontend uses **relative** `/api/...` URLs everywhere.

---

## Conventions

- **Svelte 5 runes** (`$state`, `$derived`, `$effect`) — no legacy stores for local component state.
- **Local-first:** account-free usage stores everything in IndexedDB (`src/lib/idb`).
- **Images:** artwork `<img>` tags fall back to `/placeholder.svg` via `onerror`,
  so a dead publisher artwork URL never renders as a broken image.
- **Theming:** an inline script in `app.html` applies the saved theme before paint
  to avoid a flash of the wrong theme (anti-FOUC).
- Keep `npm run check` at **0 errors** before opening a PR.
