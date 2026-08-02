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
import { storedPlaybackSpeed } from '$lib/player/playback-speed';
import { foreignSettingsOf, mergeForeignSettings } from './settings-merge';
import {
	getStoredPalette,
	getStoredTheme,
	isPaletteId,
	setPalette,
	setTheme,
	type PaletteId,
	type ThemeMode
} from '$lib/theme';

export type DateFormat = 'absolute' | 'relative';
export type DefaultInboxMode = 'all' | 'latest';
export type StartScreen = 'discover' | 'inbox' | 'library';
export type VisualizerStyle = 'off' | 'level' | 'waveform' | 'bars' | 'pulse';
export type DownloadRetention = 'keep' | 'finished' | '7d' | '14d' | '30d';
export interface HiddenPodcastPreference {
	key: string;
	title: string;
}

const KEY = 'koalacast_date_format';
const INTERESTS_KEY = 'koalacast_interests';
const HIDDEN_KEY = 'koalacast_hidden_genres';
const HIDDEN_PODCASTS_KEY = 'koalacast_hidden_podcasts';
const DEFAULT_INBOX_MODE_KEY = 'koalacast_default_inbox_mode';
const LANGUAGES_KEY = 'koalacast_preferred_languages';
const UI_LANGUAGE_KEY = 'koalacast_ui_language';
const ONBOARDED_KEY = 'koalacast_onboarded';
const VOLUME_BOOST_KEY = 'koalacast_volume_boost';
const SKIP_SILENCE_KEY = 'koalacast_skip_silence';
const PLAYBACK_SPEED_KEY = 'koalacast_playback_speed';
const START_SCREEN_KEY = 'koalacast_start_screen';
const VISUALIZER_KEY = 'koalacast_visualizer';
const PROXY_IMAGES_KEY = 'koalacast_proxy_images';
const DOWNLOAD_WIFI_ONLY_KEY = 'koalacast_download_wifi_only';
const AUTO_DOWNLOAD_COUNT_KEY = 'koalacast_auto_download_count';
const DOWNLOAD_RETENTION_KEY = 'koalacast_download_retention';
const DOWNLOAD_CONCURRENCY_KEY = 'koalacast_download_concurrency';
const DOWNLOAD_BUDGET_BYTES_KEY = 'koalacast_download_budget_bytes';
const SETTINGS_UPDATED_AT_KEY = 'koalacast_settings_updated_at';
const FOREIGN_SETTINGS_KEY = 'koalacast_settings_foreign';
const GUEST_MIGRATION_KEY = 'koalacast_guest_preferences_migrated';
const ACCOUNT_SCOPED_KEYS = [
	KEY,
	INTERESTS_KEY,
	HIDDEN_KEY,
	HIDDEN_PODCASTS_KEY,
	DEFAULT_INBOX_MODE_KEY,
	LANGUAGES_KEY,
	UI_LANGUAGE_KEY,
	VOLUME_BOOST_KEY,
	SKIP_SILENCE_KEY,
	PLAYBACK_SPEED_KEY,
	START_SCREEN_KEY,
	VISUALIZER_KEY,
	PROXY_IMAGES_KEY,
	DOWNLOAD_WIFI_ONLY_KEY,
	AUTO_DOWNLOAD_COUNT_KEY,
	DOWNLOAD_RETENTION_KEY,
	DOWNLOAD_CONCURRENCY_KEY,
	DOWNLOAD_BUDGET_BYTES_KEY,
	SETTINGS_UPDATED_AT_KEY,
	FOREIGN_SETTINGS_KEY
];

function preferenceStorage(): Storage | null {
	if (
		typeof localStorage === 'undefined' ||
		typeof localStorage.getItem !== 'function' ||
		typeof localStorage.setItem !== 'function'
	) return null;
	return localStorage;
}

function initialForeignSettings(): Record<string, unknown> {
	if (typeof localStorage === 'undefined') return {};
	try {
		const raw = localStorage.getItem(scopedKey(FOREIGN_SETTINGS_KEY));
		if (!raw) return {};
		const parsed = JSON.parse(raw);
		return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
	} catch (_) {
		return {};
	}
}
let activeOwner: string | null = null;

function scopedKey(key: string, owner = activeOwner): string {
	return owner ? `${key}:account:${encodeURIComponent(owner)}` : key;
}

function initialBoolean(key: string): boolean {
	if (typeof localStorage === 'undefined') return false;
	try {
		return localStorage.getItem(scopedKey(key)) === '1';
	} catch (_) {
		return false;
	}
}

function initialBooleanWithDefault(key: string, fallback: boolean): boolean {
	if (typeof localStorage === 'undefined') return fallback;
	try {
		const value = localStorage.getItem(scopedKey(key));
		return value === null ? fallback : value === '1';
	} catch (_) {
		return fallback;
	}
}

function initialNumber(key: string, fallback: number, min: number, max: number): number {
	if (typeof localStorage === 'undefined') return fallback;
	try {
		const raw = localStorage.getItem(scopedKey(key));
		if (raw === null) return fallback;
		return clampedNumber(raw, fallback, min, max);
	} catch (_) {
		return fallback;
	}
}

function clampedNumber(value: unknown, fallback: number, min: number, max: number): number {
	const numeric = Number(value);
	return Number.isFinite(numeric)
		? Math.min(max, Math.max(min, Math.round(numeric)))
		: fallback;
}

function initialStartScreen(): StartScreen {
	if (typeof localStorage === 'undefined') return 'discover';
	try {
		const value = localStorage.getItem(scopedKey(START_SCREEN_KEY));
		return value === 'inbox' || value === 'library' ? value : 'discover';
	} catch (_) {
		return 'discover';
	}
}

function initialVisualizer(): VisualizerStyle {
	if (typeof localStorage === 'undefined') return 'off';
	try {
		const value = localStorage.getItem(scopedKey(VISUALIZER_KEY));
		// Retired: a coarser, less legible restatement of the bars. Anyone who
		// chose it lands there rather than silently having the visualiser off.
		if (value === 'dots') return 'bars';
		return value === 'level' || value === 'waveform' || value === 'bars' || value === 'pulse' ? value : 'off';
	} catch (_) {
		return 'off';
	}
}

function initialDownloadRetention(): DownloadRetention {
	if (typeof localStorage === 'undefined') return 'keep';
	try {
		const value = localStorage.getItem(scopedKey(DOWNLOAD_RETENTION_KEY));
		return value === 'finished' || value === '7d' || value === '14d' || value === '30d' ? value : 'keep';
	} catch (_) {
		return 'keep';
	}
}

function initialPlaybackSpeed(): number {
	const storage = preferenceStorage();
	if (!storage) return 1;
	return storedPlaybackSpeed(storage.getItem(scopedKey(PLAYBACK_SPEED_KEY)));
}

// Reads the stored content languages, migrating the legacy storefront codes
// ("us", "gb") this preference used to hold before languages and regions were
// separated. An empty or unreadable value falls back to the browser's language.
function initialLanguages(): string[] {
	if (typeof localStorage === 'undefined') return ['en'];
	try {
		const stored = normalizeLanguageList(JSON.parse(localStorage.getItem(scopedKey(LANGUAGES_KEY)) || '[]'));
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
	const storage = preferenceStorage();
	if (!storage) return 'en';
	const stored = storage.getItem(scopedKey(UI_LANGUAGE_KEY));
	if (stored && isSupportedLocale(stored)) return stored;
	const fromContent = resolveLocale(contentLanguages[0]);
	if (fromContent !== 'en') return fromContent;
	return resolveLocale(typeof navigator !== 'undefined' ? navigator.language : undefined);
}

function initialFormat(): DateFormat {
	const storage = preferenceStorage();
	if (!storage) return 'absolute';
	return storage.getItem(scopedKey(KEY)) === 'relative' ? 'relative' : 'absolute';
}

function initialInterests(): string[] {
	if (typeof localStorage === 'undefined') return [];
	try {
		const v = JSON.parse(localStorage.getItem(scopedKey(INTERESTS_KEY)) || '[]');
		return Array.isArray(v) ? v : [];
	} catch (_) {
		return [];
	}
}

function initialHidden(): string[] {
	if (typeof localStorage === 'undefined') return [];
	try {
		const v = JSON.parse(localStorage.getItem(scopedKey(HIDDEN_KEY)) || '[]');
		return Array.isArray(v) ? v : [];
	} catch (_) {
		return [];
	}
}

function initialHiddenPodcasts(): HiddenPodcastPreference[] {
	if (typeof localStorage === 'undefined') return [];
	try {
		const value = JSON.parse(localStorage.getItem(scopedKey(HIDDEN_PODCASTS_KEY)) || '[]');
		if (!Array.isArray(value)) return [];
		return value.filter(
			(item): item is HiddenPodcastPreference =>
				typeof item?.key === 'string' &&
				item.key.length > 0 &&
				typeof item?.title === 'string'
		);
	} catch (_) {
		return [];
	}
}

function initialDefaultInboxMode(): DefaultInboxMode {
	const storage = preferenceStorage();
	if (!storage) return 'all';
	return storage.getItem(scopedKey(DEFAULT_INBOX_MODE_KEY)) === 'latest' ? 'latest' : 'all';
}

export function podcastPreferenceKey(feedUrl?: string, id?: string): string {
	const feed = feedUrl?.trim().toLowerCase();
	if (feed) return `feed:${feed}`;
	return `id:${(id || '').trim().toLowerCase()}`;
}

function initialOnboarded(): boolean {
	const storage = preferenceStorage();
	if (!storage) return true; // never block SSR
	return storage.getItem(ONBOARDED_KEY) === '1';
}

class Prefs {
	dateFormat = $state<DateFormat>(initialFormat());
	// Chosen genre interests (explicit personalization seed). Also editable later
	// in Settings; kept on-device for privacy.
	interests = $state<string[]>(initialInterests());
	// Vetoed genres — podcasts in these are hidden from discover and search.
	hiddenGenres = $state<string[]>(initialHidden());
	hiddenPodcasts = $state<HiddenPodcastPreference[]>(initialHiddenPodcasts());
	// Used for future subscriptions only. Each subscribed show can still override
	// this from Inbox.
	defaultInboxMode = $state<DefaultInboxMode>(initialDefaultInboxMode());
	// Spoken languages Discover and Search are filtered to (ISO 639-1 codes).
	languages = $state<string[]>(initialLanguages());
	// Interface language, independent of the content languages above. The active
	// translation catalogue lives in the i18n runtime; this is the persisted
	// preference that drives it (see `applyLocale` in +layout.svelte).
	uiLanguage = $state<string>(initialUILanguage());
	onboarded = $state<boolean>(initialOnboarded());
	volumeBoost = $state<boolean>(initialBoolean(VOLUME_BOOST_KEY));
	skipSilence = $state<boolean>(initialBoolean(SKIP_SILENCE_KEY));
	playbackSpeed = $state<number>(initialPlaybackSpeed());
	startScreen = $state<StartScreen>(initialStartScreen());
	visualizer = $state<VisualizerStyle>(initialVisualizer());
	proxyImages = $state<boolean>(initialBooleanWithDefault(PROXY_IMAGES_KEY, true));
	downloadWifiOnly = $state<boolean>(initialBooleanWithDefault(DOWNLOAD_WIFI_ONLY_KEY, true));
	autoDownloadCount = $state<number>(initialNumber(AUTO_DOWNLOAD_COUNT_KEY, 3, 1, 10));
	downloadRetention = $state<DownloadRetention>(initialDownloadRetention());
	downloadConcurrency = $state<number>(initialNumber(DOWNLOAD_CONCURRENCY_KEY, 2, 1, 4));
	downloadBudgetBytes = $state<number>(
		initialNumber(DOWNLOAD_BUDGET_BYTES_KEY, 2_048 * 1024 * 1024, 0, 10 * 1024 * 1024 * 1024)
	);
	updatedAt = $state<number>(
		preferenceStorage() === null
			? 0
			: Math.max(0, Number(preferenceStorage()?.getItem(scopedKey(SETTINGS_UPDATED_AT_KEY))) || 0)
	);
	// Settings keys owned by another client, kept verbatim so pushing from here
	// does not delete them from the server. Not $state: nothing renders them.
	#foreignSettings: Record<string, unknown> = initialForeignSettings();

	activateContext(userId: string | null, options: { migrateGuest?: boolean } = {}) {
		const storage = preferenceStorage();
		if (
			storage &&
			userId &&
			options.migrateGuest &&
			storage.getItem(GUEST_MIGRATION_KEY) !== '1'
		) {
			for (const baseKey of ACCOUNT_SCOPED_KEYS) {
				const target = scopedKey(baseKey, userId);
				if (storage.getItem(target) === null) {
					const legacy = storage.getItem(baseKey);
					if (legacy !== null) storage.setItem(target, legacy);
				}
			}
			storage.setItem(GUEST_MIGRATION_KEY, '1');
		}
		activeOwner = userId;
		this.#foreignSettings = initialForeignSettings();
		this.dateFormat = initialFormat();
		this.interests = initialInterests();
		this.hiddenGenres = initialHidden();
		this.hiddenPodcasts = initialHiddenPodcasts();
		this.defaultInboxMode = initialDefaultInboxMode();
		this.languages = initialLanguages();
		this.uiLanguage = initialUILanguage(this.languages);
		this.volumeBoost = initialBoolean(VOLUME_BOOST_KEY);
		this.skipSilence = initialBoolean(SKIP_SILENCE_KEY);
		this.playbackSpeed = initialPlaybackSpeed();
		this.startScreen = initialStartScreen();
		this.visualizer = initialVisualizer();
		this.proxyImages = initialBooleanWithDefault(PROXY_IMAGES_KEY, true);
		this.downloadWifiOnly = initialBooleanWithDefault(DOWNLOAD_WIFI_ONLY_KEY, true);
		this.autoDownloadCount = initialNumber(AUTO_DOWNLOAD_COUNT_KEY, 3, 1, 10);
		this.downloadRetention = initialDownloadRetention();
		this.downloadConcurrency = initialNumber(DOWNLOAD_CONCURRENCY_KEY, 2, 1, 4);
		this.downloadBudgetBytes = initialNumber(
			DOWNLOAD_BUDGET_BYTES_KEY,
			2_048 * 1024 * 1024,
			0,
			10 * 1024 * 1024 * 1024
		);
		this.updatedAt =
			storage === null
				? 0
				: Math.max(0, Number(storage.getItem(scopedKey(SETTINGS_UPDATED_AT_KEY))) || 0);
	}

	#touch() {
		this.updatedAt = Date.now();
		try {
			localStorage.setItem(scopedKey(SETTINGS_UPDATED_AT_KEY), String(this.updatedAt));
		} catch (_) {}
	}

	// iTunes storefronts to pull charts from, one per selected language.
	// Deduplicated because several languages can share a storefront.
	get regions(): string[] {
		return [...new Set(this.languages.map(regionForLanguage))];
	}

	#persistLanguages() {
		try {
			localStorage.setItem(scopedKey(LANGUAGES_KEY), JSON.stringify(this.languages));
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
			localStorage.setItem(scopedKey(UI_LANGUAGE_KEY), locale);
		} catch (_) {}
		this.#touch();
	}

	setDateFormat(mode: DateFormat) {
		this.dateFormat = mode;
		try {
			localStorage.setItem(scopedKey(KEY), mode);
		} catch (_) {}
		this.#touch();
	}

	setVolumeBoost(enabled: boolean) {
		this.volumeBoost = enabled;
		try {
			localStorage.setItem(scopedKey(VOLUME_BOOST_KEY), enabled ? '1' : '0');
		} catch (_) {}
		this.#touch();
	}

	setSkipSilence(enabled: boolean) {
		this.skipSilence = enabled;
		try {
			localStorage.setItem(scopedKey(SKIP_SILENCE_KEY), enabled ? '1' : '0');
		} catch (_) {}
		this.#touch();
	}

	setPlaybackSpeed(speed: number) {
		this.playbackSpeed = storedPlaybackSpeed(speed);
		try {
			localStorage.setItem(scopedKey(PLAYBACK_SPEED_KEY), String(this.playbackSpeed));
		} catch (_) {}
		this.#touch();
	}

	setStartScreen(value: StartScreen) {
		this.startScreen = value;
		try { localStorage.setItem(scopedKey(START_SCREEN_KEY), value); } catch (_) {}
		this.#touch();
	}

	setVisualizer(value: VisualizerStyle) {
		this.visualizer = value;
		try { localStorage.setItem(scopedKey(VISUALIZER_KEY), value); } catch (_) {}
		this.#touch();
	}

	setProxyImages(enabled: boolean) {
		this.proxyImages = enabled;
		try { localStorage.setItem(scopedKey(PROXY_IMAGES_KEY), enabled ? '1' : '0'); } catch (_) {}
		this.#touch();
	}

	setDownloadWifiOnly(enabled: boolean) {
		this.downloadWifiOnly = enabled;
		try { localStorage.setItem(scopedKey(DOWNLOAD_WIFI_ONLY_KEY), enabled ? '1' : '0'); } catch (_) {}
		this.#touch();
	}

	setAutoDownloadCount(value: number) {
		this.autoDownloadCount = clampedNumber(value, this.autoDownloadCount, 1, 10);
		try { localStorage.setItem(scopedKey(AUTO_DOWNLOAD_COUNT_KEY), String(this.autoDownloadCount)); } catch (_) {}
		this.#touch();
	}

	setDownloadRetention(value: DownloadRetention) {
		this.downloadRetention = value;
		try { localStorage.setItem(scopedKey(DOWNLOAD_RETENTION_KEY), value); } catch (_) {}
		this.#touch();
	}

	setDownloadConcurrency(value: number) {
		this.downloadConcurrency = clampedNumber(value, this.downloadConcurrency, 1, 4);
		try { localStorage.setItem(scopedKey(DOWNLOAD_CONCURRENCY_KEY), String(this.downloadConcurrency)); } catch (_) {}
		this.#touch();
	}

	setDownloadBudgetBytes(value: number) {
		this.downloadBudgetBytes = clampedNumber(
			value,
			this.downloadBudgetBytes,
			0,
			10 * 1024 * 1024 * 1024
		);
		try { localStorage.setItem(scopedKey(DOWNLOAD_BUDGET_BYTES_KEY), String(this.downloadBudgetBytes)); } catch (_) {}
		this.#touch();
	}

	/**
	 * Appearance is applied by `theme.ts` (which the boot script also reads, to
	 * avoid a flash of the wrong theme); these wrappers exist so changing it also
	 * marks the settings blob dirty and reaches the account's other devices.
	 */
	setThemeMode(mode: ThemeMode) {
		setTheme(mode);
		this.#touch();
	}

	setPaletteId(palette: PaletteId) {
		setPalette(palette);
		this.#touch();
	}

	setDefaultInboxMode(mode: DefaultInboxMode) {
		this.defaultInboxMode = mode;
		try {
			localStorage.setItem(scopedKey(DEFAULT_INBOX_MODE_KEY), mode);
		} catch (_) {}
		this.#touch();
	}

	#persistInterests() {
		try {
			localStorage.setItem(scopedKey(INTERESTS_KEY), JSON.stringify(this.interests));
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
			localStorage.setItem(scopedKey(HIDDEN_KEY), JSON.stringify(this.hiddenGenres));
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

	#persistHiddenPodcasts() {
		try {
			localStorage.setItem(scopedKey(HIDDEN_PODCASTS_KEY), JSON.stringify(this.hiddenPodcasts));
		} catch (_) {}
	}

	hidePodcast(podcast: { feedUrl?: string; id?: string; title: string }) {
		const key = podcastPreferenceKey(podcast.feedUrl, podcast.id);
		if (key === 'id:') return;
		this.hiddenPodcasts = [
			...this.hiddenPodcasts.filter((item) => item.key !== key),
			{ key, title: podcast.title.trim() || key }
		];
		this.#persistHiddenPodcasts();
		this.#touch();
	}

	unhidePodcast(key: string) {
		this.hiddenPodcasts = this.hiddenPodcasts.filter((item) => item.key !== key);
		this.#persistHiddenPodcasts();
		this.#touch();
	}

	syncPayload() {
		return mergeForeignSettings(
			{
				date_format: this.dateFormat,
				// Appearance lives in unscoped localStorage so the boot script can
				// apply it before first paint, but it still belongs to the account:
				// the Android client has always synced both, and a browser that
				// never sent them made every push delete them from the server.
				theme_mode: getStoredTheme(),
				palette: getStoredPalette(),
				interests: [...this.interests],
				hidden_genres: [...this.hiddenGenres],
				hidden_podcasts: this.hiddenPodcasts.map((podcast) => ({ ...podcast })),
				default_inbox_mode: this.defaultInboxMode,
				languages: [...this.languages],
				ui_language: this.uiLanguage,
				volume_boost: this.volumeBoost,
				skip_silence: this.skipSilence,
				playback_speed: this.playbackSpeed,
				start_screen: this.startScreen,
				visualizer: this.visualizer,
				proxy_images: this.proxyImages,
				download_wifi_only: this.downloadWifiOnly,
				auto_download_count: this.autoDownloadCount,
				download_retention: this.downloadRetention,
				download_concurrency: this.downloadConcurrency,
				download_budget_bytes: this.downloadBudgetBytes,
				updated_at: this.updatedAt
			},
			this.#foreignSettings
		);
	}

	resetSynced() {
		this.#foreignSettings = {};
		this.dateFormat = 'absolute';
		this.interests = [];
		this.hiddenGenres = [];
		this.hiddenPodcasts = [];
		this.defaultInboxMode = 'all';
		this.languages = detectBrowserLanguages();
		this.uiLanguage = initialUILanguage(this.languages);
		this.volumeBoost = false;
		this.skipSilence = false;
		this.playbackSpeed = 1;
		this.startScreen = 'discover';
		this.visualizer = 'off';
		this.proxyImages = true;
		this.downloadWifiOnly = true;
		this.autoDownloadCount = 3;
		this.downloadRetention = 'keep';
		this.downloadConcurrency = 2;
		this.downloadBudgetBytes = 2_048 * 1024 * 1024;
		this.updatedAt = 0;
		try {
			for (const baseKey of ACCOUNT_SCOPED_KEYS) {
				localStorage.removeItem(scopedKey(baseKey));
			}
		} catch (_) {}
	}

	applySynced(payload: Record<string, unknown>, options: { authoritative?: boolean } = {}) {
		const updatedAt = Math.max(0, Number(payload.updated_at) || 0);
		if (!updatedAt || (!options.authoritative && this.updatedAt >= updatedAt)) return;
		// Recorded only once the payload has won, so the snapshot stays in step with
		// the accepted updated_at rather than resurrecting keys from a stale write.
		this.#foreignSettings = foreignSettingsOf(payload);
		const languages = normalizeLanguageList(Array.isArray(payload.languages) ? payload.languages : []);
		if (payload.date_format === 'relative' || payload.date_format === 'absolute') {
			this.dateFormat = payload.date_format;
		}
		if (payload.theme_mode === 'dark' || payload.theme_mode === 'light' || payload.theme_mode === 'system') {
			setTheme(payload.theme_mode as ThemeMode);
		}
		if (typeof payload.palette === 'string' && isPaletteId(payload.palette)) {
			setPalette(payload.palette);
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
		if (Array.isArray(payload.hidden_podcasts)) {
			this.hiddenPodcasts = payload.hidden_podcasts.filter(
				(item): item is HiddenPodcastPreference =>
					typeof item === 'object' &&
					item !== null &&
					typeof (item as Record<string, unknown>).key === 'string' &&
					typeof (item as Record<string, unknown>).title === 'string'
			);
		}
		if (payload.default_inbox_mode === 'all' || payload.default_inbox_mode === 'latest') {
			this.defaultInboxMode = payload.default_inbox_mode;
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
		if (payload.playback_speed !== null && payload.playback_speed !== undefined) {
			this.playbackSpeed = storedPlaybackSpeed(payload.playback_speed);
		}
		if (payload.start_screen === 'discover' || payload.start_screen === 'inbox' || payload.start_screen === 'library') {
			this.startScreen = payload.start_screen;
		}
		// A peer on an older build may still send the retired "dots"; treat it the
		// same way a stored value is treated.
		if (payload.visualizer === 'dots') {
			this.visualizer = 'bars';
		} else if (payload.visualizer === 'off' || payload.visualizer === 'level' || payload.visualizer === 'waveform' || payload.visualizer === 'bars' || payload.visualizer === 'pulse') {
			this.visualizer = payload.visualizer;
		}
		if (typeof payload.proxy_images === 'boolean') this.proxyImages = payload.proxy_images;
		if (typeof payload.download_wifi_only === 'boolean') this.downloadWifiOnly = payload.download_wifi_only;
		if (payload.auto_download_count !== null && payload.auto_download_count !== undefined) {
			this.autoDownloadCount = clampedNumber(payload.auto_download_count, this.autoDownloadCount, 1, 10);
		}
		if (payload.download_retention === 'keep' || payload.download_retention === 'finished' || payload.download_retention === '7d' || payload.download_retention === '14d' || payload.download_retention === '30d') {
			this.downloadRetention = payload.download_retention;
		}
		if (payload.download_concurrency !== null && payload.download_concurrency !== undefined) {
			this.downloadConcurrency = clampedNumber(payload.download_concurrency, this.downloadConcurrency, 1, 4);
		}
		if (payload.download_budget_bytes !== null && payload.download_budget_bytes !== undefined) {
			this.downloadBudgetBytes = clampedNumber(
				payload.download_budget_bytes,
				this.downloadBudgetBytes,
				0,
				10 * 1024 * 1024 * 1024
			);
		}
		this.updatedAt = updatedAt;
		try {
			localStorage.setItem(scopedKey(KEY), this.dateFormat);
			localStorage.setItem(scopedKey(LANGUAGES_KEY), JSON.stringify(this.languages));
			localStorage.setItem(scopedKey(INTERESTS_KEY), JSON.stringify(this.interests));
			localStorage.setItem(scopedKey(HIDDEN_KEY), JSON.stringify(this.hiddenGenres));
			localStorage.setItem(
				scopedKey(HIDDEN_PODCASTS_KEY),
				JSON.stringify(this.hiddenPodcasts)
			);
			localStorage.setItem(scopedKey(DEFAULT_INBOX_MODE_KEY), this.defaultInboxMode);
			localStorage.setItem(scopedKey(UI_LANGUAGE_KEY), this.uiLanguage);
			localStorage.setItem(scopedKey(VOLUME_BOOST_KEY), this.volumeBoost ? '1' : '0');
			localStorage.setItem(scopedKey(SKIP_SILENCE_KEY), this.skipSilence ? '1' : '0');
			localStorage.setItem(scopedKey(PLAYBACK_SPEED_KEY), String(this.playbackSpeed));
			localStorage.setItem(scopedKey(START_SCREEN_KEY), this.startScreen);
			localStorage.setItem(scopedKey(VISUALIZER_KEY), this.visualizer);
			localStorage.setItem(scopedKey(PROXY_IMAGES_KEY), this.proxyImages ? '1' : '0');
			localStorage.setItem(scopedKey(DOWNLOAD_WIFI_ONLY_KEY), this.downloadWifiOnly ? '1' : '0');
			localStorage.setItem(scopedKey(AUTO_DOWNLOAD_COUNT_KEY), String(this.autoDownloadCount));
			localStorage.setItem(scopedKey(DOWNLOAD_RETENTION_KEY), this.downloadRetention);
			localStorage.setItem(scopedKey(DOWNLOAD_CONCURRENCY_KEY), String(this.downloadConcurrency));
			localStorage.setItem(scopedKey(DOWNLOAD_BUDGET_BYTES_KEY), String(this.downloadBudgetBytes));
			localStorage.setItem(scopedKey(SETTINGS_UPDATED_AT_KEY), String(this.updatedAt));
			localStorage.setItem(
				scopedKey(FOREIGN_SETTINGS_KEY),
				JSON.stringify(this.#foreignSettings)
			);
		} catch (_) {}
	}

	// True if a podcast's categories intersect the hidden set.
	isHidden(categories: string[] | undefined): boolean {
		if (!categories || this.hiddenGenres.length === 0) return false;
		const lower = this.hiddenGenres.map((g) => g.toLowerCase());
		return categories.some((c) => lower.includes((c || '').toLowerCase()));
	}

	isPodcastHidden(feedUrl?: string, id?: string): boolean {
		const key = podcastPreferenceKey(feedUrl, id);
		return this.hiddenPodcasts.some((podcast) => podcast.key === key);
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
