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

export async function purgeAudioDownloadsForOwner(owner: string | null): Promise<void> {
	if (typeof caches === 'undefined') return;
	await caches.delete(audioDownloadCacheName(owner));
}

export async function purgeAudioDownloadsExcept(owner: string | null): Promise<void> {
	if (typeof caches === 'undefined') return;
	const keep = audioDownloadCacheName(owner);
	const names = await caches.keys();
	await Promise.all(
		names
			.filter((name) => name.startsWith(`${AUDIO_DOWNLOAD_CACHE_PREFIX}-`) && name !== keep)
			.map((name) => caches.delete(name))
	);
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
	// A migration is a move, not a copy. Leaving the originals behind doubled the
	// storage an episode occupied and left audio in a namespace nothing would ever
	// clean up again.
	await caches.delete(audioDownloadCacheName(null));
}

/**
 * Removes every audio-download cache this origin holds, for every account that
 * ever signed in here. Used by "delete all local data", which previously left
 * gigabytes of downloaded audio in place.
 */
export async function purgeAllAudioDownloads(): Promise<void> {
	if (typeof caches === 'undefined') return;
	const names = await caches.keys();
	await Promise.all(
		names
			.filter((name) => name.startsWith(`${AUDIO_DOWNLOAD_CACHE_PREFIX}-`))
			.map((name) => caches.delete(name))
	);
}
