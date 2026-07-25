// Content languages the listener can filter Discover and Search by.
//
// A language is NOT a storefront region. The iTunes German storefront is full of
// English shows, so filtering by region alone can never produce a German-only
// chart — the server filters on the feed's own RSS <language> instead. The
// region here is only the storefront a chart is *pulled* from; `code` is what
// the results are then filtered down to.

export interface LanguageConfig {
	/** ISO 639-1 code, matching the server's filter and the RSS <language> tag. */
	code: string;
	/** Endonym — a language is always listed in its own language. */
	name: string;
	flag: string;
	/** iTunes storefront to pull charts from for this language. */
	region: string;
}

export const SUPPORTED_LANGUAGES: LanguageConfig[] = [
	{ code: 'de', name: 'Deutsch', flag: '🇩🇪', region: 'de' },
	{ code: 'en', name: 'English', flag: '🇬🇧', region: 'us' },
	{ code: 'fr', name: 'Français', flag: '🇫🇷', region: 'fr' },
	{ code: 'es', name: 'Español', flag: '🇪🇸', region: 'es' },
	{ code: 'it', name: 'Italiano', flag: '🇮🇹', region: 'it' },
	{ code: 'pt', name: 'Português', flag: '🇵🇹', region: 'pt' },
	{ code: 'nl', name: 'Nederlands', flag: '🇳🇱', region: 'nl' }
];

/**
 * Storefront codes the app used to persist under the "languages" preference
 * before languages and regions were separated. Existing installs still have
 * these in localStorage, so they are migrated on read rather than discarded
 * (which would silently reset everyone's preferences).
 */
const LEGACY_REGION_TO_LANGUAGE: Record<string, string> = {
	us: 'en',
	gb: 'en',
	uk: 'en',
	au: 'en',
	ca: 'en',
	de: 'de',
	at: 'de',
	ch: 'de',
	fr: 'fr',
	es: 'es',
	mx: 'es',
	it: 'it',
	pt: 'pt',
	br: 'pt',
	nl: 'nl'
};

const SUPPORTED_CODES = new Set(SUPPORTED_LANGUAGES.map((l) => l.code));

export function isSupportedLanguage(code: string): boolean {
	return SUPPORTED_CODES.has(code);
}

export function getLanguage(code: string): LanguageConfig | undefined {
	return SUPPORTED_LANGUAGES.find((l) => l.code === code);
}

/** The storefront to pull a chart from for a language, defaulting to the US. */
export function regionForLanguage(code: string): string {
	return getLanguage(code)?.region ?? 'us';
}

/**
 * Normalizes a persisted preference entry to a supported language code,
 * accepting both current codes and legacy storefront codes. Returns null for
 * anything unrecognized.
 */
export function normalizeLanguage(value: string): string | null {
	const v = (value || '').toLowerCase().trim().split(/[-_]/)[0];
	if (!v) return null;
	if (SUPPORTED_CODES.has(v)) return v;
	const migrated = LEGACY_REGION_TO_LANGUAGE[v];
	return migrated && SUPPORTED_CODES.has(migrated) ? migrated : null;
}

/** Migrates a stored list, dropping unknowns and de-duplicating. */
export function normalizeLanguageList(values: unknown): string[] {
	if (!Array.isArray(values)) return [];
	const out: string[] = [];
	for (const v of values) {
		if (typeof v !== 'string') continue;
		const code = normalizeLanguage(v);
		if (code && !out.includes(code)) out.push(code);
	}
	return out;
}

/**
 * Best guess at the listener's language from the browser, used as the initial
 * preference so a German browser gets German podcasts without configuring
 * anything. Falls back to English.
 */
export function detectBrowserLanguages(): string[] {
	if (typeof navigator === 'undefined') return ['en'];
	const candidates = navigator.languages?.length ? navigator.languages : [navigator.language];
	const detected = normalizeLanguageList(candidates as unknown[]);
	return detected.length > 0 ? detected.slice(0, 1) : ['en'];
}
