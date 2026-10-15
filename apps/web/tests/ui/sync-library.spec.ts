import { expect, test, type Page } from '@playwright/test';

// Named queues and timestamp bookmarks synchronise through the real IndexedDB
// appliers: received records land, a local deletion outlives a stale remote copy,
// and snapshot recovery replaces only what the server actually sent.

const userId = 'library-sync-user';

test.beforeEach(async ({ page }, testInfo) => {
	test.skip(testInfo.project.name !== 'compact-android', 'one project is enough');
	await page.addInitScript(() => localStorage.setItem('koalacast_onboarded', '1'));
	await page.route('**/api/v1/auth/status', (route) =>
		route.fulfill({ json: { authenticated: true, user_id: userId, username: 'listener', role: 'user' } })
	);
	await page.route('**/api/v1/auth/sessions', (route) => route.fulfill({ json: { sessions: [] } }));
});

const emptyPull = {
	since_cursor: 0, next_cursor: 0, current_cursor: 0, has_more: false, changesets: [], data_generation: 0
};

type Library = {
	queues: { name: string; updated_at: number; items: unknown[] }[];
	bookmarks: { id: string }[];
	tombstones: { id: string; deleted_at: number }[];
};
type Seed = { queues?: unknown[]; bookmarks?: unknown[]; mainQueue?: unknown[]; dropTombstone?: string };

/**
 * Runs one operation against the signed-in account's IndexedDB once the app has
 * created it. The app's CSP forbids eval, so the operations are spelled out here
 * instead of being passed in as source.
 */
function dbOperation(page: Page, op: 'read' | 'seed', seed?: Seed): Promise<Library | null> {
	return page.evaluate(
		async ({ id, operation, data }) => {
			for (let i = 0; i < 100; i++) {
				const names = (await indexedDB.databases()).map((db) => db.name);
				if (names.some((name) => name?.includes(encodeURIComponent(`user:${id}`)))) break;
				await new Promise((resolve) => setTimeout(resolve, 50));
			}
			const name = (await indexedDB.databases())
				.map((db) => db.name)
				.find((value) => value?.includes(encodeURIComponent(`user:${id}`)));
			if (!name) throw new Error('account IndexedDB was not created');
			const db = await new Promise<IDBDatabase>((resolve, reject) => {
				const request = indexedDB.open(name);
				request.onsuccess = () => resolve(request.result);
				request.onerror = () => reject(request.error);
			});
			try {
				const read = (store: string) =>
					new Promise<any[]>((resolve, reject) => {
						const request = db.transaction(store).objectStore(store).getAll();
						request.onsuccess = () => resolve(request.result);
						request.onerror = () => reject(request.error);
					});
				if (operation === 'seed') {
					await new Promise<void>((resolve, reject) => {
						const tx = db.transaction(['named_queues', 'time_bookmarks', 'tombstones', 'queue'], 'readwrite');
						for (const queue of data?.queues ?? []) tx.objectStore('named_queues').put(queue);
						for (const bookmark of data?.bookmarks ?? []) tx.objectStore('time_bookmarks').put(bookmark);
						for (const item of data?.mainQueue ?? []) tx.objectStore('queue').put(item);
						if (data?.dropTombstone) tx.objectStore('tombstones').delete(data.dropTombstone);
						tx.oncomplete = () => resolve();
						tx.onerror = () => reject(tx.error);
					});
					return null;
				}
				return {
					queues: await read('named_queues'),
					bookmarks: await read('time_bookmarks'),
					tombstones: await read('tombstones')
				};
			} finally {
				db.close();
			}
		},
		{ id: userId, operation: op, data: seed }
	);
}

const readLibrary = async (page: Page) => (await dbOperation(page, 'read')) as Library;
const seedLibrary = (page: Page, seed: Seed) => dbOperation(page, 'seed', seed);

const queueRecord = (name: string, updated_at: number) => ({
	id: `local-${name}`, name, updated_at, items: [{ episode_id: 'e1', podcast_id: 'p', title: 'T', podcast_title: 'P', enclosure_url: 'https://x.test/a.mp3', position_order: 0 }]
});
const bookmarkRecord = (id: string, created_at: number) => ({
	id, episode_id: 'ep-1', position_ms: 1000, label: id, created_at
});

test('received queues and bookmarks are stored, and their deletions applied', async ({ page }) => {
	await page.route('**/api/v1/sync*', (route) => {
		if (route.request().method() === 'POST') return route.fulfill({ json: { applied_ops: 0 } });
		return route.fulfill({
			json: {
				...emptyPull,
				next_cursor: 4,
				changesets: [
					{ entity_type: 'named_queue', entity_id: 'commute', action: 'upsert', client_timestamp: 500, server_cursor: 1,
						payload: { id: 'remote-1', name: 'Commute', items: [{ episode_id: 'e9' }], updated_at: 500 } },
					{ entity_type: 'time_bookmark', entity_id: 'b1', action: 'upsert', client_timestamp: 600, server_cursor: 2,
						payload: { id: 'b1', episode_id: 'ep-1', position_ms: 5000, label: 'keep', created_at: 600 } },
					{ entity_type: 'time_bookmark', entity_id: 'b2', action: 'upsert', client_timestamp: 610, server_cursor: 3,
						payload: { id: 'b2', episode_id: 'ep-1', position_ms: 6000, label: 'gone', created_at: 610 } },
					{ entity_type: 'time_bookmark', entity_id: 'b2', action: 'delete', client_timestamp: 700, server_cursor: 4, payload: {} }
				]
			}
		});
	});
	await page.goto('/account');
	await expect.poll(async () => (await readLibrary(page)).bookmarks.length).toBe(1);
	const library = await readLibrary(page);
	expect(library.bookmarks.map((b) => b.id)).toEqual(['b1']);
	expect(library.queues).toHaveLength(1);
	expect(library.queues[0]).toMatchObject({ name: 'Commute', updated_at: 500 });
	// A received record is not a local edit: nothing to echo back.
	expect(library.tombstones).toHaveLength(0);

	await page.goto('/library?view=queue');
	await expect(page.locator('.named-queue-row').getByText('Commute')).toBeVisible();
});

test('a newer local version of a queue survives an older remote one', async ({ page }) => {
	// The first sync (on page load) runs before the library is seeded, so the older
	// remote copy is only served once the local one exists.
	let armed = false;
	await page.route('**/api/v1/sync*', (route) =>
		route.request().method() === 'POST'
			? route.fulfill({ json: { applied_ops: 0 } })
			: route.fulfill({
					json: armed
						? {
								...emptyPull, next_cursor: 1,
								changesets: [{ entity_type: 'named_queue', entity_id: 'work', action: 'upsert', client_timestamp: 100, server_cursor: 1,
									payload: { id: 'r', name: 'WORK', items: [], updated_at: 100 } }]
							}
						: emptyPull
				})
	);
	await page.goto('/account');
	await expect(page.getByText('listener', { exact: true })).toBeVisible();
	await seedLibrary(page, { queues: [queueRecord('Work', 9000)] });
	// Leave the page first: a sync still running in the old one would otherwise
	// consume the armed response and be torn down mid-transaction by the reload.
	await page.goto('about:blank');
	armed = true;
	await page.goto('/account');
	await expect(page.getByText('listener', { exact: true })).toBeVisible();
	await expect.poll(() => page.evaluate((id) => localStorage.getItem(`koalacast_sync_cursor_${id}`), userId)).toBe('1');
	const { queues } = await readLibrary(page);
	expect(queues).toHaveLength(1);
	expect(queues[0]).toMatchObject({ name: 'Work', updated_at: 9000 });
});

test('a queue deleted here is pushed as a delete and a stale remote copy does not bring it back', async ({ page }) => {
	const pushed: { entity_type: string; entity_id: string; action: string }[] = [];
	let staleRemoteCopy = false;
	await page.route('**/api/v1/sync*', (route) => {
		if (route.request().method() === 'POST') {
			pushed.push(...(route.request().postDataJSON().operations as typeof pushed));
			return route.fulfill({ json: { applied_ops: 1 } });
		}
		return route.fulfill({
			json: staleRemoteCopy
				? { ...emptyPull, next_cursor: 1, changesets: [{ entity_type: 'named_queue', entity_id: 'work', action: 'upsert', client_timestamp: 1000, server_cursor: 1,
						payload: { id: 'r', name: 'Work', items: [], updated_at: 1000 } }] }
				: emptyPull
		});
	});
	await page.goto('/account');
	// Pretend this queue was already uploaded, so only the deletion is new.
	await page.evaluate((id) => localStorage.setItem(`koalacast_sync_push_watermarks_v2_${id}`,
		JSON.stringify({ named_queue: 2000 })), userId);
	await seedLibrary(page, { queues: [queueRecord('Work', 1000)] });

	await page.goto('/library?view=queue');
	await page.locator('.named-queue-delete').click();
	await expect(page.locator('.named-queue-row')).toHaveCount(0);
	const afterDelete = await readLibrary(page);
	expect(afterDelete.queues).toHaveLength(0);
	expect(afterDelete.tombstones.map((t) => t.id)).toContain('named_queue:work');

	staleRemoteCopy = true;
	await page.reload();
	await expect.poll(() => pushed.some((op) => op.entity_type === 'named_queue' && op.action === 'delete')).toBe(true);
	expect(pushed.find((op) => op.entity_type === 'named_queue')).toMatchObject({ entity_id: 'work', action: 'delete' });
	expect((await readLibrary(page)).queues).toHaveLength(0);
});

test('saving a queue again after deleting it clears the deletion', async ({ page }) => {
	await page.route('**/api/v1/sync*', (route) =>
		route.request().method() === 'POST' ? route.fulfill({ json: { applied_ops: 0 } }) : route.fulfill({ json: emptyPull })
	);
	await page.goto('/account');
	await seedLibrary(page, {
		queues: [queueRecord('Work', 1000)],
		mainQueue: [{
			id: 'qi-1', episode_id: 'e1', podcast_id: 'p', title: 'An episode', podcast_title: 'A show',
			artwork_url: '', enclosure_url: 'https://x.test/a.mp3', duration_ms: 1000, position_order: 0, added_at: 1
		}]
	});
	await page.goto('/library?view=queue');
	await page.locator('.named-queue-delete').click();
	await expect.poll(async () => (await readLibrary(page)).tombstones.map((t) => t.id)).toEqual(['named_queue:work']);

	// Save the current queue under the same name, in different case.
	await page.locator('.named-queue-save input').fill('WORK');
	await page.locator('.named-queue-save button').click();
	await expect(page.locator('.named-queue-row').getByText('WORK')).toBeVisible();
	const library = await readLibrary(page);
	expect(library.tombstones).toHaveLength(0);
	expect(library.queues).toHaveLength(1);
});

test('snapshot recovery replaces what the server sent and keeps unsent local edits', async ({ page }) => {
	let armed = false;
	let recovered = false;
	await page.route('**/api/v1/sync/snapshot', (route) => {
		recovered = true;
		return route.fulfill({
			json: {
				cursor: 5, data_generation: 0, subscriptions: [], favorites: [], playback_states: [], listening_sessions: [],
				queue: [], podcast_settings: [], settings: [],
				named_queues: [{ id: 'srv-1', name: 'Weekend', items: [], updated_at: 4000 }],
				time_bookmarks: [{ id: 'srv-bm', episode_id: 'ep-1', position_ms: 9, label: 'srv', created_at: 4100 }]
			}
		});
	});
	await page.route('**/api/v1/sync?*', (route) => {
		if (!armed) return route.fulfill({ json: emptyPull });
		return recovered
			? route.fulfill({ json: { ...emptyPull, next_cursor: 5, current_cursor: 5 } })
			: route.fulfill({ status: 410, json: { code: 'FULL_RESYNC_REQUIRED', error: 'compacted' } });
	});
	await page.route('**/api/v1/sync', (route) => route.fulfill({ json: { applied_ops: 0 } }));

	await page.goto('/account');
	await expect(page.getByText('listener', { exact: true })).toBeVisible();
	await page.evaluate((id) => {
		localStorage.setItem(`koalacast_sync_cursor_${id}`, '1');
		localStorage.setItem(`koalacast_sync_push_watermarks_v2_${id}`, JSON.stringify({ named_queue: 5000, time_bookmark: 5000 }));
	}, userId);
	await seedLibrary(page, {
		queues: [queueRecord('Already pushed', 1000), queueRecord('Unsent edit', 9000)],
		bookmarks: [bookmarkRecord('old-synced', 1200), bookmarkRecord('unsent-bm', 9100)]
	});
	// Leave the page first: a sync still running in the old one would otherwise
	// consume the armed response and be torn down mid-transaction by the reload.
	await page.goto('about:blank');
	armed = true;
	await page.goto('/account');

	await expect.poll(() => recovered).toBe(true);
	await expect.poll(async () => (await readLibrary(page)).queues.map((q) => q.name).sort())
		.toEqual(['Unsent edit', 'Weekend']);
	const library = await readLibrary(page);
	expect(library.bookmarks.map((b) => b.id).sort()).toEqual(['srv-bm', 'unsent-bm']);
});

test('a server that predates library sync leaves local queues and bookmarks alone', async ({ page }) => {
	let armed = false;
	let recovered = false;
	await page.route('**/api/v1/sync/snapshot', (route) => {
		recovered = true;
		return route.fulfill({
			json: { cursor: 5, data_generation: 0, subscriptions: [], favorites: [], playback_states: [],
				listening_sessions: [], queue: [], podcast_settings: [], settings: [] }
		});
	});
	await page.route('**/api/v1/sync?*', (route) => {
		if (!armed) return route.fulfill({ json: emptyPull });
		return recovered
			? route.fulfill({ json: { ...emptyPull, next_cursor: 5, current_cursor: 5 } })
			: route.fulfill({ status: 410, json: { code: 'FULL_RESYNC_REQUIRED', error: 'compacted' } });
	});
	await page.route('**/api/v1/sync', (route) => route.fulfill({ json: { applied_ops: 0 } }));

	await page.goto('/account');
	await expect(page.getByText('listener', { exact: true })).toBeVisible();
	await page.evaluate((id) => {
		localStorage.setItem(`koalacast_sync_cursor_${id}`, '1');
		// Both records count as already uploaded, so only the snapshot logic decides
		// whether they stay; "unsent" records would survive any snapshot.
		localStorage.setItem(`koalacast_sync_push_watermarks_v2_${id}`, JSON.stringify({ named_queue: 5000, time_bookmark: 5000 }));
	}, userId);
	await seedLibrary(page, { queues: [queueRecord('Mine', 1000)], bookmarks: [bookmarkRecord('mine', 1200)] });
	// Leave the page first: a sync still running in the old one would otherwise
	// consume the armed response and be torn down mid-transaction by the reload.
	await page.goto('about:blank');
	armed = true;
	await page.goto('/account');
	await expect.poll(() => recovered).toBe(true);
	await expect.poll(() => page.evaluate((id) => localStorage.getItem(`koalacast_sync_cursor_${id}`), userId)).toBe('5');
	const library = await readLibrary(page);
	expect(library.queues.map((q) => q.name)).toEqual(['Mine']);
	expect(library.bookmarks.map((b) => b.id)).toEqual(['mine']);
});
