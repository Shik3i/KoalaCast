import { openDB, type IDBPDatabase } from 'idb';

export interface LocalSubscription {
	podcast_id: string;
	feed_url: string;
	title: string;
	artwork_url: string;
	added_at: number;
}

export interface LocalPlaybackState {
	episode_id: string;
	podcast_id: string;
	position_ms: number;
	completed: boolean;
	progress_percent: number;
	last_played_at: number;
}

export interface LocalQueueItem {
	id: string;
	episode_id: string;
	podcast_id: string;
	title: string;
	artwork_url: string;
	enclosure_url: string;
	duration_ms: number;
	position_order: number;
	added_at: number;
}

export interface LocalFavorite {
	episode_id: string;
	added_at: number;
}

const DB_NAME = 'koalacast_local_db';
const DB_VERSION = 1;

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
				if (!db.objectStoreNames.contains('history')) {
					const histStore = db.createObjectStore('history', { keyPath: 'id', autoIncrement: true });
					histStore.createIndex('played_at', 'played_at');
				}
				if (!db.objectStoreNames.contains('settings')) {
					db.createObjectStore('settings', { keyPath: 'key' });
				}
			}
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
}

export async function removeLocalSubscription(podcast_id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('subscriptions', podcast_id);
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

export async function removeFromLocalQueue(id: string): Promise<void> {
	const db = await getLocalDB();
	await db.delete('queue', id);
}

export async function clearLocalQueue(): Promise<void> {
	const db = await getLocalDB();
	await db.clear('queue');
}

// Clear all local browser data
export async function clearAllLocalData(): Promise<void> {
	const db = await getLocalDB();
	await db.clear('subscriptions');
	await db.clear('playback_states');
	await db.clear('queue');
	await db.clear('favorites');
	await db.clear('history');
	await db.clear('settings');
}
