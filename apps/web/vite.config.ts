import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vitest/config';

export default defineConfig({
	plugins: [
		sveltekit({
			preprocess: vitePreprocess(),
			// Kit 3 dropped the built-in $lib alias; keep it so imports stay unchanged.
			alias: { $lib: 'src/lib' },
			csp: {
				mode: 'hash',
				directives: {
					'default-src': ['self'],
					'base-uri': ['self'],
					'object-src': ['none'],
					'frame-ancestors': ['none'],
					'form-action': ['self'],
					'script-src': ['self'],
					'style-src': ['self', 'unsafe-inline'],
					'img-src': ['self', 'data:', 'https:', 'http:'],
					'media-src': ['self', 'https:', 'http:'],
					'font-src': ['self'],
					'connect-src': ['self', 'https:', 'http:']
				}
			},
			// The icon subset keeps the complete shell CSS below 70 KiB. Inlining that
			// critical shell removes the only render-blocking request while the
			// compressed document remains small.
			inlineStyleThreshold: 80000,
			adapter: adapter({
				pages: 'build',
				assets: 'build',
				fallback: 'index.html',
				precompress: true
			})
		})
	],
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
