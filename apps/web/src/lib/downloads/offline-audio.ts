export const AUDIO_DOWNLOAD_CACHE_PREFIX = 'koalacast-audio-downloads-v2';
let activeOwner: string | null = null;

function ownerSegment(owner = activeOwner): string {
	return owner ? `account-${encodeURIComponent(owner)}` : 'guest';
}

export function activateOfflineAudioContext(userId: string | null) {
	activeOwner = userId;
}

export function audioDownloadCacheName(owner = activeOwner): string {
	return `${AUDIO_DOWNLOAD_CACHE_PREFIX}-${ownerSegment(owner)}`;
}

export function audioDownloadCacheNameForOfflinePath(pathname: string): string | null {
	const match = /^\/offline\/audio\/([^/]+)\//.exec(pathname);
	return match ? `${AUDIO_DOWNLOAD_CACHE_PREFIX}-${match[1]}` : null;
}

export interface DownloadableEpisode {
	episode_id: string;
	enclosure_url: string;
}

export function offlineAudioPath(episodeId: string, owner = activeOwner): string {
	return `/offline/audio/${ownerSegment(owner)}/${encodeURIComponent(episodeId)}`;
}

export async function isAudioDownloaded(episodeId: string): Promise<boolean> {
	if (typeof caches === 'undefined') return false;
	const cache = await caches.open(audioDownloadCacheName());
	return Boolean(await cache.match(offlineAudioPath(episodeId)));
}

export async function removeAudioDownload(episodeId: string): Promise<void> {
	if (typeof caches === 'undefined') return;
	const cache = await caches.open(audioDownloadCacheName());
	await cache.delete(offlineAudioPath(episodeId));
}

export async function resolveOfflineAudioUrl(
	episodeId: string,
	fallbackUrl: string
): Promise<string> {
	return (await isAudioDownloaded(episodeId)) ? offlineAudioPath(episodeId) : fallbackUrl;
}

export async function migrateGuestAudioDownloads(
	userId: string,
	episodeIds: string[]
): Promise<void> {
	if (typeof caches === 'undefined' || episodeIds.length === 0) return;
	const guestCache = await caches.open(audioDownloadCacheName(null));
	const accountCache = await caches.open(audioDownloadCacheName(userId));
	for (const episodeId of episodeIds) {
		const response = await guestCache.match(offlineAudioPath(episodeId, null));
		if (response) await accountCache.put(offlineAudioPath(episodeId, userId), response);
	}
}
