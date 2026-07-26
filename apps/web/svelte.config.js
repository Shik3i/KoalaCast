import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),
	kit: {
		// The shared Quiet Edition stylesheet compresses to roughly 32 KiB.
		// Inlining it removes a full render-blocking request on cold loads while
		// staying comfortably below an oversized HTML payload.
		inlineStyleThreshold: 200000,
		adapter: adapter({
			pages: 'build',
			assets: 'build',
			fallback: 'index.html',
			precompress: true
		})
	}
};

export default config;
