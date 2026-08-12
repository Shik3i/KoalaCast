import { expect, test } from '@playwright/test';

test('hard reload waits for the authenticated account cache instead of rendering the guest library', async ({ page }) => {
	const userId = 'reload-library-user';
	let delayAuthStatus = false;

	await page.addInitScript(() => localStorage.setItem('koalacast_onboarded', '1'));
	await page.route('**/api/v1/auth/status', async (route) => {
		if (delayAuthStatus) await new Promise((resolve) => setTimeout(resolve, 700));
		await route.fulfill({
			json: { authenticated: true, user_id: userId, username: 'listener', role: 'user' }
		});
	});
	await page.route('**/api/v1/auth/sessions', (route) =>
		route.fulfill({ json: { sessions: [] } })
	);
	await page.route('**/api/v1/sync*', (route) =>
		route.fulfill({
			json: {
				since_cursor: 0,
				next_cursor: 0,
				current_cursor: 0,
				has_more: false,
				changesets: [],
				data_generation: 0,
				applied_ops: 0
			}
		})
	);

	await page.goto('/account');
	await expect(page.getByText('listener', { exact: true })).toBeVisible();
	await page.evaluate(async ({ id }) => {
		const databases = await indexedDB.databases();
		const name = databases
			.map((database) => database.name)
			.find((value) => value?.includes(encodeURIComponent(`user:${id}`)));
		if (!name) throw new Error('account IndexedDB was not created');
		const db = await new Promise<IDBDatabase>((resolve, reject) => {
			const request = indexedDB.open(name);
			request.onsuccess = () => resolve(request.result);
			request.onerror = () => reject(request.error);
		});
		await new Promise<void>((resolve, reject) => {
			const tx = db.transaction('subscriptions', 'readwrite');
			tx.objectStore('subscriptions').put({
				podcast_id: 'cached-show',
				feed_url: 'https://example.test/feed.xml',
				title: 'Cached Koala Show',
				artwork_url: '',
				added_at: 1,
				updated_at: 1
			});
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
		});
		db.close();
	}, { id: userId });

	delayAuthStatus = true;
	await page.goto('/library');
	await expect(page.getByRole('status').getByText(/Loading|Wird geladen/)).toBeVisible();
	await expect(page.getByText(/You haven't subscribed|Du hast noch keine Podcasts/)).toBeHidden();
	await expect(page.getByRole('heading', { name: 'Cached Koala Show', exact: true })).toBeVisible();
	await expect(page.getByText(/You haven't subscribed|Du hast noch keine Podcasts/)).toBeHidden();
});
