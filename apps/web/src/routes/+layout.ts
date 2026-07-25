import { loadLocale } from '$lib/i18n';
import { initialUILanguage } from '$lib/stores/prefs.svelte';

export const ssr = false;
export const prerender = false;

/**
 * Loads the listener's interface language before the first render.
 *
 * Locales are lazy-loaded chunks, so awaiting here is what stops a German UI
 * from flashing English on startup. Only the active locale is ever fetched.
 */
export async function load() {
	await loadLocale(initialUILanguage());
	return {};
}
