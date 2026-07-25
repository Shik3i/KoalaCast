// Locale registry — the single place a new language is registered.
//
// Adding a language is two steps: drop a JSON file in ./messages/ and add one
// entry here. Nothing else in the app needs to change.
//
// Deliberately dependency-free (no store imports): the preferences store
// resolves a locale while initializing, and the runtime reads that store.
// Keeping the pure parts here breaks the import cycle.

export interface LocaleConfig {
	/** BCP-47 primary subtag, matching the JSON filename. */
	code: string;
	/** Endonym — a language is always listed in its own language. */
	name: string;
	flag: string;
	/**
	 * Loads the message catalogue on demand, so unused locales never ship.
	 * Omitted for the fallback locale, which is bundled with the runtime.
	 */
	load?: () => Promise<{ default: unknown }>;
	/** Right-to-left script. Sets `dir="rtl"` on the document. */
	rtl?: boolean;
}

/**
 * Every locale the UI is available in.
 *
 * Keep this alphabetical by code, except English which stays first because it
 * is the fallback and the source of truth for translation keys.
 */
export const LOCALES: LocaleConfig[] = [
	// English has no loader: it is statically bundled as the fallback catalogue.
	{ code: 'en', name: 'English', flag: '🇬🇧' },
	{ code: 'de', name: 'Deutsch', flag: '🇩🇪', load: () => import('./messages/de.json') }
];

/** The fallback locale. Always bundled; every other locale is lazy-loaded. */
export const DEFAULT_LOCALE = 'en';

export function isSupportedLocale(value: string): boolean {
	return LOCALES.some((l) => l.code === value);
}

export function getLocaleConfig(code: string): LocaleConfig | undefined {
	return LOCALES.find((l) => l.code === code);
}

/**
 * Maps any language tag ("de-AT", "de", "fr") onto a locale the UI is actually
 * translated into, falling back to English. Used to seed the interface language
 * from the browser or from the listener's first content language.
 */
export function resolveLocale(value: string | undefined | null): string {
	const base = (value || '').toLowerCase().trim().split(/[-_]/)[0];
	return isSupportedLocale(base) ? base : DEFAULT_LOCALE;
}
