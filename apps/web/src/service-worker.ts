/// <reference types="@sveltejs/kit" />
/// <reference lib="webworker" />

// App-shell service worker: precaches the built assets so the UI loads offline,
// and serves navigations network-first (fresh when online, cached shell when not).
// Authenticated API calls and cross-origin audio are passed straight through.
// Public catalogue JSON uses stale-while-revalidate so a returning listener gets
// the last useful screen immediately while the next snapshot is fetched.

import { build, files, version } from '$service-worker';
import { AUDIO_DOWNLOAD_CACHE } from '$lib/downloads/offline-audio';

const sw = self as unknown as ServiceWorkerGlobalScope;

const CACHE = `koalacast-cache-${version}`;
const PUBLIC_API_CACHE = `koalacast-public-api-${version}`;
// Vite emits legacy font fallbacks alongside WOFF2, while `files` contains every
// generated icon rendition. Precaching all of them downloaded roughly 9 MB on a
// first visit even though the browser uses only a small subset. Keep the complete
// executable app shell, modern fonts, and the assets required for offline UI/PWA
// installation; all other static assets remain available through normal HTTP.
const PRECACHE_BUILD = build.filter((path) => !/\.(?:woff|ttf|svg)$/.test(path));
const OFFLINE_FILES = new Set([
	'/favicon.ico',
	'/icon-32.png',
	'/icon-192.png',
	'/icon-192.webp',
	'/icon-512.png',
	'/manifest.webmanifest',
	'/cover-placeholder.webp',
	'/illustrations/empty-library.webp',
	'/illustrations/empty-search.webp',
	'/illustrations/empty-queue.webp'
]);

sw.addEventListener('notificationclick', (event) => {
	event.notification.close();
	const target = String(event.notification.data?.url || '/inbox');
	event.waitUntil(
		sw.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async (clients) => {
			const existing = clients.find((client) => new URL(client.url).pathname === target);
			if (existing) return existing.focus();
			return sw.clients.openWindow(target);
		})
	);
});
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
			.then((keys) =>
				Promise.all(
					keys
						.filter((k) => k !== CACHE && k !== PUBLIC_API_CACHE && k !== AUDIO_DOWNLOAD_CACHE)
						.map((k) => caches.delete(k))
				)
			)
			.then(() => sw.clients.claim())
	);
});

sw.addEventListener('fetch', (event) => {
	const { request } = event;
	if (request.method !== 'GET') return;

	const url = new URL(request.url);

	// Only handle same-origin traffic; leave audio CDNs and third parties alone.
	if (url.origin !== sw.location.origin) return;

	if (url.pathname.startsWith('/offline/audio/')) {
		event.respondWith(
			caches.open(AUDIO_DOWNLOAD_CACHE).then(async (cache) => {
				const cached = await cache.match(url.pathname);
				if (!cached) return new Response('Audio is not downloaded', { status: 404 });
				const range = request.headers.get('range');
				if (!range) return cached;
				const blob = await cached.blob();
				const match = /^bytes=(\d+)-(\d*)$/.exec(range);
				if (!match) return new Response(null, { status: 416 });
				const start = Number(match[1]);
				const end = match[2] ? Math.min(Number(match[2]), blob.size - 1) : blob.size - 1;
				if (start > end || start >= blob.size) return new Response(null, { status: 416 });
				const chunk = blob.slice(start, end + 1, blob.type);
				return new Response(chunk, {
					status: 206,
					headers: {
						'Content-Type': blob.type || 'application/octet-stream',
						'Content-Length': String(chunk.size),
						'Content-Range': `bytes ${start}-${end}/${blob.size}`,
						'Accept-Ranges': 'bytes'
					}
				});
			})
		);
		return;
	}

	// Artwork is public and keyed by the complete source URL + requested width.
	// Keep successful real covers across SPA navigations. Never store the proxy's
	// temporary fallback response; a later request must be able to retry upstream.
	if (url.pathname === '/api/v1/proxy/image') {
		event.respondWith(
			caches.open(CACHE).then(async (cache) => {
				const cached = await cache.match(request);
				if (cached) return cached;
				const response = await fetch(request);
				if (response.ok && response.headers.get('X-KoalaCast-Image-Fallback') !== 'true') {
					await cache.put(request, response.clone());
				}
				return response;
			})
		);
		return;
	}

	const isPublicContentAPI =
		url.pathname === '/api/v1/podcasts/discover' ||
		url.pathname === '/api/v1/podcasts/search' ||
		/^\/api\/v1\/podcasts\/[^/]+(?:\/episodes)?$/.test(url.pathname) ||
		/^\/api\/v1\/episodes\/[^/]+$/.test(url.pathname) ||
		url.pathname === '/api/v1/proxy/chapters' ||
		url.pathname === '/api/v1/proxy/transcript' ||
		/^\/api\/v1\/episodes\/[^/]+\/transcript$/.test(url.pathname);

	if (isPublicContentAPI) {
		event.respondWith(
			caches.open(PUBLIC_API_CACHE).then(async (cache) => {
				// Explicit revalidation requests come from the app after it has
				// already painted its IndexedDB snapshot.
				if (request.cache === 'reload' || request.cache === 'no-cache') {
					const fresh = await fetch(request);
					if (fresh.ok) await cache.put(request, fresh.clone());
					return fresh;
				}
				const cached = await cache.match(request);
				const update = fetch(request)
					.then(async (fresh) => {
						if (fresh.ok) await cache.put(request, fresh.clone());
						return fresh;
					})
					.catch(() => cached);
				if (cached) {
					event.waitUntil(update.then(() => undefined));
					return cached;
				}
				return (await update) ?? Response.error();
			})
		);
		return;
	}

	// Everything else below /api may be account-scoped.
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
