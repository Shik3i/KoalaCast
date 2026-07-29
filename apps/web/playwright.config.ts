import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
	testDir: './tests/ui',
	fullyParallel: false,
	workers: 2,
	retries: process.env.CI ? 2 : 0,
	reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : 'list',
	use: {
		baseURL: 'http://127.0.0.1:4173',
		trace: 'retain-on-failure',
		screenshot: 'only-on-failure'
	},
	webServer: {
		command: 'npm run preview -- --host 127.0.0.1 --port 4173',
		url: 'http://127.0.0.1:4173',
		reuseExistingServer: !process.env.CI
	},
	projects: [
		{
			name: 'compact-android',
			use: {
				...devices['Pixel 5'],
				viewport: { width: 360, height: 740 }
			}
		},
		{
			name: 'compact-small',
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
		}
	]
});
