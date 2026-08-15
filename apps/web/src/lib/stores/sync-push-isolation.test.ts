import { beforeEach, describe, expect, it, vi } from 'vitest';

// The sync store reaches into IndexedDB, the podcast-settings store and the
// player's own state. None of that is what this file is about: it is about what
// the push loop does when the server refuses one record, so everything else is
// stubbed down to "there is exactly one favourite to send".
const favorite = {
	episode_id: 'ep-bad',
	added_at: 1_000,
	podcast_id: 'pod-1',
	title: 'Episode'
};
const secondFavorite = {
	episode_id: 'ep-good',
	added_at: 1_001,
	podcast_id: 'pod-1',
	title: 'Another episode'
};

vi.mock('$lib/idb/db', () => ({
	getLocalSubscriptions: async () => [],
	getLocalFavorites: async () => [favorite, secondFavorite],
	getAllLocalPlaybackStates: async () => [],
	getLocalListeningSessions: async () => [],
	getLocalListeningSession: async () => undefined,
	getLocalQueue: async () => [],
	getLocalQueueUpdatedAt: async () => 0,
	getTombstones: async () => [],
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

vi.mock('$lib/stores/podcast-settings', () => ({
	applySyncedPodcastPlaybackSettings: () => {},
	clearPodcastPlaybackSettingsContext: () => {},
	getAllPodcastPlaybackSettings: () => [],
	removePodcastPlaybackSettings: () => {}
}));

vi.mock('$lib/stores/prefs.svelte', () => ({
	prefs: {
		updatedAt: 0,
		syncPayload: () => ({ updated_at: 0 }),
		applySynced: () => {},
		resetSynced: () => {}
	}
}));

class MemoryStorage {
	#values = new Map<string, string>();
	getItem(key: string) {
		return this.#values.get(key) ?? null;
	}
	setItem(key: string, value: string) {
		this.#values.set(key, value);
	}
	removeItem(key: string) {
		this.#values.delete(key);
	}
}

interface PushBody {
	operations: { entity_id: string }[];
}

/** Every push body the store sent, in order. */
let pushes: PushBody[] = [];

function installFetch(rejectedEntityId: string | null) {
	pushes = [];
	vi.stubGlobal('fetch', async (input: string, init?: RequestInit) => {
		if (!init || init.method !== 'POST') {
			return new Response(
				JSON.stringify({ changesets: [], next_cursor: 0, has_more: false, data_generation: 0 }),
				{ status: 200, headers: { 'Content-Type': 'application/json' } }
			);
		}
		const body = JSON.parse(String(init.body)) as PushBody;
		pushes.push(body);
		const offender = body.operations.find((op) => op.entity_id === rejectedEntityId);
		if (offender) {
			return new Response(
				JSON.stringify({ error: 'invalid favorite payload', operation_index: 0 }),
				{ status: 400, headers: { 'Content-Type': 'application/json' } }
			);
		}
		return new Response(JSON.stringify({ applied_ops: body.operations.length }), { status: 200 });
	});
}

async function loadSync() {
	vi.stubGlobal('localStorage', new MemoryStorage());
	vi.stubGlobal('document', { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} });
	vi.resetModules();
	const module = await import('./sync.svelte');
	return module.sync;
}

describe('sync push isolation', () => {
	beforeEach(() => {
		vi.unstubAllGlobals();
	});

	it('sends everything when the server accepts the batch', async () => {
		installFetch(null);
		const sync = await loadSync();
		sync.userId = 'user-1';
		await sync.syncNow();

		expect(sync.status).toBe('idle');
		expect(sync.rejectedOperations).toBe(0);
		expect(pushes).toHaveLength(1);
		expect(pushes[0].operations.map((op) => op.entity_id).sort()).toEqual(['ep-bad', 'ep-good']);
	});

	it('isolates the one record the server refuses and still ships the rest', async () => {
		installFetch('ep-bad');
		const sync = await loadSync();
		sync.userId = 'user-1';
		await sync.syncNow();

		// A 400 used to abort the run, leaving the watermark where it was so the
		// same batch went back up every 45 seconds forever.
		expect(sync.status).toBe('idle');
		expect(sync.rejectedOperations).toBe(1);
		expect(sync.lastError).toContain('1 record(s) rejected');

		const accepted = pushes.filter(
			(body) =>
				body.operations.length === 1 && body.operations[0].entity_id === 'ep-good'
		);
		expect(accepted).toHaveLength(1);

		// And the run must not repeat the rejected record on the next pass.
		const before = pushes.length;
		await sync.syncNow();
		expect(pushes.length).toBe(before);
	});

	it('keeps failing the run on a server error so it retries later', async () => {
		vi.stubGlobal('fetch', async (input: string, init?: RequestInit) => {
			if (!init || init.method !== 'POST') {
				return new Response(
					JSON.stringify({ changesets: [], next_cursor: 0, has_more: false, data_generation: 0 }),
					{ status: 200, headers: { 'Content-Type': 'application/json' } }
				);
			}
			return new Response('{}', { status: 503 });
		});
		const sync = await loadSync();
		sync.userId = 'user-1';
		await sync.syncNow();

		expect(sync.status).toBe('error');
		expect(sync.rejectedOperations).toBe(0);
	});
});
