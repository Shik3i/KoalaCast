import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [sveltekit()],
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
