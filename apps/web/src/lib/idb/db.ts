import { openDB, type IDBPDatabase } from 'idb';

export type InboxMode = 'all' | 'latest';

export interface LocalSubscription {
	podcast_id: string;
	feed_url: string;
	title: string;
	artwork_url: string;
	added_at: number;
	// Controls how this show appears in the New/Inbox feed: 'all' lists every
	// recent episode, 'latest' only the single newest one (ideal for hourly news
	// shows that would otherwise flood the feed). Defaults to 'all' when unset.
	inbox_mode?: InboxMode;
}

export interface LocalPlaybackState {
	episode_id: string;
	podcast_id: string;
	position_ms: number;
	completed: boolean;
	progress_percent: number;
	last_played_at: number;
	// Optional denormalized track metadata so a "continue listening" shelf can
	// render and resume playback without an extra network round-trip.
	title?: string;
	podcast_title?: string;
	artwork_url?: string;
	enclosure_url?: string;
	duration_ms?: number;
	categories?: string[];
}

// Fine-grained listening telemetry. Each record covers one uninterrupted play
// segment (play → pause/end). It stays local in guest mode and participates in
// account sync after sign-in.
export interface LocalListeningSession {
	id: string;
	episode_id: string;
	podcast_id: string;
	title: string;
	podcast_title: string;
	categories?: string[];
	started_at: number;
	ended_at: number;
	wall_clock_ms: number;
	audio_listened_ms: number;
	speed_saved_ms: number;
	silence_saved_ms: number;
	manual_skipped_ms: number;
	intro_outro_skipped_ms: number;
	speed_weighted_ms: number;
}

export interface LocalQueueItem {
	id: string;
	episode_id: string;
	podcast_id: string;
	title: string;
	podcast_title?: string;
	artwork_url: string;
	enclosure_url: string;
	duration_ms: number;
	position_order: number;
	added_at: number;
	categories?: string[];
}

// Persist a new queue order (drag-to-reorder). Rewrites position_order to match
// the given episode_id sequence.
export async function reorderLocalQueue(orderedIds: string[]): Promise<void> {
	const db = await getLocalDB();
	const items: LocalQueueItem[] = await db.getAll('queue');
	const byEpisode = new Map(items.map((i) => [i.episode_id, i]));
	const tx = db.transaction('queue', 'readwrite');
	orderedIds.forEach((epId, idx) => {
		const item = byEpisode.get(epId);
		if (item) tx.store.put({ ...item, position_order: idx });
	});
	await tx.done;
}

export interface LocalFavorite {
	episode_id: string;
	added_at: number;
	// Denormalized so the Favorites list renders and plays without a refetch.
	podcast_id?: string;
	title?: string;
	podcast_title?: string;
	artwork_url?: string;
	enclosure_url?: string;
	duration_ms?: number;
	categories?: string[];
}

const DB_NAME = 'koalacast_local_db';
// v2 dropped the never-used 'history' store. v3 adds local-only listening
// sessions for accurate Profile analytics.
const DB_VERSION = 3;

let dbPromise: Promise<IDBPDatabase> | null = null;

export function getLocalDB(): Promise<IDBPDatabase> {
	if (!dbPromise) {
		dbPromise = openDB(DB_NAME, DB_VERSION, {
			upgrade(db) {
				if (!db.objectStoreNames.contains('subscriptions')) {
					db.createObjectStore('subscriptions', { keyPath: 'podcast_id' });
				}
				if (!db.objectStoreNames.contains('playback_states')) {
					db.createObjectStore('playback_states', { keyPath: 'episode_id' });
				}
				if (!db.objectStoreNames.contains('queue')) {
					const queueStore = db.createObjectStore('queue', { keyPath: 'id' });
					queueStore.createIndex('position_order', 'position_order');
				}
				if (!db.objectStoreNames.contains('favorites')) {
					db.createObjectStore('favorites', { keyPath: 'episode_id' });
				}
				if (!db.objectStoreNames.contains('settings')) {
					db.createObjectStore('settings', { keyPath: 'key' });
				}
				// Deletion tombstones so unsubscribe/unfavorite can propagate through
				// cross-device sync (an "upsert-only" sync would resurrect removed items).
				if (!db.objectStoreNames.contains('tombstones')) {
					db.createObjectStore('tombstones', { keyPath: 'id' });
				}
				if (!db.objectStoreNames.contains('listening_sessions')) {
					const sessions = db.createObjectStore('listening_sessions', { keyPath: 'id' });
					sessions.createIndex('started_at', 'started_at');
					sessions.createIndex('podcast_id', 'podcast_id');
				}
				// v1 created a 'history' store that was never read or written; drop it
				// when upgrading an existing database.
				if (db.objectStoreNames.contains('history')) {
					db.deleteObjectStore('history');
				}
			}
		}).catch((err) => {
			dbPromise = null;
			throw err;
		});
	}
	return dbPromise;
}

// Subscriptions
export async function getLocalSubscriptions(): Promise<LocalSubscription[]> {
	const db = await getLocalDB();
	return db.getAll('subscriptions');
}

export async function saveLocalSubscription(sub: LocalSubscription): Promise<void> {
	const db = await getLocalDB();
	await db.put('subscriptions', sub);
	// Re-subscribing clears any prior deletion tombstone.
	await db.delete('tombstones', tombstoneId('subscription', sub.podcast_id));
}

export async function saveLocalSubscriptions(subscriptions: LocalSubscription[]): Promise<void> {
	if (subscriptions.length === 0) return;
	const db = await getLocalDB();
	const tx = db.transaction(['subscriptions', 'tombstones'], 'readwrite');
	for (const sub of subscriptions) {
		tx.objectStore('subscriptions').put(sub);
		tx.objectStore('tombstones').delete(tombstoneId('subscription', sub.podcast_id));
	}
	await tx.done;
}

export async function removeLocalSubscription(podcast_id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('subscriptions', podcast_id);
	await recordTombstone(db, 'subscription', podcast_id);
}

// Update a single subscription's inbox mode (read-modify-write; no-op if the
// subscription no longer exists).
export async function setSubscriptionInboxMode(
	podcast_id: string,
	mode: InboxMode
): Promise<void> {
	const db = await getLocalDB();
	const sub = (await db.get('subscriptions', podcast_id)) as LocalSubscription | undefined;
	if (!sub) return;
	sub.inbox_mode = mode;
	await db.put('subscriptions', sub);
}

// Playback States
export async function getLocalPlaybackState(episode_id: string): Promise<LocalPlaybackState | undefined> {
	const db = await getLocalDB();
	return db.get('playback_states', episode_id);
}

export async function saveLocalPlaybackState(state: LocalPlaybackState): Promise<void> {
	const db = await getLocalDB();
	await db.put('playback_states', state);
}

// Set of episode ids the user has completed — used to filter the Inbox to unplayed.
export async function getCompletedEpisodeIds(): Promise<Set<string>> {
	const db = await getLocalDB();
	const all: LocalPlaybackState[] = await db.getAll('playback_states');
	return new Set(all.filter((s) => s.completed).map((s) => s.episode_id));
}

// Manually mark an episode played/unplayed without listening. Preserves any
// existing position; played => completed + 100%, unplayed => reset to 0%.
export async function setEpisodePlayed(
	meta: {
		episode_id: string;
		podcast_id: string;
		title?: string;
		podcast_title?: string;
		artwork_url?: string;
		enclosure_url?: string;
		duration_ms?: number;
		categories?: string[];
	},
	played: boolean
): Promise<void> {
	const db = await getLocalDB();
	const existing = (await db.get('playback_states', meta.episode_id)) as
		| LocalPlaybackState
		| undefined;
	await db.put('playback_states', {
		...existing,
		episode_id: meta.episode_id,
		podcast_id: meta.podcast_id,
		position_ms: played ? existing?.position_ms ?? 0 : 0,
		completed: played,
		progress_percent: played ? 100 : 0,
		last_played_at: Date.now(),
		title: meta.title ?? existing?.title,
		podcast_title: meta.podcast_title ?? existing?.podcast_title,
		artwork_url: meta.artwork_url ?? existing?.artwork_url,
		enclosure_url: meta.enclosure_url ?? existing?.enclosure_url,
		duration_ms: meta.duration_ms ?? existing?.duration_ms,
		categories: meta.categories ?? existing?.categories
	});
}

export async function setEpisodesPlayed(
	episodes: Parameters<typeof setEpisodePlayed>[0][],
	played: boolean
): Promise<void> {
	if (episodes.length === 0) return;
	const db = await getLocalDB();
	const tx = db.transaction('playback_states', 'readwrite');
	const now = Date.now();
	for (const meta of episodes) {
		const existing = (await tx.store.get(meta.episode_id)) as LocalPlaybackState | undefined;
		await tx.store.put({
			...existing,
			episode_id: meta.episode_id,
			podcast_id: meta.podcast_id,
			position_ms: played ? existing?.position_ms ?? 0 : 0,
			completed: played,
			progress_percent: played ? 100 : 0,
			last_played_at: now,
			title: meta.title ?? existing?.title,
			podcast_title: meta.podcast_title ?? existing?.podcast_title,
			artwork_url: meta.artwork_url ?? existing?.artwork_url,
			enclosure_url: meta.enclosure_url ?? existing?.enclosure_url,
			duration_ms: meta.duration_ms ?? existing?.duration_ms,
			categories: meta.categories ?? existing?.categories
		});
	}
	await tx.done;
}

// All playback states (used by the sync engine to push local progress).
export async function getAllLocalPlaybackStates(): Promise<LocalPlaybackState[]> {
	const db = await getLocalDB();
	return db.getAll('playback_states');
}

// Recently played, still-in-progress episodes for the "Continue Listening" shelf.
// Most-recent first; completed episodes and untouched (0%) ones are filtered out.
export async function getRecentPlaybackStates(limit = 12): Promise<LocalPlaybackState[]> {
	const db = await getLocalDB();
	const all: LocalPlaybackState[] = await db.getAll('playback_states');
	return all
		.filter((s) => !s.completed && s.position_ms > 5000 && s.progress_percent < 98)
		.sort((a, b) => b.last_played_at - a.last_played_at)
		.slice(0, limit);
}

// Listening analytics source records.
export async function saveLocalListeningSession(session: LocalListeningSession): Promise<void> {
	const db = await getLocalDB();
	await db.put('listening_sessions', session);
}

export async function getLocalListeningSessions(): Promise<LocalListeningSession[]> {
	const db = await getLocalDB();
	const sessions: LocalListeningSession[] = await db.getAllFromIndex('listening_sessions', 'started_at');
	return sessions.sort((a, b) => a.started_at - b.started_at);
}

export async function getLocalListeningSession(id: string): Promise<LocalListeningSession | undefined> {
	const db = await getLocalDB();
	return db.get('listening_sessions', id);
}

// Queue
export async function getLocalQueue(): Promise<LocalQueueItem[]> {
	const db = await getLocalDB();
	const items: LocalQueueItem[] = await db.getAllFromIndex('queue', 'position_order');
	return items.sort((a, b) => a.position_order - b.position_order);
}

export async function addToLocalQueue(item: LocalQueueItem): Promise<void> {
	const db = await getLocalDB();
	await db.put('queue', item);
}

export async function addManyToLocalQueue(items: LocalQueueItem[]): Promise<void> {
	if (items.length === 0) return;
	const db = await getLocalDB();
	const tx = db.transaction('queue', 'readwrite');
	for (const item of items) await tx.store.put(item);
	await tx.done;
}

export async function removeFromLocalQueue(id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('queue', id);
}

export async function clearLocalQueue(): Promise<void> {
	const db = await getLocalDB();
	await db.clear('queue');
}

// Favorites
export async function getLocalFavorites(): Promise<LocalFavorite[]> {
	const db = await getLocalDB();
	const items: LocalFavorite[] = await db.getAll('favorites');
	return items.sort((a, b) => b.added_at - a.added_at);
}

export async function getFavoriteEpisodeIds(): Promise<Set<string>> {
	const db = await getLocalDB();
	const items: LocalFavorite[] = await db.getAll('favorites');
	return new Set(items.map((f) => f.episode_id));
}

export async function isLocalFavorite(episode_id: string): Promise<boolean> {
	const db = await getLocalDB();
	return !!(await db.get('favorites', episode_id));
}

export async function addLocalFavorite(fav: LocalFavorite): Promise<void> {
	const db = await getLocalDB();
	await db.put('favorites', { ...fav, added_at: fav.added_at || Date.now() });
	await db.delete('tombstones', tombstoneId('favorite', fav.episode_id));
}

export async function removeLocalFavorite(episode_id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('favorites', episode_id);
	await recordTombstone(db, 'favorite', episode_id);
}

// ---- Deletion tombstones (for cross-device sync) ----
export type TombstoneEntity = 'subscription' | 'favorite';
export interface LocalTombstone {
	id: string; // `${entity_type}:${entity_id}`
	entity_type: TombstoneEntity;
	entity_id: string;
	deleted_at: number;
}

function tombstoneId(entity_type: TombstoneEntity, entity_id: string): string {
	return `${entity_type}:${entity_id}`;
}

async function recordTombstone(
	db: IDBPDatabase,
	entity_type: TombstoneEntity,
	entity_id: string
): Promise<void> {
	await db.put('tombstones', {
		id: tombstoneId(entity_type, entity_id),
		entity_type,
		entity_id,
		deleted_at: Date.now()
	} satisfies LocalTombstone);
}

export async function getTombstones(): Promise<LocalTombstone[]> {
	const db = await getLocalDB();
	return db.getAll('tombstones');
}

// Removers used by the sync applier when a delete arrives from another device:
// they must NOT create a new tombstone (that would echo the delete back out).
export async function removeLocalSubscriptionSilent(podcast_id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('subscriptions', podcast_id);
}
export async function removeLocalFavoriteSilent(episode_id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('favorites', episode_id);
}

// Clear all local browser data
export async function clearAllLocalData(): Promise<void> {
	const db = await getLocalDB();
	await db.clear('subscriptions');
	await db.clear('playback_states');
	await db.clear('queue');
	await db.clear('favorites');
	await db.clear('settings');
	await db.clear('tombstones');
	await db.clear('listening_sessions');
}
