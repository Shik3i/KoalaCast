import { switchLocalDataContext, wasGuestContextActive } from '$lib/idb/db';
import { player } from '$lib/stores/player.svelte';
import { sync } from '$lib/stores/sync.svelte';
import { prefs } from '$lib/stores/prefs.svelte';
import { activatePodcastSettingsContext } from '$lib/stores/podcast-settings';
import { audioDownloads } from '$lib/downloads/manager.svelte';

let transition: Promise<void> = Promise.resolve();
const LAST_ACCOUNT_KEY = 'koalacast_last_account_id';

export function getLastAccountContext(): string | null {
	if (typeof localStorage === 'undefined') return null;
	try {
		return localStorage.getItem(LAST_ACCOUNT_KEY);
	} catch {
		return null;
	}
}

export function activateAccountContext(
	userId: string | null,
	options: { migrateGuest?: boolean } = {}
): Promise<void> {
	sync.disable();
	transition = transition.then(async () => {
		if (player.isActive || player.isPlaying) await player.stop();
		await switchLocalDataContext(userId, options);
		prefs.activateContext(userId, options);
		player.activatePreferences(prefs.playbackSpeed);
		activatePodcastSettingsContext(userId, options);
		await audioDownloads.activateContext(userId, options);
		await player.loadContext();
		try {
			if (userId) localStorage.setItem(LAST_ACCOUNT_KEY, userId);
			else localStorage.removeItem(LAST_ACCOUNT_KEY);
		} catch (_) {}
		if (userId) sync.enable(userId);
	});
	return transition;
}

export function activateLoggedInAccount(userId: string): Promise<void> {
	return activateAccountContext(userId, { migrateGuest: wasGuestContextActive() });
}
