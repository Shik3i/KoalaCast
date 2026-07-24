/// <reference types="@sveltejs/kit" />
/// <reference lib="webworker" />

// App-shell service worker: precaches the built assets so the UI loads offline,
// and serves navigations network-first (fresh when online, cached shell when not).
// API calls and cross-origin audio are always passed straight through to the
// network — we never cache podcast audio or authenticated API responses here.

import { build, files, version } from '$service-worker';

const sw = self as unknown as ServiceWorkerGlobalScope;

const CACHE = `koalacast-cache-${version}`;
const PRECACHE = [...build, ...files];

sw.addEventListener('install', (event) => {
	event.waitUntil(
		caches.open(CACHE).then((cache) => cache.addAll(PRECACHE)).then(() => sw.skipWaiting())
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
