import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		localStorage.setItem('koalacast_onboarded', '1');
		localStorage.setItem('koalacast_ui_language', 'en');
		localStorage.setItem('koalacast_preferred_languages', JSON.stringify(['en']));
	});
});

for (const route of ['/settings', '/search', '/library?view=queue', '/downloads']) {
	test(`${route} stays usable in a compact viewport`, async ({ page }) => {
		await page.goto(route);
		await expect(page.locator('#main-content')).toBeVisible();
		await expect.poll(() =>
			page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
		).toBe(true);

		const nestedRoute = page.locator('#main-content > main');
		const routeContent = (await nestedRoute.count()) > 0
			? nestedRoute.first()
			: page.locator('#main-content');
		const firstInteractive = routeContent.locator('button, input, a, summary').filter({ visible: true });
		const firstUseful = (await firstInteractive.count()) > 0
			? firstInteractive.first()
			: routeContent.locator('h1').first();
		await expect(firstUseful).toBeVisible();
		const box = await firstUseful.boundingBox();
		expect(box?.y ?? Number.POSITIVE_INFINITY).toBeLessThan(520);
	});
}

test('mobile navigation mirrors the Android destinations', async ({ page }) => {
	await page.goto('/');
	const navigation = page.locator('.quiet-mobile-nav');
	await expect(navigation.getByRole('link')).toHaveCount(4);
	expect((await navigation.getByRole('link').allTextContents()).map((label) => label.trim())).toEqual([
		'Discover',
		'New',
		'Library',
		'Profile'
	]);
	await expect(navigation.getByRole('link', { name: 'Profile' })).toHaveAttribute('href', '/profile');
});

test('legacy more route redirects to profile actions', async ({ page }) => {
	await page.goto('/more');
	await expect(page).toHaveURL(/\/profile$/);
	await expect(page.locator('.profile-actions').getByRole('link')).toHaveCount(4);
});

test('audio controls remain visible without consuming the settings screen', async ({ page }) => {
	await page.goto('/settings#playback');
	const playback = page.locator('#playback');
	await expect(playback).toBeVisible();
	await expect(playback).toHaveAttribute('open', '');
	await expect(page.locator('details.card[open]')).toHaveCount(1);
	await expect(playback.locator('input[type="checkbox"]')).toHaveCount(2);
	await expect(playback.locator('input[type="checkbox"]').first()).toBeVisible();
	const box = await playback.boundingBox();
	expect(box?.width ?? 0).toBeLessThanOrEqual(360);
});

test('all visualizer choices and previews fit the compact playback settings', async ({ page }) => {
	await page.goto('/settings#playback');
	const group = page.getByRole('group', { name: 'Audio visualizer' });
	await expect(group.getByRole('button')).toHaveCount(9);
	await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);

	const constellation = group.getByRole('button', { name: 'Constellation', exact: true });
	await constellation.click();
	await expect(constellation).toHaveAttribute('aria-pressed', 'true');
	const preview = page.locator('.visualizer-preview');
	await expect(preview.locator('[data-visualizer="constellation"]')).toBeVisible();
	const box = await preview.boundingBox();
	const viewportWidth = await page.evaluate(() => window.innerWidth);
	expect(box?.x ?? -1).toBeGreaterThanOrEqual(0);
	expect((box?.x ?? 0) + (box?.width ?? Number.POSITIVE_INFINITY)).toBeLessThanOrEqual(viewportWidth);
});

test('settings start as a compact overview', async ({ page }) => {
	await page.goto('/settings');
	await expect(page.locator('details.card')).toHaveCount(11);
	await expect(page.locator('details.card[open]')).toHaveCount(0);
});

test('personalization content scrolls above its action bar', async ({ page }) => {
	await page.addInitScript(() => localStorage.removeItem('koalacast_onboarded'));
	await page.goto('/');
	const card = page.locator('.ob-card');
	const scroll = page.locator('.ob-scroll');
	const actions = page.locator('.ob-actions');
	await expect(card).toBeVisible();
	const geometry = await page.evaluate(() => {
		const cardBox = document.querySelector('.ob-card')?.getBoundingClientRect();
		const scrollBox = document.querySelector('.ob-scroll')?.getBoundingClientRect();
		const actionBox = document.querySelector('.ob-actions')?.getBoundingClientRect();
		return cardBox && scrollBox && actionBox
			? {
				cardTop: cardBox.top,
				cardBottom: cardBox.bottom,
				scrollBottom: scrollBox.bottom,
				actionTop: actionBox.top,
				viewportHeight: window.innerHeight
			}
			: null;
	});
	expect(geometry).not.toBeNull();
	expect(geometry!.cardTop).toBeGreaterThanOrEqual(0);
	expect(geometry!.cardBottom).toBeLessThanOrEqual(geometry!.viewportHeight);
	expect(geometry!.scrollBottom).toBeLessThanOrEqual(geometry!.actionTop + 1);
});

test('new podcast mode applies to imports and library actions stay circular', async ({ page }) => {
	await page.goto('/settings#playback');
	const latestMode = page.getByRole('button', {
		name: 'Show only the latest episode',
		exact: true
	});
	await latestMode.click();
	await expect(latestMode).toHaveAttribute('aria-pressed', 'true');

	await page.goto('/settings#opml');
	await page.locator('#opml input[type="file"]').setInputFiles({
		name: 'library-layout.opml',
		mimeType: 'text/xml',
		buffer: Buffer.from(
			'<?xml version="1.0"?><opml version="2.0"><body><outline text="Layout Test" xmlUrl="https://example.org/layout-test.xml"/></body></opml>'
		)
	});
	await expect(page.getByText('Successfully imported')).toBeVisible();

	await page.goto('/library');
	const card = page.locator('.quiet-cover-card');
	await expect(card).toHaveCount(1);
	await card.hover();
	const actions = card.locator('.round-action');
	await expect(actions).toHaveCount(3);
	const geometry = await actions.evaluateAll((elements) =>
		elements.map((element) => {
			const box = element.getBoundingClientRect();
			return { width: box.width, height: box.height };
		})
	);
	for (const box of geometry) {
		expect(box.width).toBe(44);
		expect(box.height).toBe(44);
	}

	const inboxMode = await page.evaluate(async () => {
		const request = indexedDB.open('koalacast_local_db');
		const db = await new Promise<IDBDatabase>((resolve, reject) => {
			request.onsuccess = () => resolve(request.result);
			request.onerror = () => reject(request.error);
		});
		const transaction = db.transaction('subscriptions', 'readonly');
		const read = transaction.objectStore('subscriptions').getAll();
		const subscriptions = await new Promise<Array<{ inbox_mode?: string }>>((resolve, reject) => {
			read.onsuccess = () => resolve(read.result);
			read.onerror = () => reject(read.error);
		});
		db.close();
		return subscriptions[0]?.inbox_mode;
	});
	expect(inboxMode).toBe('latest');

	await page.evaluate(() => {
		const nativeFetch = window.fetch.bind(window);
		window.fetch = async (input, init) => {
			const url = String(input instanceof Request ? input.url : input);
			if (url.endsWith('/api/v1/podcasts/feed')) {
				return new Response(JSON.stringify({ id: 'layout-test' }), {
					status: 200,
					headers: { 'Content-Type': 'application/json' }
				});
			}
			if (url.includes('/api/v1/podcasts/layout-test/episodes')) {
				return new Response(JSON.stringify({
					episodes: [{
						id: 'layout-episode',
						podcast_id: 'layout-test',
						title: 'Newest unplayed layout episode',
						enclosure_url: 'https://example.org/layout-episode.mp3',
						duration_ms: 60_000
					}]
				}), {
					status: 200,
					headers: { 'Content-Type': 'application/json' }
				});
			}
			return nativeFetch(input, init);
		};
	});
	await page.getByRole('button', {
		name: 'Play the latest unplayed episode of Layout Test',
		exact: true
	}).click();
	await expect(page.locator('.player-bar .track-title')).toHaveText('Newest unplayed layout episode');
	await expect(page).toHaveURL(/\/library$/);
});

test('library sections remain visible without horizontal scrolling', async ({ page }) => {
	await page.goto('/library');
	const tabs = page.locator('.collection-tabs');
	await expect(tabs.getByRole('tab')).toHaveCount(4);
	await expect.poll(() =>
		tabs.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
	).toBe(true);
});
