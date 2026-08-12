# KoalaCast Web (`apps/web`)

The KoalaCast web client — a SvelteKit 5 single-page app (SPA) with a local-first data
layer (IndexedDB) and an optional account-backed sync mode.

- **Framework:** SvelteKit + Svelte 5 (runes)
- **Adapter:** `@sveltejs/adapter-static` (compiled to static SPA assets served natively by Go)
- **Language:** TypeScript
- **Bundler:** Vite

---

## Layout

```text
apps/web/
├── src/
│   ├── app.html                 HTML shell (bundled Phosphor fonts, anti-FOUC theme script)
│   ├── lib/
│   │   ├── components/          Player, Footer, …
│   │   ├── data/                Static fallback data (featured podcasts)
│   │   ├── idb/                 IndexedDB engine (local subscriptions/queue/progress)
│   │   ├── styles/              Global layout + palette-aware design tokens
│   │   └── theme.ts             System/light/dark mode and nine color palettes
│   └── routes/                  Discovery, Search, Inbox, Library, Player, Stats, Account, Settings, Admin
├── static/                      app icons, empty states, SEO files and social artwork
├── svelte.config.js             adapter-static config with fallback: 'index.html'
└── vite.config.ts               Dev server + /api → :3000 proxy
```

---

## Development

```bash
npm install
npm run dev        # http://localhost:5173
```

In dev, Vite proxies `/api`, `/healthz`, and `/readyz` to the Go API on
`http://localhost:3000` (see [`vite.config.ts`](vite.config.ts)), so run the
backend alongside it (`make dev-api`).

### Scripts

| Script | Purpose |
| :--- | :--- |
| `npm run dev` | Dev server with HMR on `:5173` |
| `npm run build` | Production static SPA build (`build/`) |
| `npm run preview` | Preview the static SPA production build |
| `npm run check` | `svelte-check` type verification (must be 0 errors) |
| `npm run check:docs` | Validate tracked Markdown links and current-state assertions |
| `npm run check:release-policy` | Validate tag, workflow and GitHub Release artifact boundaries |
| `npm run check:i18n` | Validate translation catalogues |
| `npm run check:seo` | Validate sitemap, robots, llms and social metadata |
| `npm test` | Run Vitest unit tests |
| `npm run test:ui` | Run Playwright UI regression tests |

---

## Production

`npm run build` emits static SPA assets into `build/`, which are bundled into the production Alpine container and served natively by the single Go application binary (`/app/koalacast`) on port `3000`.

In production, the Go server delivers the static SPA with `index.html` fallback routing, serving both frontend and backend on a single origin (`http://localhost:3000`).

---

## Conventions

- **Svelte 5 runes** (`$state`, `$derived`, `$effect`) — no legacy stores for local component state.
- **Local-first:** account-free usage stores everything in IndexedDB (`src/lib/idb`).
- **Images:** artwork `<img>` tags use `/api/v1/proxy/image` for privacy-safe Catmull-Rom downscaling and RAM LRU caching, falling back to `/cover-placeholder.webp` via `onerror`.
- **Zero asset CDNs:** Icons and fonts are bundled locally.
- Keep `npm run check` at **0 errors** before opening a PR.
