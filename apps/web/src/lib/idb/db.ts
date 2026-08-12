import { openDB, type IDBPDatabase } from 'idb';
import { normalizeRules, type SmartQueue } from '$lib/queues/smart';

export type InboxMode = 'all' | 'latest';

export interface LocalSubscription {
	podcast_id: string;
	feed_url: string;
	title: string;
	artwork_url: string;
	added_at: number;
	/** Last local metadata mutation; unlike added_at this changes for folder/inbox edits. */
	updated_at?: number;
	// Controls how this show appears in the New/Inbox feed: 'all' lists every
	// recent episode, 'latest' only the single newest one (ideal for hourly news
	// shows that would otherwise flood the feed). Defaults to 'all' when unset.
	inbox_mode?: InboxMode;
	folder?: string;
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
	event_type?: 'PROGRESS_TICK' | 'SEEK' | 'RESTART' | 'MARK_PLAYED' | 'MARK_UNPLAYED';
	playback_session_id?: string;
	per_session_seq?: number;
}

let lastLocalMutationAt = 0;
function nextLocalMutationAt(previous = 0): number {
	lastLocalMutationAt = Math.max(Date.now(), previous + 1, lastLocalMutationAt + 1);
	return lastLocalMutationAt;
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

const QUEUE_UPDATED_AT_KEY = 'queue_updated_at';

async function touchQueue(db: IDBPDatabase<any>, updatedAt = Date.now()): Promise<void> {
	await db.put('settings', { key: QUEUE_UPDATED_AT_KEY, value: updatedAt });
}

export async function getLocalQueueUpdatedAt(): Promise<number> {
	const db = await getLocalDB();
	return Math.max(0, Number((await db.get('settings', QUEUE_UPDATED_AT_KEY))?.value) || 0);
}

export interface LocalCurrentPlayback {
	episode_id: string;
	podcast_id: string;
	title: string;
	podcast_title: string;
	artwork_url: string;
	enclosure_url: string;
	duration_ms: number;
	categories?: string[];
	position_ms: number;
}

export interface LocalContentCacheEntry<T = unknown> {
	key: string;
	value: T;
	stored_at: number;
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
	await touchQueue(db);
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

export interface LocalTimeBookmark {
	id: string;
	episode_id: string;
	position_ms: number;
	label: string;
	created_at: number;
}

export interface LocalNamedQueue {
	id: string;
	name: string;
	items: LocalQueueItem[];
	updated_at: number;
}

const GUEST_DB_NAME = 'koalacast_local_db';
const ACTIVE_CONTEXT_KEY = 'koalacast_local_data_context';
const INITIALIZED_KEY = 'context_initialized';
// v2 dropped the never-used 'history' store. v3 adds local-only listening
// sessions for accurate Profile analytics. v7 adds saved smart-queue rules.
const DB_VERSION = 7;

let dbPromise: Promise<IDBPDatabase> | null = null;
let activeContext = 'guest';
// Serialises context switches against each other *and* against getLocalDB(). The
// window between closing the old database and publishing the new one contains
// several awaits; a concurrent reader (the 30-second progress write, the sync
// loop) used to reopen the *old* context in that gap, leak the connection and
// write the next listener's data into the previous account's database.
let contextSwitch: Promise<void> = Promise.resolve();

function contextDBName(context: string): string {
	return context === 'guest'
		? GUEST_DB_NAME
		: `${GUEST_DB_NAME}_user_${encodeURIComponent(context)}`;
}

function openLocalDB(name: string): Promise<IDBPDatabase> {
	return openDB(name, DB_VERSION, {
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
			if (!db.objectStoreNames.contains('time_bookmarks')) {
				const bookmarks = db.createObjectStore('time_bookmarks', { keyPath: 'id' });
				bookmarks.createIndex('episode_id', 'episode_id');
			}
			if (!db.objectStoreNames.contains('named_queues')) {
				const namedQueues = db.createObjectStore('named_queues', { keyPath: 'id' });
				namedQueues.createIndex('updated_at', 'updated_at');
			}
			if (!db.objectStoreNames.contains('settings')) {
				db.createObjectStore('settings', { keyPath: 'key' });
			}
			if (!db.objectStoreNames.contains('tombstones')) {
				db.createObjectStore('tombstones', { keyPath: 'id' });
			}
			if (!db.objectStoreNames.contains('listening_sessions')) {
				const sessions = db.createObjectStore('listening_sessions', { keyPath: 'id' });
				sessions.createIndex('started_at', 'started_at');
				sessions.createIndex('podcast_id', 'podcast_id');
			}
			if (!db.objectStoreNames.contains('content_cache')) {
				const contentCache = db.createObjectStore('content_cache', { keyPath: 'key' });
				contentCache.createIndex('stored_at', 'stored_at');
			}
			if (!db.objectStoreNames.contains('smart_queues')) {
				db.createObjectStore('smart_queues', { keyPath: 'id' });
			}
			if (db.objectStoreNames.contains('history')) {
				db.deleteObjectStore('history');
			}
		}
	});
}

// Svelte state may expose arrays as reactive proxies. IndexedDB's structured
// clone algorithm rejects those proxies, so normalize denormalized list fields
// exactly once at the persistence boundary.
function plainCategories(categories?: readonly string[]): string[] | undefined {
	return categories ? Array.from(categories, (category) => String(category)) : undefined;
}

function plainJSON<T>(value: T): T {
	return JSON.parse(JSON.stringify(value)) as T;
}

export function getLocalDB(): Promise<IDBPDatabase> {
	if (dbPromise) return dbPromise;
	// Queue behind any context switch in flight. Opening eagerly here would read a
	// half-updated `activeContext` and hand back the previous account's database.
	const pending: Promise<IDBPDatabase> = contextSwitch
		.catch(() => {})
		.then(() =>
			// The switch installs its own connection; adopt it rather than opening a
			// second one to the same database.
			dbPromise && dbPromise !== pending ? dbPromise : openLocalDB(contextDBName(activeContext))
		)
		.catch((err) => {
			if (dbPromise === pending) dbPromise = null;
			throw err;
		});
	dbPromise = pending;
	return pending;
}

export function getLocalDataContext(): string {
	return activeContext;
}

export function wasGuestContextActive(): boolean {
	try {
		const stored = localStorage.getItem(ACTIVE_CONTEXT_KEY);
		return !stored || stored === 'guest';
	} catch {
		return true;
	}
}

async function closeCurrentDB(): Promise<void> {
	const pending = dbPromise;
	dbPromise = null;
	if (!pending) return;
	try {
		(await pending).close();
	} catch {
		// A failed open is already reset by getLocalDB.
	}
}

async function copyAndClearGuestData(target: IDBPDatabase): Promise<void> {
	const guest = await openLocalDB(GUEST_DB_NAME);
	try {
		const stores = [
			'subscriptions',
			'playback_states',
			'queue',
			'favorites',
			'time_bookmarks',
			'named_queues',
			'settings',
			'tombstones',
			'listening_sessions',
			'content_cache',
			'smart_queues'
		] as const;
		const recordsByStore = new Map<string, any[]>();
		for (const storeName of stores) recordsByStore.set(storeName, await guest.getAll(storeName));
		const targetTx = target.transaction([...stores], 'readwrite');
		for (const storeName of stores) {
			for (const record of recordsByStore.get(storeName) ?? []) {
				await targetTx.objectStore(storeName).put(record);
			}
		}
		await targetTx.done;

		const guestTx = guest.transaction([...stores], 'readwrite');
		for (const storeName of stores) await guestTx.objectStore(storeName).clear();
		await guestTx.done;
	} finally {
		guest.close();
	}
}

export function switchLocalDataContext(
	userId: string | null,
	options: { migrateGuest?: boolean } = {}
): Promise<void> {
	const run = contextSwitch.then(
		() => performContextSwitch(userId, options),
		() => performContextSwitch(userId, options)
	);
	contextSwitch = run.catch(() => {});
	return run;
}

async function performContextSwitch(
	userId: string | null,
	options: { migrateGuest?: boolean }
): Promise<void> {
	const nextContext = userId ? `user:${userId}` : 'guest';
	if (nextContext === activeContext) return;

	await closeCurrentDB();
	const nextDB = await openLocalDB(contextDBName(nextContext));
	try {
		const initialized = await nextDB.get('settings', INITIALIZED_KEY);
		if (userId && options.migrateGuest && !initialized) {
			await copyAndClearGuestData(nextDB);
		}
		await nextDB.put('settings', { key: INITIALIZED_KEY, value: true });
	} catch (error) {
		nextDB.close();
		throw error;
	}

	activeContext = nextContext;
	dbPromise = Promise.resolve(nextDB);
	try {
		localStorage.setItem(ACTIVE_CONTEXT_KEY, nextContext);
	} catch {
		// IndexedDB isolation remains effective without the convenience marker.
	}
}

// Subscriptions
export async function getLocalSubscriptions(): Promise<LocalSubscription[]> {
	const db = await getLocalDB();
	return db.getAll('subscriptions');
}

export async function getCachedContent<T>(
	key: string
): Promise<LocalContentCacheEntry<T> | undefined> {
	const db = await getLocalDB();
	return db.get('content_cache', key);
}

export async function putCachedContent<T>(key: string, value: T): Promise<void> {
	const db = await getLocalDB();
	await db.put('content_cache', {
		key,
		value: plainJSON(value),
		stored_at: Date.now()
	} satisfies LocalContentCacheEntry<T>);
}

export async function saveLocalSubscription(sub: LocalSubscription): Promise<void> {
	const db = await getLocalDB();
	const existing = (await db.get('subscriptions', sub.podcast_id)) as LocalSubscription | undefined;
	await db.put('subscriptions', {
		...sub,
		updated_at: sub.updated_at || sub.added_at || Date.now(),
		folder: sub.folder ?? existing?.folder ?? ''
	});
	// Re-subscribing clears any prior deletion tombstone.
	await db.delete('tombstones', tombstoneId('subscription', sub.podcast_id));
}

export async function saveLocalSubscriptions(subscriptions: LocalSubscription[]): Promise<void> {
	if (subscriptions.length === 0) return;
	const db = await getLocalDB();
	const tx = db.transaction(['subscriptions', 'tombstones'], 'readwrite');
	for (const sub of subscriptions) {
		tx.objectStore('subscriptions').put({
			...sub,
			updated_at: sub.updated_at || sub.added_at || Date.now()
		});
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
	sub.updated_at = nextLocalMutationAt(sub.updated_at || sub.added_at || 0);
	await db.put('subscriptions', sub);
}

// Playback States
export async function getLocalPlaybackState(episode_id: string): Promise<LocalPlaybackState | undefined> {
	const db = await getLocalDB();
	return db.get('playback_states', episode_id);
}

export async function saveLocalPlaybackState(state: LocalPlaybackState): Promise<void> {
	const db = await getLocalDB();
	await db.put('playback_states', { ...state, categories: plainCategories(state.categories) });
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
		last_played_at: nextLocalMutationAt(existing?.last_played_at || 0),
		event_type: played ? 'MARK_PLAYED' : 'MARK_UNPLAYED',
		playback_session_id: `manual:${crypto.randomUUID()}`,
		per_session_seq: 1,
		title: meta.title ?? existing?.title,
		podcast_title: meta.podcast_title ?? existing?.podcast_title,
		artwork_url: meta.artwork_url ?? existing?.artwork_url,
		enclosure_url: meta.enclosure_url ?? existing?.enclosure_url,
		duration_ms: meta.duration_ms ?? existing?.duration_ms,
		categories: plainCategories(meta.categories ?? existing?.categories)
	});
}

export async function setEpisodesPlayed(
	episodes: Parameters<typeof setEpisodePlayed>[0][],
	played: boolean
): Promise<void> {
	if (episodes.length === 0) return;
	const db = await getLocalDB();
	const tx = db.transaction('playback_states', 'readwrite');
	for (const meta of episodes) {
		const existing = (await tx.store.get(meta.episode_id)) as LocalPlaybackState | undefined;
		await tx.store.put({
			...existing,
			episode_id: meta.episode_id,
			podcast_id: meta.podcast_id,
			position_ms: played ? existing?.position_ms ?? 0 : 0,
			completed: played,
			progress_percent: played ? 100 : 0,
			last_played_at: nextLocalMutationAt(existing?.last_played_at || 0),
			event_type: played ? 'MARK_PLAYED' : 'MARK_UNPLAYED',
			playback_session_id: `manual:${crypto.randomUUID()}`,
			per_session_seq: 1,
			title: meta.title ?? existing?.title,
			podcast_title: meta.podcast_title ?? existing?.podcast_title,
			artwork_url: meta.artwork_url ?? existing?.artwork_url,
			enclosure_url: meta.enclosure_url ?? existing?.enclosure_url,
			duration_ms: meta.duration_ms ?? existing?.duration_ms,
			categories: plainCategories(meta.categories ?? existing?.categories)
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
	await db.put('listening_sessions', { ...session, categories: plainCategories(session.categories) });
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
	await db.put('queue', { ...item, categories: plainCategories(item.categories) });
	await touchQueue(db);
}

export async function addToLocalQueueIfAbsent(item: LocalQueueItem): Promise<boolean> {
	const db = await getLocalDB();
	const tx = db.transaction('queue', 'readwrite');
	const existing: LocalQueueItem[] = await tx.store.getAll();
	if (existing.some((entry) => entry.episode_id === item.episode_id)) {
		await tx.done;
		return false;
	}
	await tx.store.put({ ...item, categories: plainCategories(item.categories) });
	await tx.done;
	await touchQueue(db);
	return true;
}

export async function addManyToLocalQueue(items: LocalQueueItem[]): Promise<void> {
	if (items.length === 0) return;
	const db = await getLocalDB();
	const tx = db.transaction('queue', 'readwrite');
	for (const item of items) {
		await tx.store.put({ ...item, categories: plainCategories(item.categories) });
	}
	await tx.done;
	await touchQueue(db);
}

export async function removeFromLocalQueue(id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('queue', id);
	await touchQueue(db);
}

export async function clearLocalQueue(): Promise<void> {
	const db = await getLocalDB();
	await db.clear('queue');
	await touchQueue(db);
}

export async function replaceLocalQueueFromSync(
	items: LocalQueueItem[],
	updatedAt: number,
	options: { authoritative?: boolean } = {}
): Promise<void> {
	const db = await getLocalDB();
	const currentUpdatedAt = await getLocalQueueUpdatedAt();
	if (!options.authoritative && currentUpdatedAt >= updatedAt) return;
	const tx = db.transaction(['queue', 'settings'], 'readwrite');
	await tx.objectStore('queue').clear();
	for (const item of items.slice(0, 500)) {
		if (!item?.episode_id || !item.id) continue;
		await tx.objectStore('queue').put({
			...item,
			categories: plainCategories(item.categories)
		});
	}
	await tx.objectStore('settings').put({ key: QUEUE_UPDATED_AT_KEY, value: updatedAt });
	await tx.done;
}

const CURRENT_PLAYBACK_KEY = 'current_playback';

export async function getLocalCurrentPlayback(): Promise<LocalCurrentPlayback | null> {
	const db = await getLocalDB();
	const record = await db.get('settings', CURRENT_PLAYBACK_KEY);
	return record?.value ?? null;
}

export async function saveLocalCurrentPlayback(value: LocalCurrentPlayback | null): Promise<void> {
	const db = await getLocalDB();
	if (!value) {
		await db.delete('settings', CURRENT_PLAYBACK_KEY);
		return;
	}
	await db.put('settings', {
		key: CURRENT_PLAYBACK_KEY,
		value: { ...value, categories: plainCategories(value.categories) }
	});
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
	await db.put('favorites', {
		...fav,
		categories: plainCategories(fav.categories),
		added_at: fav.added_at || Date.now()
	});
	await db.delete('tombstones', tombstoneId('favorite', fav.episode_id));
}

export async function removeLocalFavorite(episode_id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('favorites', episode_id);
	await recordTombstone(db, 'favorite', episode_id);
}

export async function setLocalSubscriptionFolder(
	podcast_id: string,
	folder: string
): Promise<void> {
	const db = await getLocalDB();
	const sub = (await db.get('subscriptions', podcast_id)) as LocalSubscription | undefined;
	if (!sub) return;
	await db.put('subscriptions', {
		...sub,
		folder: folder.trim(),
		updated_at: nextLocalMutationAt(sub.updated_at || sub.added_at || 0)
	});
}

// Timestamp bookmarks are local-first and scoped by the active guest/account
// IndexedDB, like the rest of the listener's private library.
export async function getLocalTimeBookmarks(episode_id: string): Promise<LocalTimeBookmark[]> {
	const db = await getLocalDB();
	const items: LocalTimeBookmark[] = await db.getAllFromIndex(
		'time_bookmarks',
		'episode_id',
		episode_id
	);
	return items.sort((a, b) => a.position_ms - b.position_ms || a.created_at - b.created_at);
}

export async function addLocalTimeBookmark(
	episode_id: string,
	position_ms: number,
	label = ''
): Promise<LocalTimeBookmark> {
	const db = await getLocalDB();
	const bookmark: LocalTimeBookmark = {
		id: crypto.randomUUID(),
		episode_id,
		position_ms: Math.max(0, Math.round(position_ms)),
		label: label.trim(),
		created_at: Date.now()
	};
	await db.put('time_bookmarks', bookmark);
	return bookmark;
}

export async function removeLocalTimeBookmark(id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('time_bookmarks', id);
}

export async function getLocalNamedQueues(): Promise<LocalNamedQueue[]> {
	const db = await getLocalDB();
	const queues: LocalNamedQueue[] = await db.getAll('named_queues');
	return queues.sort((a, b) => b.updated_at - a.updated_at);
}

export async function saveLocalNamedQueue(
	name: string,
	items: LocalQueueItem[]
): Promise<LocalNamedQueue> {
	const normalizedName = name.trim();
	if (!normalizedName) throw new Error('named queue requires a name');
	const db = await getLocalDB();
	const existing: LocalNamedQueue[] = await db.getAll('named_queues');
	const current = existing.find(
		(queue) => queue.name.localeCompare(normalizedName, undefined, { sensitivity: 'base' }) === 0
	);
	const namedQueue: LocalNamedQueue = {
		id: current?.id ?? crypto.randomUUID(),
		name: normalizedName,
		items: plainJSON(items),
		updated_at: Date.now()
	};
	await db.put('named_queues', namedQueue);
	return namedQueue;
}

export async function removeLocalNamedQueue(id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('named_queues', id);
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

export async function getTombstone(
	entity_type: TombstoneEntity,
	entity_id: string
): Promise<LocalTombstone | undefined> {
	const db = await getLocalDB();
	return db.get('tombstones', tombstoneId(entity_type, entity_id));
}

export async function acknowledgeTombstone(
	entity_type: TombstoneEntity,
	entity_id: string
): Promise<void> {
	const db = await getLocalDB();
	await db.delete('tombstones', tombstoneId(entity_type, entity_id));
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
	await db.clear('time_bookmarks');
	await db.clear('named_queues');
	await db.clear('settings');
	await db.clear('tombstones');
	await db.clear('listening_sessions');
	await db.clear('content_cache');
	await db.clear('smart_queues');
}

// ---- Smart queues (saved rule sets; see lib/queues/smart.ts) ----
export async function getSmartQueues(): Promise<SmartQueue[]> {
	const db = await getLocalDB();
	const queues: SmartQueue[] = await db.getAll('smart_queues');
	return queues
		.map((queue) => ({ ...queue, rules: normalizeRules(queue.rules) }))
		.sort((a, b) => b.updated_at - a.updated_at);
}

export async function saveSmartQueue(queue: SmartQueue): Promise<void> {
	const db = await getLocalDB();
	await db.put('smart_queues', plainJSON({ ...queue, rules: normalizeRules(queue.rules) }));
}

export async function removeSmartQueue(id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('smart_queues', id);
}

export interface LocalSyncSnapshot {
	subscriptions?: LocalSubscription[];
	favorites?: LocalFavorite[];
	playback_states?: LocalPlaybackState[];
	listening_sessions?: LocalListeningSession[];
}

function validateLocalSyncSnapshot(snapshot: LocalSyncSnapshot): void {
	if (!snapshot || typeof snapshot !== 'object') throw new Error('invalid sync snapshot');
	const arrays = [
		snapshot.subscriptions,
		snapshot.favorites,
		snapshot.playback_states,
		snapshot.listening_sessions
	];
	if (arrays.some((items) => items !== undefined && !Array.isArray(items))) {
		throw new Error('invalid sync snapshot collections');
	}
	for (const item of snapshot.subscriptions ?? []) {
		if (!item.podcast_id || !Number.isFinite(item.added_at)) {
			throw new Error('invalid sync snapshot subscription');
		}
	}
	for (const item of snapshot.favorites ?? []) {
		if (!item.episode_id || !Number.isFinite(item.added_at)) {
			throw new Error('invalid sync snapshot favorite');
		}
	}
	for (const item of snapshot.playback_states ?? []) {
		if (!item.episode_id || !Number.isFinite(item.last_played_at)) {
			throw new Error('invalid sync snapshot playback state');
		}
	}
	for (const item of snapshot.listening_sessions ?? []) {
		if (!item.id || !Number.isFinite(item.ended_at)) {
			throw new Error('invalid sync snapshot listening session');
		}
	}
}

export async function replaceLocalSyncSnapshot(
	snapshot: LocalSyncSnapshot,
	pushWatermarks: Record<string, number>
): Promise<void> {
	validateLocalSyncSnapshot(snapshot);
	const db = await getLocalDB();
	const pendingSubscriptions = (await db.getAll('subscriptions') as LocalSubscription[])
		.filter((item) => (item.updated_at || item.added_at) > (pushWatermarks.subscription || 0));
	const pendingFavorites = (await db.getAll('favorites') as LocalFavorite[])
		.filter((item) => item.added_at > (pushWatermarks.favorite || 0));
	const pendingTombstones = (await db.getAll('tombstones') as LocalTombstone[])
		.filter((item) => item.deleted_at > (pushWatermarks[item.entity_type] || 0));
	const stores = [
		'subscriptions',
		'favorites',
		'playback_states',
		'listening_sessions',
		'tombstones'
	] as const;
	const tx = db.transaction([...stores], 'readwrite');
	// Subscriptions/favorites are authoritative because both support deletes.
	// Playback states and listening sessions are append/upsert-only: retain local
	// records that may not have reached the server before cursor compaction.
	for (const storeName of ['subscriptions', 'favorites', 'tombstones'] as const) {
		await tx.objectStore(storeName).clear();
	}
	for (const item of snapshot.subscriptions ?? []) {
		await tx.objectStore('subscriptions').put(item);
	}
	for (const item of snapshot.favorites ?? []) {
		await tx.objectStore('favorites').put({
			...item,
			categories: plainCategories(item.categories)
		});
	}
	// Snapshot state ends at the server cursor. Local mutations newer than the
	// last successful push still need to win and be uploaded on this sync run.
	for (const item of pendingSubscriptions) {
		await tx.objectStore('subscriptions').put(item);
	}
	for (const item of pendingFavorites) {
		await tx.objectStore('favorites').put({
			...item,
			categories: plainCategories(item.categories)
		});
	}
	for (const item of pendingTombstones) {
		await tx.objectStore('tombstones').put(item);
		if (item.entity_type === 'subscription') {
			await tx.objectStore('subscriptions').delete(item.entity_id);
		} else {
			await tx.objectStore('favorites').delete(item.entity_id);
		}
	}
	for (const item of snapshot.playback_states ?? []) {
		const store = tx.objectStore('playback_states');
		const existing = await store.get(item.episode_id);
		if (!existing || item.last_played_at > existing.last_played_at) {
			await store.put({
				...item,
				categories: plainCategories(item.categories)
			});
		}
	}
	for (const item of snapshot.listening_sessions ?? []) {
		const store = tx.objectStore('listening_sessions');
		const existing = await store.get(item.id);
		if (!existing || item.ended_at > existing.ended_at) {
			await store.put({
				...item,
				categories: plainCategories(item.categories)
			});
		}
	}
	await tx.done;
}
