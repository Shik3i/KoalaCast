// Small global preferences store (persisted to localStorage). Holds the date
// display mode so the whole app formats episode dates consistently, the content
// languages Discover and Search are filtered to, and the UI language.

import {
	detectBrowserLanguages,
	normalizeLanguageList,
	regionForLanguage
} from '$lib/data/languages';
// Only the pure registry is imported here, never the runtime: the runtime reads
// this store, and importing it back would create a cycle.
import { isSupportedLocale, resolveLocale } from '$lib/i18n/registry';

export type DateFormat = 'absolute' | 'relative';

const KEY = 'koalacast_date_format';
const INTERESTS_KEY = 'koalacast_interests';
const HIDDEN_KEY = 'koalacast_hidden_genres';
const LANGUAGES_KEY = 'koalacast_preferred_languages';
const UI_LANGUAGE_KEY = 'koalacast_ui_language';
const ONBOARDED_KEY = 'koalacast_onboarded';
const VOLUME_BOOST_KEY = 'koalacast_volume_boost';
const SKIP_SILENCE_KEY = 'koalacast_skip_silence';
const SETTINGS_UPDATED_AT_KEY = 'koalacast_settings_updated_at';

function initialBoolean(key: string): boolean {
	if (typeof localStorage === 'undefined') return false;
	try {
		return localStorage.getItem(key) === '1';
	} catch (_) {
		return false;
	}
}

// Reads the stored content languages, migrating the legacy storefront codes
// ("us", "gb") this preference used to hold before languages and regions were
// separated. An empty or unreadable value falls back to the browser's language.
function initialLanguages(): string[] {
	if (typeof localStorage === 'undefined') return ['en'];
	try {
		const stored = normalizeLanguageList(JSON.parse(localStorage.getItem(LANGUAGES_KEY) || '[]'));
		return stored.length > 0 ? stored : detectBrowserLanguages();
	} catch (_) {
		return detectBrowserLanguages();
	}
}

// The UI language is independent of the content languages: someone may want a
// German interface while still listening to English shows. It defaults to the
// first content language when it is one we have translations for, else to the
// browser's own language.
export function initialUILanguage(contentLanguages: string[] = initialLanguages()): string {
	if (typeof localStorage === 'undefined') return 'en';
	const stored = localStorage.getItem(UI_LANGUAGE_KEY);
	if (stored && isSupportedLocale(stored)) return stored;
	const fromContent = resolveLocale(contentLanguages[0]);
	if (fromContent !== 'en') return fromContent;
	return resolveLocale(typeof navigator !== 'undefined' ? navigator.language : undefined);
}

function initialFormat(): DateFormat {
	if (typeof localStorage === 'undefined') return 'absolute';
	return localStorage.getItem(KEY) === 'relative' ? 'relative' : 'absolute';
}

function initialInterests(): string[] {
	if (typeof localStorage === 'undefined') return [];
	try {
		const v = JSON.parse(localStorage.getItem(INTERESTS_KEY) || '[]');
		return Array.isArray(v) ? v : [];
	} catch (_) {
		return [];
	}
}

function initialHidden(): string[] {
	if (typeof localStorage === 'undefined') return [];
	try {
		const v = JSON.parse(localStorage.getItem(HIDDEN_KEY) || '[]');
		return Array.isArray(v) ? v : [];
	} catch (_) {
		return [];
	}
}

function initialOnboarded(): boolean {
	if (typeof localStorage === 'undefined') return true; // never block SSR
	return localStorage.getItem(ONBOARDED_KEY) === '1';
}

class Prefs {
	dateFormat = $state<DateFormat>(initialFormat());
	// Chosen genre interests (explicit personalization seed). Also editable later
	// in Settings; kept on-device for privacy.
	interests = $state<string[]>(initialInterests());
	// Vetoed genres — podcasts in these are hidden from discover and search.
	hiddenGenres = $state<string[]>(initialHidden());
	// Spoken languages Discover and Search are filtered to (ISO 639-1 codes).
	languages = $state<string[]>(initialLanguages());
	// Interface language, independent of the content languages above. The active
	// translation catalogue lives in the i18n runtime; this is the persisted
	// preference that drives it (see `applyLocale` in +layout.svelte).
	uiLanguage = $state<string>(initialUILanguage());
	onboarded = $state<boolean>(initialOnboarded());
	volumeBoost = $state<boolean>(initialBoolean(VOLUME_BOOST_KEY));
	skipSilence = $state<boolean>(initialBoolean(SKIP_SILENCE_KEY));
	updatedAt = $state<number>(
		typeof localStorage === 'undefined'
			? 0
			: Math.max(0, Number(localStorage.getItem(SETTINGS_UPDATED_AT_KEY)) || 0)
	);

	#touch() {
		this.updatedAt = Date.now();
		try {
			localStorage.setItem(SETTINGS_UPDATED_AT_KEY, String(this.updatedAt));
		} catch (_) {}
	}

	// iTunes storefronts to pull charts from, one per selected language.
	// Deduplicated because several languages can share a storefront.
	get regions(): string[] {
		return [...new Set(this.languages.map(regionForLanguage))];
	}

	#persistLanguages() {
		try {
			localStorage.setItem(LANGUAGES_KEY, JSON.stringify(this.languages));
		} catch (_) {}
	}

	toggleLanguage(langCode: string) {
		const has = this.languages.includes(langCode);
		if (has && this.languages.length === 1) {
			// At least one language must remain active
			return;
		}
		this.languages = has ? this.languages.filter((l) => l !== langCode) : [...this.languages, langCode];
		this.#persistLanguages();
		this.#touch();
	}

	setUILanguage(locale: string) {
		this.uiLanguage = locale;
		try {
			localStorage.setItem(UI_LANGUAGE_KEY, locale);
		} catch (_) {}
		this.#touch();
	}

	setDateFormat(mode: DateFormat) {
		this.dateFormat = mode;
		try {
			localStorage.setItem(KEY, mode);
		} catch (_) {}
		this.#touch();
	}

	setVolumeBoost(enabled: boolean) {
		this.volumeBoost = enabled;
		try {
			localStorage.setItem(VOLUME_BOOST_KEY, enabled ? '1' : '0');
		} catch (_) {}
		this.#touch();
	}

	setSkipSilence(enabled: boolean) {
		this.skipSilence = enabled;
		try {
			localStorage.setItem(SKIP_SILENCE_KEY, enabled ? '1' : '0');
		} catch (_) {}
		this.#touch();
	}

	#persistInterests() {
		try {
			localStorage.setItem(INTERESTS_KEY, JSON.stringify(this.interests));
		} catch (_) {}
	}

	toggleInterest(genre: string) {
		const has = this.interests.includes(genre);
		this.interests = has ? this.interests.filter((g) => g !== genre) : [...this.interests, genre];
		// A genre can't be both an interest and hidden.
		if (!has && this.hiddenGenres.includes(genre)) {
			this.hiddenGenres = this.hiddenGenres.filter((g) => g !== genre);
			this.#persistHidden();
		}
		this.#persistInterests();
		this.#touch();
	}

	#persistHidden() {
		try {
			localStorage.setItem(HIDDEN_KEY, JSON.stringify(this.hiddenGenres));
		} catch (_) {}
	}

	toggleHidden(genre: string) {
		this.hiddenGenres = this.hiddenGenres.includes(genre)
			? this.hiddenGenres.filter((g) => g !== genre)
			: [...this.hiddenGenres, genre];
		if (this.interests.includes(genre)) {
			this.interests = this.interests.filter((g) => g !== genre);
			this.#persistInterests();
		}
		this.#persistHidden();
		this.#touch();
	}

	syncPayload() {
		return {
			date_format: this.dateFormat,
			interests: [...this.interests],
			hidden_genres: [...this.hiddenGenres],
			languages: [...this.languages],
			ui_language: this.uiLanguage,
			volume_boost: this.volumeBoost,
			skip_silence: this.skipSilence,
			updated_at: this.updatedAt
		};
	}

	applySynced(payload: Record<string, unknown>) {
		const updatedAt = Math.max(0, Number(payload.updated_at) || 0);
		if (!updatedAt || this.updatedAt >= updatedAt) return;
		const languages = normalizeLanguageList(Array.isArray(payload.languages) ? payload.languages : []);
		if (payload.date_format === 'relative' || payload.date_format === 'absolute') {
			this.dateFormat = payload.date_format;
		}
		this.languages = languages.length ? languages : this.languages;
		if (Array.isArray(payload.interests)) {
			this.interests = payload.interests.filter(
				(value): value is string => typeof value === 'string'
			);
		}
		if (Array.isArray(payload.hidden_genres)) {
			this.hiddenGenres = payload.hidden_genres.filter(
				(value): value is string => typeof value === 'string'
			);
		}
		if (typeof payload.ui_language === 'string' && isSupportedLocale(payload.ui_language)) {
			this.uiLanguage = payload.ui_language;
		}
		if (typeof payload.volume_boost === 'boolean') {
			this.volumeBoost = payload.volume_boost;
		}
		if (typeof payload.skip_silence === 'boolean') {
			this.skipSilence = payload.skip_silence;
		}
		this.updatedAt = updatedAt;
		try {
			localStorage.setItem(KEY, this.dateFormat);
			localStorage.setItem(LANGUAGES_KEY, JSON.stringify(this.languages));
			localStorage.setItem(INTERESTS_KEY, JSON.stringify(this.interests));
			localStorage.setItem(HIDDEN_KEY, JSON.stringify(this.hiddenGenres));
			localStorage.setItem(UI_LANGUAGE_KEY, this.uiLanguage);
			localStorage.setItem(VOLUME_BOOST_KEY, this.volumeBoost ? '1' : '0');
			localStorage.setItem(SKIP_SILENCE_KEY, this.skipSilence ? '1' : '0');
			localStorage.setItem(SETTINGS_UPDATED_AT_KEY, String(this.updatedAt));
		} catch (_) {}
	}

	// True if a podcast's categories intersect the hidden set.
	isHidden(categories: string[] | undefined): boolean {
		if (!categories || this.hiddenGenres.length === 0) return false;
		const lower = this.hiddenGenres.map((g) => g.toLowerCase());
		return categories.some((c) => lower.includes((c || '').toLowerCase()));
	}

	completeOnboarding() {
		this.onboarded = true;
		try {
			localStorage.setItem(ONBOARDED_KEY, '1');
		} catch (_) {}
	}

	// Format an episode pub date (unix seconds) per the current setting.
	formatDate(sec?: number | null): string {
		if (!sec) return '';
		const date = new Date(sec * 1000);
		if (this.dateFormat === 'relative') return relative(date, this.uiLanguage);
		return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
	}
}

function relative(date: Date, locale: string): string {
	const diffMs = Date.now() - date.getTime();
	const sec = Math.round(diffMs / 1000);
	const formatter = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
	if (Math.abs(sec) < 45) return formatter.format(0, 'second');
	const min = Math.round(sec / 60);
	if (Math.abs(min) < 60) return formatter.format(-min, 'minute');
	const hours = Math.round(min / 60);
	if (Math.abs(hours) < 24) return formatter.format(-hours, 'hour');
	const days = Math.round(hours / 24);
	if (Math.abs(days) < 7) return formatter.format(-days, 'day');
	const weeks = Math.round(days / 7);
	if (Math.abs(weeks) < 5) return formatter.format(-weeks, 'week');
	const months = Math.round(days / 30);
	if (Math.abs(months) < 12) return formatter.format(-months, 'month');
	const years = Math.round(days / 365);
	return formatter.format(-years, 'year');
}

export const prefs = new Prefs();
