/**
 * The little that the service worker needs in order to check for new episodes on
 * its own, in a place it can actually reach.
 *
 * Checking for new episodes only ever happened while the Inbox was open, because
 * everything it needs lives where a worker cannot go: the account context is in
 * localStorage (so the worker cannot even tell which IndexedDB database is the
 * current one), and the per-show "notify me" flag is in localStorage too.
 *
 * Rather than move — or duplicate — the whole data layer, the page keeps a small
 * mirror in a database with a *fixed* name: one row per show worth watching, the
 * episode ids already seen, and nothing else. No titles of unplayed episodes, no
 * progress, no account identifiers. The worker reads that, asks the public
 * episode endpoint what is new, and notifies. When the listener next opens the
 * Inbox the mirror is rewritten from the real data.
 */

import { openDB, type IDBPDatabase } from 'idb';

export const BACKGROUND_DB_NAME = 'koalacast_background';
export const BACKGROUND_DB_VERSION = 1;
export const WATCHED_FEEDS_STORE = 'watched_feeds';
export const BACKGROUND_META_STORE = 'meta';
export const NEW_EPISODES_LABEL_KEY = 'new_episodes_label';

export interface WatchedFeed {
	podcast_id: string;
	title: string;
	/** Episode ids already seen. Capped: this is a change detector, not a history. */
	known_episode_ids: string[];
	checked_at: number;
}

/** Enough to survive a burst from a daily show without growing without bound. */
const MAX_KNOWN_IDS = 40;

export function openBackgroundDB(): Promise<IDBPDatabase> {
	return openDB(BACKGROUND_DB_NAME, BACKGROUND_DB_VERSION, {
		upgrade(db) {
			if (!db.objectStoreNames.contains(WATCHED_FEEDS_STORE)) {
				db.createObjectStore(WATCHED_FEEDS_STORE, { keyPath: 'podcast_id' });
			}
			if (!db.objectStoreNames.contains(BACKGROUND_META_STORE)) {
				db.createObjectStore(BACKGROUND_META_STORE, { keyPath: 'key' });
			}
		}
	});
}

export function trimKnownIds(ids: readonly string[]): string[] {
	return ids.slice(0, MAX_KNOWN_IDS);
}

/**
 * Replaces the mirror with the shows that currently want notifications.
 *
 * A full replace rather than an update, so unsubscribing or switching the toggle
 * off removes the row instead of leaving a worker checking a feed nobody is
 * watching any more.
 */
export async function replaceWatchedFeeds(
	feeds: readonly WatchedFeed[],
	// The worker has no access to the i18n runtime — it reads the store, which
	// reads localStorage — so the one sentence it needs is handed to it already
	// translated, with {count} left to substitute.
	newEpisodesLabel?: string
): Promise<void> {
	if (typeof indexedDB === 'undefined') return;
	try {
		const db = await openBackgroundDB();
		if (newEpisodesLabel) {
			await db.put(BACKGROUND_META_STORE, {
				key: NEW_EPISODES_LABEL_KEY,
				value: newEpisodesLabel
			});
		}
		const tx = db.transaction(WATCHED_FEEDS_STORE, 'readwrite');
		await tx.store.clear();
		for (const feed of feeds) {
			await tx.store.put({
				podcast_id: feed.podcast_id,
				title: feed.title,
				known_episode_ids: trimKnownIds(feed.known_episode_ids),
				checked_at: feed.checked_at
			} satisfies WatchedFeed);
		}
		await tx.done;
		db.close();
	} catch {
		// Background refresh is an optimisation; never let it break the Inbox.
	}
}

export async function clearWatchedFeeds(): Promise<void> {
	if (typeof indexedDB === 'undefined') return;
	try {
		const db = await openBackgroundDB();
		await db.clear(WATCHED_FEEDS_STORE);
		db.close();
	} catch {
		/* ignore */
	}
}

export const PERIODIC_SYNC_TAG = 'koalacast-episode-check';
/** Twelve hours; the browser treats this as a floor, not a promise. */
export const PERIODIC_SYNC_INTERVAL_MS = 12 * 60 * 60 * 1000;

/**
 * Asks for periodic background sync. Chromium-only and only for an installed
 * app, which is why nothing in the UI advertises it: where it is unavailable the
 * Inbox keeps refreshing on open, exactly as before.
 */
export async function registerPeriodicEpisodeCheck(): Promise<boolean> {
	if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return false;
	try {
		const registration = (await navigator.serviceWorker.ready) as ServiceWorkerRegistration & {
			periodicSync?: {
				register: (tag: string, options: { minInterval: number }) => Promise<void>;
				getTags: () => Promise<string[]>;
			};
		};
		if (!registration.periodicSync) return false;
		const status = await navigator.permissions
			?.query({ name: 'periodic-background-sync' as PermissionName })
			.catch(() => null);
		if (status && status.state !== 'granted') return false;
		const tags = await registration.periodicSync.getTags();
		if (tags.includes(PERIODIC_SYNC_TAG)) return true;
		await registration.periodicSync.register(PERIODIC_SYNC_TAG, {
			minInterval: PERIODIC_SYNC_INTERVAL_MS
		});
		return true;
	} catch {
		return false;
	}
}
