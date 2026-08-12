import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
	testDir: './tests/ui',
	globalSetup: './tests/ui/global-setup.ts',
	fullyParallel: false,
	workers: 2,
	globalTimeout: 180_000,
	timeout: 20_000,
	retries: process.env.CI ? 2 : 0,
	reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : 'list',
	use: {
		baseURL: 'http://127.0.0.1:4173',
		// Page routes are the API fixture layer in these tests. A registered PWA
		// service worker would intercept first and leak requests to a real backend.
		serviceWorkers: 'block',
		trace: 'retain-on-failure',
		screenshot: 'only-on-failure'
	},
	projects: [
		{
			name: 'compact-android',
			testIgnore: /sidebar-navigation\.spec\.ts/,
			use: {
				...devices['Pixel 5'],
				viewport: { width: 360, height: 740 }
			}
		},
		{
			name: 'compact-small',
			testIgnore: /sidebar-navigation\.spec\.ts/,
			use: {
				...devices['Galaxy S9+'],
				viewport: { width: 320, height: 640 }
			}
		},
		{
			name: 'desktop-player',
			testMatch: /podcast-player\.spec\.ts/,
			use: {
				...devices['Desktop Chrome'],
				viewport: { width: 1440, height: 900 }
			}
		},
		{
			name: 'desktop-account',
			testMatch: /account-layout\.spec\.ts/,
			use: {
				...devices['Desktop Chrome'],
				viewport: { width: 1200, height: 900 }
			}
		},
		{
			name: 'desktop-navigation',
			testMatch: /sidebar-navigation\.spec\.ts/,
			use: {
				...devices['Desktop Chrome'],
				viewport: { width: 1200, height: 900 }
			}
		}
	]
});
