import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
	plugins: [sveltekit()],
	build: {
		rolldownOptions: {
			// A full production build completes in a few seconds; percentage-based
			// plugin timing notices are therefore noise rather than a slow-build signal.
			checks: { pluginTimings: false }
		}
	},
	server: {
		port: 5173,
		proxy: {
			'/api': {
				target: 'http://localhost:3000',
				changeOrigin: true
			},
			'/healthz': 'http://localhost:3000',
			'/readyz': 'http://localhost:3000'
		}
	},
	test: {
		include: ['src/**/*.test.ts'],
		// The i18n runtime lives in a .svelte.ts module and uses runes, so tests
		// need the Svelte compiler applied to server-side sources too.
		environment: 'node'
	}
});
