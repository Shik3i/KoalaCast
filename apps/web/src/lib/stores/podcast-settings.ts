export interface PodcastPlaybackSettings {
	skipIntroSeconds: number;
	skipOutroSeconds: number;
	speed: number | null;
	volumeBoost: boolean | null;
	skipSilence: boolean | null;
	autoQueueNew: boolean;
	autoDownload: boolean;
	notifyNewEpisodes: boolean;
	updatedAt: number;
}

const defaults: PodcastPlaybackSettings = {
	skipIntroSeconds: 0,
	skipOutroSeconds: 0,
	speed: null,
	volumeBoost: null,
	skipSilence: null,
	autoQueueNew: false,
	autoDownload: false,
	notifyNewEpisodes: false,
	updatedAt: 0
};

const KEY_PREFIX = 'koalacast_podcast_settings_';
const GUEST_MIGRATION_KEY = 'koalacast_guest_podcast_settings_migrated';
let activeOwner: string | null = null;

function key(podcastId: string) {
	return activeOwner
		? `${KEY_PREFIX}account_${encodeURIComponent(activeOwner)}_${podcastId}`
		: `${KEY_PREFIX}${podcastId}`;
}

function activePrefix() {
	return activeOwner
		? `${KEY_PREFIX}account_${encodeURIComponent(activeOwner)}_`
		: KEY_PREFIX;
}

export function activatePodcastSettingsContext(
	userId: string | null,
	options: { migrateGuest?: boolean } = {}
) {
	if (typeof localStorage === 'undefined') {
		activeOwner = userId;
		return;
	}
	if (
		userId &&
		options.migrateGuest &&
		localStorage.getItem(GUEST_MIGRATION_KEY) !== '1'
	) {
		const targetPrefix = `${KEY_PREFIX}account_${encodeURIComponent(userId)}_`;
		const legacy: Array<[string, string]> = [];
		for (let index = 0; index < localStorage.length; index++) {
			const storageKey = localStorage.key(index);
			if (!storageKey?.startsWith(KEY_PREFIX) || storageKey.startsWith(`${KEY_PREFIX}account_`)) continue;
			const value = localStorage.getItem(storageKey);
			if (value !== null) legacy.push([storageKey.slice(KEY_PREFIX.length), value]);
		}
		for (const [podcastId, value] of legacy) {
			const targetKey = `${targetPrefix}${podcastId}`;
			if (localStorage.getItem(targetKey) === null) localStorage.setItem(targetKey, value);
		}
		localStorage.setItem(GUEST_MIGRATION_KEY, '1');
	}
	activeOwner = userId;
}

function normalize(parsed: Partial<PodcastPlaybackSettings>): PodcastPlaybackSettings {
	return {
		skipIntroSeconds: Math.max(0, Math.min(600, Number(parsed.skipIntroSeconds) || 0)),
		skipOutroSeconds: Math.max(0, Math.min(600, Number(parsed.skipOutroSeconds) || 0)),
		speed: parsed.speed == null ? null : Math.max(0.25, Math.min(4, Number(parsed.speed) || 1)),
		volumeBoost: typeof parsed.volumeBoost === 'boolean' ? parsed.volumeBoost : null,
		skipSilence: typeof parsed.skipSilence === 'boolean' ? parsed.skipSilence : null,
		autoQueueNew: !!parsed.autoQueueNew,
		autoDownload: !!parsed.autoDownload,
		notifyNewEpisodes: !!parsed.notifyNewEpisodes,
		updatedAt: Math.max(0, Number(parsed.updatedAt) || 0)
	};
}

export function getPodcastPlaybackSettings(podcastId: string): PodcastPlaybackSettings {
	if (typeof localStorage === 'undefined' || !podcastId) return { ...defaults };
	try {
		return normalize(JSON.parse(localStorage.getItem(key(podcastId)) || '{}'));
	} catch {
		return { ...defaults };
	}
}

export function savePodcastPlaybackSettings(podcastId: string, settings: PodcastPlaybackSettings) {
	if (typeof localStorage === 'undefined' || !podcastId) return;
	try {
		localStorage.setItem(key(podcastId), JSON.stringify(normalize({ ...settings, updatedAt: Date.now() })));
	} catch (_) {}
}

export function getAllPodcastPlaybackSettings(): Array<PodcastPlaybackSettings & { podcastId: string }> {
	if (typeof localStorage === 'undefined') return [];
	const result: Array<PodcastPlaybackSettings & { podcastId: string }> = [];
	const prefix = activePrefix();
	for (let index = 0; index < localStorage.length; index++) {
		const storageKey = localStorage.key(index);
		if (!storageKey?.startsWith(prefix)) continue;
		if (!activeOwner && storageKey.startsWith(`${KEY_PREFIX}account_`)) continue;
		const podcastId = storageKey.slice(prefix.length);
		if (podcastId) result.push({ podcastId, ...getPodcastPlaybackSettings(podcastId) });
	}
	return result;
}

export function applySyncedPodcastPlaybackSettings(
	podcastId: string,
	incoming: Partial<PodcastPlaybackSettings>,
	options: { authoritative?: boolean } = {}
) {
	if (typeof localStorage === 'undefined' || !podcastId) return;
	const normalized = normalize(incoming);
	if (!options.authoritative && getPodcastPlaybackSettings(podcastId).updatedAt >= normalized.updatedAt) return;
	try {
		localStorage.setItem(key(podcastId), JSON.stringify(normalized));
	} catch (_) {}
}

export function clearPodcastPlaybackSettingsContext() {
	if (typeof localStorage === 'undefined') return;
	const prefix = activePrefix();
	const keys: string[] = [];
	for (let index = 0; index < localStorage.length; index++) {
		const storageKey = localStorage.key(index);
		if (!storageKey?.startsWith(prefix)) continue;
		if (!activeOwner && storageKey.startsWith(`${KEY_PREFIX}account_`)) continue;
		keys.push(storageKey);
	}
	for (const storageKey of keys) localStorage.removeItem(storageKey);
}
