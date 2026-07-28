export const AUDIO_DOWNLOAD_CACHE = 'koalacast-audio-downloads-v1';

export interface DownloadableEpisode {
	episode_id: string;
	enclosure_url: string;
}

export function offlineAudioPath(episodeId: string): string {
	return `/offline/audio/${encodeURIComponent(episodeId)}`;
}

export async function isAudioDownloaded(episodeId: string): Promise<boolean> {
	if (typeof caches === 'undefined') return false;
	const cache = await caches.open(AUDIO_DOWNLOAD_CACHE);
	return Boolean(await cache.match(offlineAudioPath(episodeId)));
}

export async function removeAudioDownload(episodeId: string): Promise<void> {
	if (typeof caches === 'undefined') return;
	const cache = await caches.open(AUDIO_DOWNLOAD_CACHE);
	await cache.delete(offlineAudioPath(episodeId));
}

export async function resolveOfflineAudioUrl(
	episodeId: string,
	fallbackUrl: string
): Promise<string> {
	return (await isAudioDownloaded(episodeId)) ? offlineAudioPath(episodeId) : fallbackUrl;
}
