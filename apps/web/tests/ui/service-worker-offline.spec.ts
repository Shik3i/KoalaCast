import { expect, test } from '@playwright/test';

// The rest of the suite blocks service workers so page.route() fixtures see every
// request. This spec is the one place that exercises the real worker: it guards the
// precache list (absolute paths, as `url.pathname` comparisons need) and the offline
// app shell, both of which broke silently when `$service-worker` changed shape.
test.use({ serviceWorkers: 'allow' });

test('the service worker precaches the shell with absolute paths and serves it offline', async ({
	page,
	context
}, testInfo) => {
	test.skip(testInfo.project.name !== 'compact-android', 'one project is enough');

	await page.route('**/api/**', (route) => route.fulfill({ status: 503, body: '{}' }));
	await page.goto('/settings');

	await page.evaluate(async () => {
		const registration = await navigator.serviceWorker.ready;
		if (registration.active?.state !== 'activated') {
			await new Promise<void>((resolve) => {
				registration.active?.addEventListener('statechange', () => {
					if (registration.active?.state === 'activated') resolve();
				});
			});
		}
	});

	const cached = await page.evaluate(async () => {
		const urls: string[] = [];
		for (const name of await caches.keys()) {
			for (const request of await (await caches.open(name)).keys()) {
				urls.push(new URL(request.url).pathname);
			}
		}
		return urls;
	});
	expect(cached.some((path) => path.startsWith('/_app/immutable/'))).toBe(true);
	expect(cached).toContain('/favicon.ico');
	expect(cached).toContain('/manifest.webmanifest');
	expect(cached.every((path) => path.startsWith('/'))).toBe(true);

	// A reload hands the page to the now-active worker; then cut the network.
	await page.reload();
	await page.evaluate(() => navigator.serviceWorker.ready);
	await context.setOffline(true);
	await page.reload();

	await expect(page.locator('nav').first()).toBeAttached();
	await expect(page.locator('main, [role="main"]').first()).toBeAttached();
	// The mocked /api 503s are intentional; every other resource must come from the cache.
	const failedAssets = await page.evaluate(
		() =>
			performance
				.getEntriesByType('resource')
				.filter((e) => !e.name.includes('/api/') && (e as PerformanceResourceTiming).responseStatus >= 400)
				.map((e) => e.name)
	);
	expect(failedAssets).toEqual([]);
});
