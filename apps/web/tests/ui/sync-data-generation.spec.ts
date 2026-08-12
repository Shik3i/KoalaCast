import { expect, test } from '@playwright/test';

test('a second web client wipes its stale local copy before any push', async ({ page }) => {
	const userId = 'generation-reset-user';
	let serverGeneration = 0;
	let postResetAcceptedPushCount = 0;
	let stalePushCount = 0;
	await page.addInitScript(() => localStorage.setItem('koalacast_onboarded', '1'));
	await page.route('**/api/v1/auth/status', (route) => route.fulfill({
		json: { authenticated: true, user_id: userId, username: 'listener', role: 'user' }
	}));
	await page.route('**/api/v1/auth/sessions', (route) => route.fulfill({ json: { sessions: [] } }));
	await page.route('**/api/v1/sync*', async (route) => {
		if (route.request().method() === 'POST') {
			const body = route.request().postDataJSON() as { data_generation?: number };
			if (body.data_generation !== serverGeneration) {
				stalePushCount++;
				return route.fulfill({
					status: 409,
					json: {
						code: 'DATA_GENERATION_MISMATCH',
						message: 'client data generation is stale',
						data_generation: serverGeneration
					}
				});
			}
			if (serverGeneration > 0) postResetAcceptedPushCount++;
			return route.fulfill({ json: { applied_ops: 0, current_cursor: 0, data_generation: serverGeneration } });
		}
		return route.fulfill({
			json: {
				since_cursor: 0,
				next_cursor: 0,
				current_cursor: 0,
				has_more: false,
				changesets: [],
				data_generation: serverGeneration
			}
		});
	});

	await page.goto('/account');
	await expect(page.getByText('listener', { exact: true })).toBeVisible();
	await expect.poll(() => page.evaluate(async (id) =>
		(await indexedDB.databases()).some((db) => db.name?.includes(encodeURIComponent(`user:${id}`))), userId
	)).toBe(true);
	await page.evaluate(async ({ id }) => {
		const databases = await indexedDB.databases();
		const name = databases.map((db) => db.name).find((value) => value?.includes(encodeURIComponent(`user:${id}`)));
		if (!name) throw new Error('account IndexedDB was not created');
		const db = await new Promise<IDBDatabase>((resolve, reject) => {
			const request = indexedDB.open(name);
			request.onsuccess = () => resolve(request.result);
			request.onerror = () => reject(request.error);
		});
		await new Promise<void>((resolve, reject) => {
			const tx = db.transaction('subscriptions', 'readwrite');
			tx.objectStore('subscriptions').put({
				podcast_id: 'deleted-show', feed_url: 'https://example.test/feed.xml',
				title: 'Deleted show', artwork_url: '', added_at: 1, updated_at: 1
			});
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
		});
		db.close();
		localStorage.setItem(`koalacast_sync_cursor_${id}`, '99');
		localStorage.setItem(`koalacast_data_generation_${id}`, '0');
		localStorage.setItem('koalacast_theme', 'light');
		localStorage.setItem('koalacast_palette', 'ember');
	}, { id: userId });

	serverGeneration = 1;
	await page.reload();
	await expect.poll(() => page.evaluate((id) => localStorage.getItem(`koalacast_data_generation_${id}`), userId)).toBe('1');
	const localRows = await page.evaluate(async ({ id }) => {
		const databases = await indexedDB.databases();
		const name = databases.map((db) => db.name).find((value) => value?.includes(encodeURIComponent(`user:${id}`)));
		if (!name) return -1;
		const db = await new Promise<IDBDatabase>((resolve, reject) => {
			const request = indexedDB.open(name);
			request.onsuccess = () => resolve(request.result);
			request.onerror = () => reject(request.error);
		});
		const count = await new Promise<number>((resolve, reject) => {
			const request = db.transaction('subscriptions').objectStore('subscriptions').count();
			request.onsuccess = () => resolve(request.result);
			request.onerror = () => reject(request.error);
		});
		db.close();
		return count;
	}, { id: userId });
	expect(localRows).toBe(0);
	await expect.poll(() => page.evaluate(() => localStorage.getItem('koalacast_theme'))).toBe('system');
	await expect.poll(() => page.evaluate(() => localStorage.getItem('koalacast_palette'))).toBe('fjord');
	// A request already in flight when the reset commits can still arrive, but
	// the server must reject its old generation. Once the reset is adopted, no
	// old row may be accepted with the new generation.
	expect(postResetAcceptedPushCount).toBe(0);
	expect(stalePushCount).toBeLessThanOrEqual(1);
});
