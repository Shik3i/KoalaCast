export interface PodcastPlaybackSettings {
	skipIntroSeconds: number;
	skipOutroSeconds: number;
	speed: number | null;
	autoQueueNew: boolean;
	notifyNewEpisodes: boolean;
}

const defaults: PodcastPlaybackSettings = {
	skipIntroSeconds: 0,
	skipOutroSeconds: 0,
	speed: null,
	autoQueueNew: false,
	notifyNewEpisodes: false
};

function key(podcastId: string) {
	return `koalacast_podcast_settings_${podcastId}`;
}

export function getPodcastPlaybackSettings(podcastId: string): PodcastPlaybackSettings {
	if (typeof localStorage === 'undefined' || !podcastId) return { ...defaults };
	try {
		const parsed = JSON.parse(localStorage.getItem(key(podcastId)) || '{}');
		return {
			skipIntroSeconds: Math.max(0, Math.min(600, Number(parsed.skipIntroSeconds) || 0)),
			skipOutroSeconds: Math.max(0, Math.min(600, Number(parsed.skipOutroSeconds) || 0)),
			speed: parsed.speed == null ? null : Math.max(.25, Math.min(4, Number(parsed.speed) || 1)),
			autoQueueNew: !!parsed.autoQueueNew,
			notifyNewEpisodes: !!parsed.notifyNewEpisodes
		};
	} catch {
		return { ...defaults };
	}
}

export function savePodcastPlaybackSettings(podcastId: string, settings: PodcastPlaybackSettings) {
	if (typeof localStorage === 'undefined' || !podcastId) return;
	try {
		localStorage.setItem(key(podcastId), JSON.stringify(settings));
	} catch (_) {}
}
