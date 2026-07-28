import { switchLocalDataContext, wasGuestContextActive } from '$lib/idb/db';
import { player } from '$lib/stores/player.svelte';
import { sync } from '$lib/stores/sync.svelte';

let transition: Promise<void> = Promise.resolve();

export function activateAccountContext(
	userId: string | null,
	options: { migrateGuest?: boolean } = {}
): Promise<void> {
	sync.disable();
	transition = transition.then(async () => {
		await switchLocalDataContext(userId, options);
		await player.loadContext();
		if (userId) sync.enable(userId);
	});
	return transition;
}

export function activateLoggedInAccount(userId: string): Promise<void> {
	return activateAccountContext(userId, { migrateGuest: wasGuestContextActive() });
}
