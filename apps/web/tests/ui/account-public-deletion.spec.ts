import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }, testInfo) => {
	const language = testInfo.project.name === 'compact-small' ? 'de' : 'en';
	await page.addInitScript((uiLanguage) => {
		localStorage.setItem('koalacast_onboarded', '1');
		localStorage.setItem('koalacast_ui_language', uiLanguage);
		localStorage.setItem('koalacast_preferred_languages', JSON.stringify([uiLanguage]));
	}, language);
	await page.route('**/api/v1/auth/status', (route) =>
		route.fulfill({ json: { authenticated: false } })
	);
});

test('public account page explains both deletion choices without requiring a login', async ({ page }) => {
	await page.goto('/account');
	await expect(page).toHaveURL(/\/account$/);
	await expect(page.getByRole('heading', { name: /KoalaCast data and account deletion|KoalaCast-Daten- und Kontolöschung/ })).toBeVisible();
	await expect(page.getByRole('heading', { name: /Delete synchronized data|Synchronisierte Daten löschen/, exact: true })).toBeVisible();
	await expect(page.getByRole('heading', { name: /Delete account|Konto löschen/, exact: true })).toBeVisible();
	await expect(page.getByText(/maximum of seven days|höchstens sieben Tage/)).toBeVisible();
	await expect(page.getByText(/Account, username, password and recovery credentials|Konto, Benutzername, Passwort- und Wiederherstellungszugang/)).toBeVisible();
	await expect(page.getByRole('link', { name: /Sign in to export or delete data|Zum Exportieren oder Löschen anmelden/ })).toHaveAttribute('href', '/login');
	await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});
