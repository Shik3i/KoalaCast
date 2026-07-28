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

export async function downloadAudio(episode: DownloadableEpisode): Promise<void> {
	if (!episode.enclosure_url) throw new Error('Episode has no audio URL');
	const response = await fetch(
		`/api/v1/proxy/audio?url=${encodeURIComponent(episode.enclosure_url)}`,
		{ cache: 'no-store' }
	);
	if (!response.ok) throw new Error(`Audio download failed: ${response.status}`);
	const cache = await caches.open(AUDIO_DOWNLOAD_CACHE);
	await cache.put(offlineAudioPath(episode.episode_id), response);
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
