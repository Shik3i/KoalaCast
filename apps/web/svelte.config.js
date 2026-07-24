import adapter from '@sveltejs/adapter-node';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),
	kit: {
		inlineStyleThreshold: 65536,
		adapter: adapter({
			out: 'build',
			precompress: true
		})
	}
};

export default config;
