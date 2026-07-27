import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),
	kit: {
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
	}
};

export default config;
