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

function key(podcastId: string) {
	return `${KEY_PREFIX}${podcastId}`;
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
	for (let index = 0; index < localStorage.length; index++) {
		const storageKey = localStorage.key(index);
		if (!storageKey?.startsWith(KEY_PREFIX)) continue;
		const podcastId = storageKey.slice(KEY_PREFIX.length);
		if (podcastId) result.push({ podcastId, ...getPodcastPlaybackSettings(podcastId) });
	}
	return result;
}

export function applySyncedPodcastPlaybackSettings(
	podcastId: string,
	incoming: Partial<PodcastPlaybackSettings>
) {
	if (typeof localStorage === 'undefined' || !podcastId) return;
	const normalized = normalize(incoming);
	if (getPodcastPlaybackSettings(podcastId).updatedAt >= normalized.updatedAt) return;
	try {
		localStorage.setItem(key(podcastId), JSON.stringify(normalized));
	} catch (_) {}
}
