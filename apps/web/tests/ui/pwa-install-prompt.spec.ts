import { expect, test } from '@playwright/test';

test('KoalaCast does not suppress the browser-owned install promotion', async ({ page }) => {
	await page.goto('/settings');
	const defaultPrevented = await page.evaluate(() => {
		const event = new Event('beforeinstallprompt', { cancelable: true });
		window.dispatchEvent(event);
		return event.defaultPrevented;
	});
	expect(defaultPrevented).toBe(false);
});
