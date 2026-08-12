import { expect, test } from '@playwright/test';

test('most played shows reuse artwork from the authenticated local library cache', async ({ page }) => {
	const userId = 'profile-artwork-user';
	const artworkUrl = 'https://images.example.test/cached-cover.jpg';

	await page.addInitScript(() => localStorage.setItem('koalacast_onboarded', '1'));
	await page.route('**/api/v1/auth/status', (route) => route.fulfill({
		json: { authenticated: true, user_id: userId, username: 'listener', role: 'user' }
	}));
	await page.route('**/api/v1/auth/sessions', (route) => route.fulfill({ json: { sessions: [] } }));
	await page.route('**/api/v1/sync*', (route) => route.fulfill({
		json: {
			since_cursor: 0, next_cursor: 0, current_cursor: 0, has_more: false,
			changesets: [], data_generation: 0, applied_ops: 0
		}
	}));
	await page.route('**/api/v1/proxy/image*', (route) => route.fulfill({
		contentType: 'image/svg+xml',
		body: '<svg xmlns="http://www.w3.org/2000/svg" width="220" height="220"><rect width="220" height="220" fill="#4a8"/></svg>'
	}));

	await page.goto('/account');
	await expect(page.getByText('listener', { exact: true })).toBeVisible();
	await page.evaluate(async ({ id, cover }) => {
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
		const now = Date.now();
		await new Promise<void>((resolve, reject) => {
			const tx = db.transaction(['subscriptions', 'listening_sessions', 'playback_states'], 'readwrite');
			tx.objectStore('subscriptions').put({
				podcast_id: 'cached-show', feed_url: 'https://example.test/feed.xml',
				title: 'Cached Koala Show', artwork_url: cover, added_at: now, updated_at: now
			});
			tx.objectStore('listening_sessions').put({
				id: 'session-1', episode_id: 'episode-1', podcast_id: 'cached-show',
				title: 'Cached episode', podcast_title: 'Cached Koala Show',
				started_at: now - 60_000, ended_at: now, wall_clock_ms: 60_000,
				audio_listened_ms: 60_000, speed_saved_ms: 0, silence_saved_ms: 0,
				manual_skipped_ms: 0, intro_outro_skipped_ms: 0, speed_weighted_ms: 60_000
			});
			tx.objectStore('playback_states').put({
				episode_id: 'episode-1', podcast_id: 'cached-show', position_ms: 60_000,
				completed: true, progress_percent: 100, last_played_at: now,
				title: 'Cached episode', podcast_title: 'Cached Koala Show', artwork_url: cover,
				enclosure_url: 'https://example.test/episode.mp3', duration_ms: 60_000,
				event_type: 'MARK_PLAYED', playback_session_id: 'session-1', per_session_seq: 1
			});
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
		});
		db.close();
	}, { id: userId, cover: artworkUrl });

	await page.goto('/profile');
	const cover = page.locator('.ranking-list .ranking-cover').first();
	await expect(cover).toBeVisible();
	await expect(cover).toHaveAttribute('src', new RegExp(`url=${encodeURIComponent(artworkUrl)}.*w=220`));
	const history = page.locator('.history-section');
	await expect(history.getByRole('heading', { name: 'Recently listened' })).toBeVisible();
	await expect(history.getByRole('link', { name: /Cached episode/ })).toHaveAttribute('href', '/episode/episode-1');
	await expect(history.locator('img')).toHaveAttribute('src', new RegExp(`url=${encodeURIComponent(artworkUrl)}.*w=220`));
	await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});
