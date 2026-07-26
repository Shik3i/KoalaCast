/// <reference types="@sveltejs/kit" />
/// <reference lib="webworker" />

// App-shell service worker: precaches the built assets so the UI loads offline,
// and serves navigations network-first (fresh when online, cached shell when not).
// API calls and cross-origin audio are always passed straight through to the
// network — we never cache podcast audio or authenticated API responses here.

import { build, files, version } from '$service-worker';

const sw = self as unknown as ServiceWorkerGlobalScope;

const CACHE = `koalacast-cache-${version}`;
// Vite emits legacy font fallbacks alongside WOFF2, while `files` contains every
// generated icon rendition. Precaching all of them downloaded roughly 9 MB on a
// first visit even though the browser uses only a small subset. Keep the complete
// executable app shell, modern fonts, and the assets required for offline UI/PWA
// installation; all other static assets remain available through normal HTTP.
const PRECACHE_BUILD = build.filter((path) => !/\.(?:woff|ttf|svg)$/.test(path));
const OFFLINE_FILES = new Set([
	'/TwemojiCountryFlags.woff2',
	'/favicon.svg',
	'/icon-192.png',
	'/icon-512.png',
	'/manifest.webmanifest',
	'/placeholder.svg'
]);
const PRECACHE = [...PRECACHE_BUILD, ...files.filter((path) => OFFLINE_FILES.has(path))];

sw.addEventListener('install', (event) => {
	event.waitUntil(
		caches
			.open(CACHE)
			.then(async (cache) => {
				await cache.addAll(PRECACHE);
				// Cache the SPA shell under '/' so an offline navigation to a route we
				// haven't visited yet can still fall back to it. Best-effort: never fail
				// the install if the root can't be fetched at this moment.
				try {
					await cache.add('/');
				} catch (_) {
					/* ignore */
				}
			})
			.then(() => sw.skipWaiting())
	);
});

sw.addEventListener('activate', (event) => {
	event.waitUntil(
		caches
			.keys()
			.then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
			.then(() => sw.clients.claim())
	);
});

sw.addEventListener('fetch', (event) => {
	const { request } = event;
	if (request.method !== 'GET') return;

	const url = new URL(request.url);

	// Only handle same-origin traffic; leave audio CDNs and third parties alone.
	if (url.origin !== sw.location.origin) return;

	// Never cache the API — it's dynamic and often auth-scoped.
	if (url.pathname.startsWith('/api/')) return;

	// Cache-first for immutable build assets and static files.
	if (PRECACHE.includes(url.pathname)) {
		event.respondWith(
			caches.match(request).then((cached) => cached ?? fetch(request))
		);
		return;
	}

	// Network-first for navigations: serve fresh when online (and stash a copy so
	// the page works offline next time), fall back to the cached copy otherwise.
	if (request.mode === 'navigate') {
		event.respondWith(
			fetch(request)
				.then((response) => {
					if (response.ok) {
						const copy = response.clone();
						caches.open(CACHE).then((cache) => cache.put(request, copy));
					}
					return response;
				})
				.catch(async () => {
					const cache = await caches.open(CACHE);
					return (await cache.match(request)) ?? (await cache.match('/')) ?? Response.error();
				})
		);
	}
});
