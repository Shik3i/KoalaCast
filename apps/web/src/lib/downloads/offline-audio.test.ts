import { describe, expect, it } from 'vitest';
import {
	AUDIO_DOWNLOAD_CACHE_PREFIX,
	audioDownloadCacheName,
	audioDownloadCacheNameForOfflinePath,
	offlineAudioPath
} from './offline-audio';

describe('account-scoped offline audio caches', () => {
	it('maps an account path to the matching cache', () => {
		const owner = 'user/with space';
		const path = offlineAudioPath('episode 1', owner);

		expect(audioDownloadCacheNameForOfflinePath(path)).toBe(audioDownloadCacheName(owner));
		expect(audioDownloadCacheNameForOfflinePath(path)).toBe(
			`${AUDIO_DOWNLOAD_CACHE_PREFIX}-account-user%2Fwith%20space`
		);
	});

	it('rejects paths without an owner namespace', () => {
		expect(audioDownloadCacheNameForOfflinePath('/offline/audio/episode')).toBeNull();
	});
});
