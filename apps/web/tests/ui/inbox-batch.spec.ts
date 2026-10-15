import { expect, test, type Page, type Route } from '@playwright/test';

// The Inbox refreshes stale feeds with one batched request and falls back to the
// per-feed route for anything the batch does not deliver.
test.beforeEach(async ({ page }, testInfo) => {
	test.skip(testInfo.project.name !== 'compact-android', 'one project is enough');
	await page.addInitScript(() => localStorage.setItem('koalacast_onboarded', '1'));
	await page.route('**/api/v1/auth/status', (route) => route.fulfill({ json: { authenticated: false } }));
});

const episode = (podcast: string, n: number, pub: number) => ({
	id: `${podcast}-ep${n}`,
	podcast_id: podcast,
	title: `${podcast.toUpperCase()} episode ${n}`,
	description: 'About this episode',
	pub_date: pub,
	duration_ms: 60_000,
	enclosure_url: `https://cdn.example.test/${podcast}-${n}.mp3`,
	artwork_url: ''
});

async function seedGuestSubscriptions(page: Page, ids: string[]) {
	// Visiting any page creates the guest database; then add the subscriptions.
	await page.goto('/settings');
	await page.evaluate(async (podcastIds) => {
		// Opening before the app created its database would create an empty one.
		for (let i = 0; i < 100; i++) {
			if ((await indexedDB.databases()).some((database) => database.name === 'koalacast_local_db')) break;
			await new Promise((resolve) => setTimeout(resolve, 50));
		}
		const db = await new Promise<IDBDatabase>((resolve, reject) => {
			const request = indexedDB.open('koalacast_local_db');
			request.onsuccess = () => resolve(request.result);
			request.onerror = () => reject(request.error);
		});
		const now = Date.now();
		await new Promise<void>((resolve, reject) => {
			const tx = db.transaction(['subscriptions'], 'readwrite');
			for (const id of podcastIds) {
				tx.objectStore('subscriptions').put({
					podcast_id: id, feed_url: `https://example.test/${id}.xml`,
					title: `Show ${id.toUpperCase()}`, artwork_url: '', added_at: now, updated_at: now
				});
			}
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
		});
		db.close();
	}, ids);
}

function countRoute(counter: { n: number }, handler: (route: Route) => Promise<void> | void) {
	return async (route: Route) => {
		counter.n += 1;
		await handler(route);
	};
}

test('stale feeds are refreshed with a single batched request', async ({ page }) => {
	const batch = { n: 0 };
	const perFeed = { n: 0 };
	let batchBody: { feeds: { podcast_id: string; since: number }[] } | null = null;
	await page.route('**/api/v1/inbox/episodes', countRoute(batch, async (route) => {
		batchBody = route.request().postDataJSON();
		await route.fulfill({
			json: {
				feeds: [
					{ podcast_id: 'a', found: true, episodes: [episode('a', 1, 2000)] },
					{ podcast_id: 'b', found: true, episodes: [episode('b', 1, 1000)] }
				]
			}
		});
	}));
	await page.route('**/api/v1/podcasts/*/episodes*', countRoute(perFeed, (route) => route.fulfill({ json: { episodes: [] } })));
	await seedGuestSubscriptions(page, ['a', 'b']);

	await page.goto('/inbox');
	await expect(page.getByText('A episode 1').first()).toBeVisible();
	await expect(page.getByText('B episode 1').first()).toBeVisible();
	expect(batch.n).toBe(1);
	expect(perFeed.n).toBe(0);
	expect(batchBody!.feeds.map((feed) => feed.podcast_id).sort()).toEqual(['a', 'b']);
	expect(batchBody!.feeds.every((feed) => feed.since === 0)).toBe(true);
});

test('a failing batch falls back to the per-feed route for every feed', async ({ page }) => {
	const perFeed: string[] = [];
	await page.route('**/api/v1/inbox/episodes', (route) => route.fulfill({ status: 503, json: { error: 'down' } }));
	await page.route('**/api/v1/podcasts/*/episodes*', async (route) => {
		const id = new URL(route.request().url()).pathname.split('/')[4];
		perFeed.push(id);
		await route.fulfill({ json: { episodes: [episode(id, 1, id === 'a' ? 2000 : 1000)] } });
	});
	await seedGuestSubscriptions(page, ['a', 'b']);

	await page.goto('/inbox');
	await expect(page.getByText('A episode 1').first()).toBeVisible();
	await expect(page.getByText('B episode 1').first()).toBeVisible();
	expect(perFeed.sort()).toEqual(['a', 'b']);
});

test('only the feed the batch does not know is fetched through the per-feed route', async ({ page }) => {
	const perFeed: string[] = [];
	await page.route('**/api/v1/inbox/episodes', (route) =>
		route.fulfill({
			json: {
				feeds: [
					{ podcast_id: 'a', found: true, episodes: [episode('a', 1, 2000)] },
					{ podcast_id: 'b', found: false, episodes: [] }
				]
			}
		})
	);
	await page.route('**/api/v1/podcasts/*/episodes*', async (route) => {
		const id = new URL(route.request().url()).pathname.split('/')[4];
		perFeed.push(id);
		await route.fulfill({ json: { episodes: [episode(id, 1, 1000)] } });
	});
	await seedGuestSubscriptions(page, ['a', 'b']);

	await page.goto('/inbox');
	await expect(page.getByText('A episode 1').first()).toBeVisible();
	await expect(page.getByText('B episode 1').first()).toBeVisible();
	expect(perFeed).toEqual(['b']);
});

test('a single stale feed keeps using the cacheable per-feed route', async ({ page }) => {
	const batch = { n: 0 };
	await page.route('**/api/v1/inbox/episodes', countRoute(batch, (route) => route.fulfill({ json: { feeds: [] } })));
	await page.route('**/api/v1/podcasts/*/episodes*', (route) => route.fulfill({ json: { episodes: [episode('a', 1, 2000)] } }));
	await seedGuestSubscriptions(page, ['a']);

	await page.goto('/inbox');
	await expect(page.getByText('A episode 1').first()).toBeVisible();
	expect(batch.n).toBe(0);
});
