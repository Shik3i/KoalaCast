/// <reference types="@sveltejs/kit" />
/// <reference lib="webworker" />

// App-shell service worker: precaches the built assets so the UI loads offline,
// and serves navigations network-first (fresh when online, cached shell when not).
// Authenticated API calls and cross-origin audio are passed straight through.
// Public catalogue JSON uses stale-while-revalidate so a returning listener gets
// the last useful screen immediately while the next snapshot is fetched.

import { build, files, version } from '$service-worker';
import {
	AUDIO_DOWNLOAD_CACHE_PREFIX,
	audioDownloadCacheNameForOfflinePath
} from '$lib/downloads/offline-audio';
import {
	BACKGROUND_META_STORE,
	NEW_EPISODES_LABEL_KEY,
	openBackgroundDB,
	PERIODIC_SYNC_TAG,
	trimKnownIds,
	WATCHED_FEEDS_STORE,
	type WatchedFeed
} from '$lib/background/feed-mirror';

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

/**
 * A push payload decides where the notification takes the listener, so it is
 * only ever allowed to name a path on this origin. An absolute URL would sail
 * past the pathname comparison below and open an arbitrary site in a window the
 * listener believes belongs to KoalaCast. A leading `//` is a host, not a path.
 */
function safeNotificationPath(value: unknown): string {
	if (typeof value !== 'string') return '/inbox';
	if (!value.startsWith('/') || value.startsWith('//')) return '/inbox';
	return value;
}

sw.addEventListener('push', (event) => {
	let payload: { title?: string; body?: string; tag?: string; url?: string } = {};
	try {
		payload = event.data?.json() || {};
	} catch (_) {
		payload = {};
	}
	event.waitUntil(
		sw.registration.showNotification(payload.title || 'KoalaCast', {
			body: payload.body || '',
			icon: '/icon-192.png',
			badge: '/icon-72.png',
			tag: payload.tag || 'koalacast-update',
			data: { url: safeNotificationPath(payload.url) }
		})
	);
});

/**
 * Checks the watched shows for new episodes without the app being open.
 *
 * Reads the mirror the page maintains (see background/feed-mirror.ts) rather
 * than the account's own database, whose name the worker cannot know. Failures
 * are per-feed: one unreachable publisher must not stop the rest.
 */
async function checkWatchedFeedsForNewEpisodes(): Promise<void> {
	// A real server push already covers this device; showing the same episode a
	// second time from here would be a duplicate notification.
	if (await sw.registration.pushManager?.getSubscription().catch(() => null)) return;

	const db = await openBackgroundDB();
	try {
		const feeds: WatchedFeed[] = await db.getAll(WATCHED_FEEDS_STORE);
		const label = String(
			(await db.get(BACKGROUND_META_STORE, NEW_EPISODES_LABEL_KEY))?.value ?? '{count} new episodes'
		);
		for (const feed of feeds) {
			try {
				const response = await fetch(
					`/api/v1/podcasts/${encodeURIComponent(feed.podcast_id)}/episodes?limit=5`,
					{ cache: 'no-store' }
				);
				if (!response.ok) continue;
				const data = await response.json();
				const episodes: Array<{ id?: string; title?: string }> = data.episodes || [];
				const known = new Set(feed.known_episode_ids);
				const fresh = episodes.filter((episode) => episode.id && !known.has(episode.id));
				const seenIds = trimKnownIds([
					...episodes.map((episode) => String(episode.id ?? '')).filter(Boolean),
					...feed.known_episode_ids
				]);
				await db.put(WATCHED_FEEDS_STORE, {
					...feed,
					known_episode_ids: seenIds,
					checked_at: Date.now()
				});
				// An empty `known_episode_ids` means the mirror has never been filled
				// for this show; announcing its whole back catalogue as "new" would be
				// a wall of notifications on the first run.
				if (fresh.length === 0 || feed.known_episode_ids.length === 0) continue;
				await sw.registration.showNotification(feed.title, {
					body:
						fresh.length === 1
							? String(fresh[0].title ?? '')
							: label.replace('{count}', String(fresh.length)),
					icon: '/icon-192.png',
					badge: '/icon-72.png',
					tag: `new-episodes-${feed.podcast_id}`,
					data: { url: `/podcast/${encodeURIComponent(feed.podcast_id)}` }
				});
			} catch {
				// Next run gets another go at this feed.
			}
		}
	} finally {
		db.close();
	}
}

sw.addEventListener('periodicsync', (event) => {
	const periodic = event as ExtendableEvent & { tag?: string };
	if (periodic.tag !== PERIODIC_SYNC_TAG) return;
	periodic.waitUntil(checkWatchedFeedsForNewEpisodes());
});

// One-shot fallback, so a browser that has Background Sync but not the periodic
// variant still catches up the next time it regains connectivity.
sw.addEventListener('sync', (event) => {
	const oneShot = event as ExtendableEvent & { tag?: string };
	if (oneShot.tag !== PERIODIC_SYNC_TAG) return;
	oneShot.waitUntil(checkWatchedFeedsForNewEpisodes());
});

sw.addEventListener('notificationclick', (event) => {
	event.notification.close();
	const target = safeNotificationPath(event.notification.data?.url);
	event.waitUntil(
		sw.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async (clients) => {
			const existing = clients.find((client) => new URL(client.url).pathname === target);
			if (existing) return existing.focus();
			return sw.clients.openWindow(target);
		})
	);
});
const PRECACHE = [...PRECACHE_BUILD, ...files.filter((path) => OFFLINE_FILES.has(path))];

// Both runtime caches grew without limit: every artwork variant and every
// visited episode URL stayed until the browser evicted the whole origin. These
// are the *runtime* additions only — the precached shell is not counted and
// never trimmed, so the app keeps working offline no matter how full they get.
const MAX_RUNTIME_ENTRIES = 300;
const MAX_PUBLIC_API_ENTRIES = 200;

async function trimCache(cacheName: string, maxEntries: number, keep: (url: URL) => boolean) {
	const cache = await caches.open(cacheName);
	const keys = await cache.keys();
	const evictable = keys.filter((request) => !keep(new URL(request.url)));
	// Cache.keys() returns insertion order, so the head of the list is the oldest.
	const excess = evictable.length - maxEntries;
	for (let index = 0; index < excess; index++) await cache.delete(evictable[index]);
}

/** The executable shell and the '/' offline fallback are never evicted. */
function keepInAppShell(url: URL): boolean {
	return url.origin === sw.location.origin && (PRECACHE.includes(url.pathname) || url.pathname === '/');
}

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
						.filter(
							(k) =>
								k !== CACHE &&
								k !== PUBLIC_API_CACHE &&
								!k.startsWith(`${AUDIO_DOWNLOAD_CACHE_PREFIX}-`)
						)
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
		const audioCacheName = audioDownloadCacheNameForOfflinePath(url.pathname);
		event.respondWith(
			(audioCacheName ? caches.open(audioCacheName) : Promise.resolve(null)).then(async (cache) => {
				if (!cache) return new Response('Invalid offline audio path', { status: 404 });
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
					event.waitUntil(trimCache(CACHE, MAX_RUNTIME_ENTRIES, keepInAppShell));
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
					if (fresh.ok) {
						await cache.put(request, fresh.clone());
						event.waitUntil(trimCache(PUBLIC_API_CACHE, MAX_PUBLIC_API_ENTRIES, () => false));
					}
					return fresh;
				}
				const cached = await cache.match(request);
				const update = fetch(request)
					.then(async (fresh) => {
						if (fresh.ok) {
						await cache.put(request, fresh.clone());
						event.waitUntil(trimCache(PUBLIC_API_CACHE, MAX_PUBLIC_API_ENTRIES, () => false));
					}
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
						event.waitUntil(
							caches
								.open(CACHE)
								.then((cache) => cache.put(request, copy))
								.then(() => trimCache(CACHE, MAX_RUNTIME_ENTRIES, keepInAppShell))
								// A full quota must not turn a served page into a failed one.
								.catch(() => undefined)
						);
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
