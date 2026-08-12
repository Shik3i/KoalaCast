import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		localStorage.setItem('koalacast_onboarded', '1');
		localStorage.setItem('koalacast_ui_language', 'en');
	});
	await page.route('**/api/v1/auth/status', async (route) => {
		await route.fulfill({ json: { authenticated: false } });
	});
});

for (const route of ['/downloads', '/profile']) {
	test(`selected ${route} navigation item keeps its icon`, async ({ page }) => {
		await page.goto(route);

		const activeLink = page.locator(`.quiet-nav a[href="${route}"][aria-current="page"]`);
		const icon = activeLink.locator('i');
		await expect(activeLink).toBeVisible();
		await expect(icon).toBeVisible();

		const rendered = await icon.evaluate((element) => {
			const pseudo = getComputedStyle(element, '::before');
			const box = element.getBoundingClientRect();
			return {
				content: pseudo.content,
				fontFamily: pseudo.fontFamily,
				width: box.width,
				height: box.height
			};
		});

		expect(rendered.content).not.toBe('none');
		expect(rendered.content).not.toBe('normal');
		expect(rendered.content).not.toBe('""');
		expect(rendered.fontFamily).toContain('Phosphor Subset');
		expect(rendered.width).toBeGreaterThan(0);
		expect(rendered.height).toBeGreaterThan(0);
	});
}
