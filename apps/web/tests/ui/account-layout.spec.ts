import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }, testInfo) => {
	const language = testInfo.project.name === 'compact-small' ? 'de' : 'en';
	await page.addInitScript((uiLanguage) => {
		localStorage.setItem('koalacast_onboarded', '1');
		localStorage.setItem('koalacast_ui_language', uiLanguage);
		localStorage.setItem('koalacast_preferred_languages', JSON.stringify([uiLanguage]));
	}, language);

	await page.route('**/api/v1/auth/status', async (route) => {
		await route.fulfill({
			json: {
				authenticated: true,
				user_id: '94d7d251-29c9-42b5-91e7-4fe983e4ce29',
				username: 'account-layout-regression-user',
				role: 'admin'
			}
		});
	});
	await page.route('**/api/v1/auth/sessions', async (route) => {
		await route.fulfill({
			json: {
				sessions: [
					{
						id: 'current',
						kind: 'session',
						device_name: 'Web Session',
						device_type: 'web',
						truncated_ip: '2001:db8:1234:5678::/64',
						sanitized_user_agent: 'Compact Browser',
						created_at: 1_786_377_500_000,
						last_used_at: 1_786_377_600_000,
						is_current: true
					},
					{
						id: 'other',
						kind: 'device',
						device_name: 'Android application',
						device_type: 'android',
						truncated_ip: '203.0.113.0/24',
						sanitized_user_agent: '',
						created_at: 1_786_377_500_000,
						last_used_at: 1_786_377_600_000,
						is_current: false
					}
				]
			}
		});
	});
	await page.route('**/api/v1/sync*', async (route) => {
		await route.fulfill({
			json: {
				since_cursor: 0,
				next_cursor: 0,
				current_cursor: 0,
				has_more: false,
				changesets: [],
				data_generation: 0
			}
		});
	});
});

test('account controls, long metadata and destructive actions never collide', async ({ page }, testInfo) => {
	await page.goto('/account');
	await expect(page.locator('.account-grid > .card')).toHaveCount(3);
	await expect(page.locator('.danger-card')).toBeVisible();

	const geometry = await page.evaluate(() => {
		const rect = (selector: string) => {
			const element = document.querySelector(selector);
			if (!element) return null;
			const box = element.getBoundingClientRect();
			return {
				x: box.x,
				y: box.y,
				right: box.right,
				bottom: box.bottom,
				width: box.width,
				height: box.height
			};
		};
		const title = rect('.data-control-header h3');
		const hint = rect('.data-control-header .subtitle');
		const grid = rect('.account-grid');
		const danger = rect('.danger-card');
		const viewportWidth = window.innerWidth;
		const rowsFit = [...document.querySelectorAll('.session-row')].every((row) => {
			const rowBox = row.getBoundingClientRect();
			return [...row.children].every((child) => {
				const box = child.getBoundingClientRect();
				return box.left >= rowBox.left - 1 && box.right <= rowBox.right + 1;
			});
		});
		return {
			viewportWidth,
			documentWidth: document.documentElement.scrollWidth,
			title,
			hint,
			grid,
			danger,
			rowsFit
		};
	});

	expect(geometry.documentWidth).toBeLessThanOrEqual(geometry.viewportWidth + 1);
	expect(geometry.rowsFit).toBe(true);
	expect(geometry.title).not.toBeNull();
	expect(geometry.hint).not.toBeNull();
	expect(geometry.title!.bottom).toBeLessThanOrEqual(geometry.hint!.y + 1);

	if (testInfo.project.name === 'desktop-account') {
		expect(Math.abs(geometry.danger!.x - geometry.grid!.x)).toBeLessThanOrEqual(1);
		expect(Math.abs(geometry.danger!.width - geometry.grid!.width)).toBeLessThanOrEqual(1);
	}

	await page.getByRole('button', { name: /Delete account|Konto löschen/ }).click();
	await expect(page.locator('.delete-form')).toBeVisible();
	await expect(page.locator('#delete-credential')).toBeFocused();
	await expect.poll(() =>
		page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
	).toBe(true);

	const dangerBox = await page.locator('.danger-card').boundingBox();
	const formBox = await page.locator('.delete-form').boundingBox();
	expect(dangerBox).not.toBeNull();
	expect(formBox).not.toBeNull();
	expect(formBox!.x).toBeGreaterThanOrEqual(dangerBox!.x);
	expect(formBox!.x + formBox!.width).toBeLessThanOrEqual(dangerBox!.x + dangerBox!.width + 1);

	await page.getByRole('button', { name: /Cancel|Abbrechen/, exact: true }).click();
	await expect(page.getByRole('button', { name: /Delete account|Konto löschen/ })).toBeFocused();
});
