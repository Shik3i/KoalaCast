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

test('settings start as a compact overview', async ({ page }) => {
	await page.goto('/settings');
	await expect(page.locator('details.card')).toHaveCount(9);
	await expect(page.locator('details.card[open]')).toHaveCount(0);
});

test('library sections remain visible without horizontal scrolling', async ({ page }) => {
	await page.goto('/library');
	const tabs = page.locator('.collection-tabs');
	await expect(tabs.getByRole('tab')).toHaveCount(4);
	await expect.poll(() =>
		tabs.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
	).toBe(true);
});
