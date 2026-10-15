import { beforeEach, describe, expect, it, vi } from 'vitest';

// Named queues and timestamp bookmarks ride the same engine as the other entities;
// this pins their push selection, watermarks and the way received records are applied.

const state = vi.hoisted(() => ({
	namedQueues: [] as any[],
	bookmarks: [] as any[],
	tombstones: [] as any[],
	applyQueue: [] as any[],
	removeQueue: [] as any[],
	applyBookmark: [] as any[],
	removeBookmark: [] as string[]
}));

vi.mock('#lib/idb/db.ts', () => ({
	getLocalSubscriptions: async () => [],
	getLocalFavorites: async () => [],
	getAllLocalPlaybackStates: async () => [],
	getLocalListeningSessions: async () => [],
	getLocalListeningSession: async () => undefined,
	getLocalQueue: async () => [],
	getLocalQueueUpdatedAt: async () => 0,
	getLocalNamedQueues: async () => state.namedQueues,
	getAllLocalTimeBookmarks: async () => state.bookmarks,
	applySyncedNamedQueue: async (queue: unknown) => void state.applyQueue.push(queue),
	removeSyncedNamedQueue: async (key: string, at: number) => void state.removeQueue.push([key, at]),
	applySyncedTimeBookmark: async (bookmark: unknown) => void state.applyBookmark.push(bookmark),
	removeSyncedTimeBookmark: async (id: string) => void state.removeBookmark.push(id),
	getTombstones: async () => state.tombstones,
	getTombstone: async () => undefined,
	acknowledgeTombstone: async () => {},
	getLocalPlaybackState: async () => undefined,
	saveLocalSubscription: async () => {},
	saveLocalPlaybackState: async () => {},
	saveLocalListeningSession: async () => {},
	addLocalFavorite: async () => {},
	removeLocalSubscriptionSilent: async () => {},
	removeLocalFavoriteSilent: async () => {},
	replaceLocalQueueFromSync: async () => {},
	replaceLocalSyncSnapshot: async () => {}
}));

vi.mock('#lib/stores/podcast-settings.ts', () => ({
	applySyncedPodcastPlaybackSettings: () => {},
	clearPodcastPlaybackSettingsContext: () => {},
	getAllPodcastPlaybackSettings: () => [],
	removePodcastPlaybackSettings: () => {}
}));

vi.mock('#lib/stores/prefs.svelte.ts', () => ({
	prefs: { updatedAt: 0, syncPayload: () => ({ updated_at: 0 }), applySynced: () => {}, resetSynced: () => {} }
}));

class MemoryStorage {
	values = new Map<string, string>();
	getItem(key: string) {
		return this.values.get(key) ?? null;
	}
	setItem(key: string, value: string) {
		this.values.set(key, value);
	}
	removeItem(key: string) {
		this.values.delete(key);
	}
}

interface Operation {
	entity_type: string;
	entity_id: string;
	action: string;
	client_op_id: string;
	client_timestamp: number;
	payload: any;
}

let pushed: Operation[] = [];
let storage: MemoryStorage;

function installFetch(changesets: unknown[] = []) {
	pushed = [];
	vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => {
		if (init?.method === 'POST') {
			pushed.push(...(JSON.parse(String(init.body)).operations as Operation[]));
			return new Response('{}', { status: 200 });
		}
		return new Response(
			JSON.stringify({
				changesets,
				next_cursor: changesets.length,
				has_more: false,
				data_generation: 0
			}),
			{ status: 200 }
		);
	});
}

async function loadSync() {
	storage = new MemoryStorage();
	vi.stubGlobal('localStorage', storage);
	vi.stubGlobal('document', { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} });
	vi.resetModules();
	const sync = (await import('./sync.svelte')).sync;
	sync.userId = 'user-1';
	return sync;
}

const queue = (name: string, updated_at: number, items: unknown[] = [{ episode_id: 'e1' }]) => ({
	id: `id-${name}`,
	name,
	items,
	updated_at
});
const bookmark = (id: string, created_at: number) => ({
	id,
	episode_id: 'ep-1',
	position_ms: 61_000,
	label: 'nice',
	created_at
});

describe('library sync: push', () => {
	beforeEach(() => {
		vi.unstubAllGlobals();
		state.namedQueues = [];
		state.bookmarks = [];
		state.tombstones = [];
		state.applyQueue = [];
		state.removeQueue = [];
		state.applyBookmark = [];
		state.removeBookmark = [];
	});

	it('uploads named queues under their name key and bookmarks under their id', async () => {
		state.namedQueues = [queue('  Morning Commute ', 2000)];
		state.bookmarks = [bookmark('bm-1', 3000)];
		installFetch();
		const sync = await loadSync();
		await sync.syncNow();

		expect(sync.status).toBe('idle');
		const q = pushed.find((op) => op.entity_type === 'named_queue')!;
		expect(q).toMatchObject({
			entity_id: 'morning commute',
			action: 'upsert',
			client_op_id: 'nq:morning commute:2000',
			client_timestamp: 2000
		});
		expect(q.payload).toEqual({ id: 'id-  Morning Commute ', name: '  Morning Commute ', items: [{ episode_id: 'e1' }], updated_at: 2000 });
		const b = pushed.find((op) => op.entity_type === 'time_bookmark')!;
		expect(b).toMatchObject({ entity_id: 'bm-1', action: 'upsert', client_op_id: 'tb:bm-1:3000', client_timestamp: 3000 });
		expect(b.payload).toEqual({ id: 'bm-1', episode_id: 'ep-1', position_ms: 61000, label: 'nice', created_at: 3000 });
	});

	it('does not send the same records again once they were accepted', async () => {
		state.namedQueues = [queue('Work', 2000)];
		state.bookmarks = [bookmark('bm-1', 3000)];
		installFetch();
		const sync = await loadSync();
		await sync.syncNow();
		expect(pushed).toHaveLength(2);
		pushed = [];
		await sync.syncNow();
		expect(pushed).toHaveLength(0);

		// A genuine later edit is picked up again.
		state.namedQueues = [queue('Work', 2500)];
		await sync.syncNow();
		expect(pushed.map((op) => op.client_op_id)).toEqual(['nq:work:2500']);
	});

	it('uploads existing history even when the legacy single watermark is far ahead', async () => {
		installFetch();
		state.namedQueues = [queue('Old queue', 1000)];
		state.bookmarks = [bookmark('old-bm', 1100)];
		const sync = await loadSync();
		// An install that predates per-entity watermarks still carries this key.
		storage.setItem('koalacast_sync_push_watermark_user-1', '9999999999999');
		await sync.syncNow();
		expect(pushed.map((op) => op.entity_type).sort()).toEqual(['named_queue', 'time_bookmark']);
	});

	it('keeps a queue the server would refuse on the device instead of failing every run', async () => {
		state.namedQueues = [
			queue('Too long', 5000, Array.from({ length: 501 }, () => ({ episode_id: 'x' }))),
			queue('n'.repeat(201), 5001),
			queue('Fine', 5002)
		];
		installFetch();
		const sync = await loadSync();
		await sync.syncNow();
		expect(sync.rejectedOperations).toBe(0);
		expect(pushed.map((op) => op.entity_id)).toEqual(['fine']);
	});

	it('sends only the newest of two queues that share a key', async () => {
		state.namedQueues = [queue('Road Trip', 1000), queue('road trip', 4000)];
		installFetch();
		const sync = await loadSync();
		await sync.syncNow();
		expect(pushed).toHaveLength(1);
		expect(pushed[0].client_timestamp).toBe(4000);
	});

	it('propagates deletions of both kinds as delete operations', async () => {
		state.tombstones = [
			{ id: 'named_queue:work', entity_type: 'named_queue', entity_id: 'work', deleted_at: 7000 },
			{ id: 'time_bookmark:bm-9', entity_type: 'time_bookmark', entity_id: 'bm-9', deleted_at: 7100 }
		];
		installFetch();
		const sync = await loadSync();
		await sync.syncNow();
		expect(pushed.map((op) => [op.entity_type, op.entity_id, op.action, op.client_timestamp])).toEqual([
			['named_queue', 'work', 'delete', 7000],
			['time_bookmark', 'bm-9', 'delete', 7100]
		]);
	});

	it('skips a bookmark the server would refuse', async () => {
		state.bookmarks = [{ ...bookmark('bad', 0), created_at: 0 }, bookmark('good', 4000)];
		installFetch();
		const sync = await loadSync();
		await sync.syncNow();
		expect(pushed.map((op) => op.entity_id)).toEqual(['good']);
	});
});

describe('library sync: pull', () => {
	beforeEach(() => {
		vi.unstubAllGlobals();
		state.applyQueue = [];
		state.removeQueue = [];
		state.applyBookmark = [];
		state.removeBookmark = [];
		state.namedQueues = [];
		state.bookmarks = [];
		state.tombstones = [];
	});

	const cs = (n: number, entity_type: string, entity_id: string, action: string, payload: unknown, ts = 100) => ({
		entity_type, entity_id, action, payload, client_timestamp: ts, server_cursor: n
	});

	it('applies received queues and bookmarks and their deletions', async () => {
		installFetch([
			cs(1, 'named_queue', 'commute', 'upsert', { id: 'q1', name: 'Commute', items: [], updated_at: 500 }, 500),
			cs(2, 'time_bookmark', 'b1', 'upsert', { id: 'b1', episode_id: 'e', position_ms: 5, label: 'x', created_at: 600 }, 600),
			cs(3, 'named_queue', 'work', 'delete', {}, 700),
			cs(4, 'time_bookmark', 'b2', 'delete', {}, 800)
		]);
		const sync = await loadSync();
		await sync.syncNow();

		expect(sync.skippedChangesets).toBe(0);
		expect(state.applyQueue).toEqual([{ id: 'q1', name: 'Commute', items: [], updated_at: 500 }]);
		expect(state.applyBookmark).toEqual([{ id: 'b1', episode_id: 'e', position_ms: 5, label: 'x', created_at: 600 }]);
		expect(state.removeQueue).toEqual([['work', 700]]);
		expect(state.removeBookmark).toEqual(['b2']);
	});

	it('skips an unusable record, counts it and still applies the rest', async () => {
		installFetch([
			cs(1, 'named_queue', 'commute', 'upsert', { name: 'Other name', items: [], updated_at: 5 }),
			cs(2, 'time_bookmark', 'b1', 'upsert', { id: 'someone-else', episode_id: 'e', position_ms: 1, created_at: 2 }),
			cs(3, 'time_bookmark', 'b3', 'upsert', { id: 'b3', episode_id: 'e', position_ms: 1, label: '', created_at: 9 })
		]);
		const sync = await loadSync();
		await sync.syncNow();

		expect(sync.status).toBe('idle');
		expect(sync.skippedChangesets).toBe(2);
		expect(state.applyQueue).toHaveLength(0);
		expect(state.applyBookmark.map((b) => b.id)).toEqual(['b3']);
	});
});
