import { clearAllLocalData, switchLocalDataContext, wasGuestContextActive } from '$lib/idb/db';
import { player } from '$lib/stores/player.svelte';
import { sync } from '$lib/stores/sync.svelte';
import { prefs } from '$lib/stores/prefs.svelte';
import {
	activatePodcastSettingsContext,
	clearPodcastPlaybackSettingsContext
} from '$lib/stores/podcast-settings';
import { audioDownloads } from '$lib/downloads/manager.svelte';
import { clearWatchedFeeds } from '$lib/background/feed-mirror';
import { disableBrowserNotifications } from '$lib/notifications/browser';

let transition: Promise<void> = Promise.resolve();
let requestedUserId: string | null | undefined;
let activeUserId: string | null | undefined;
let resolveInitialContext!: () => void;
const initialContext = new Promise<void>((resolve) => {
	resolveInitialContext = resolve;
});
let initialContextResolved = false;
const LAST_ACCOUNT_KEY = 'koalacast_last_account_id';

/**
 * Wait until the root layout has verified the HttpOnly session (or selected the
 * offline fallback) and switched IndexedDB to that listener. Route onMount
 * callbacks otherwise race the auth request and permanently render the guest DB
 * until the component is remounted.
 */
export async function waitForAccountContext(): Promise<void> {
	await initialContext;
	await transition;
}

function markInitialContextReady(): void {
	if (initialContextResolved) return;
	initialContextResolved = true;
	resolveInitialContext();
}

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
	// Route components may independently confirm the same authenticated session.
	// Re-entering an already requested context used to stop active playback before
	// reopening the exact same IndexedDB database.
	if (requestedUserId === userId) return transition;
	requestedUserId = userId;
	sync.disable();
	transition = transition.then(async () => {
		if (activeUserId === userId) return;
		if (player.isActive || player.isPlaying) await player.stop();
		// The background mirror belongs to whoever was signed in; the next Inbox
		// refresh rebuilds it for this listener.
		await clearWatchedFeeds();
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
		activeUserId = userId;
		if (userId) sync.enable(userId);
		markInitialContextReady();
	});
	// Without this the chain stays rejected for the rest of the session: every later
	// `.then()` is skipped, so signing in or out silently stops working until the
	// page is reloaded. Resetting `requestedUserId` also lets the same context be
	// requested again rather than being deduplicated against a switch that failed.
	transition = transition.catch((error) => {
		requestedUserId = activeUserId;
		console.error('account context switch failed', error);
	});
	return transition;
}

export function activateLoggedInAccount(userId: string): Promise<void> {
	return activateAccountContext(userId, { migrateGuest: wasGuestContextActive() });
}

/**
 * Everything this browser holds about the listener, gone.
 *
 * It used to mean "empty ten IndexedDB stores": the downloaded audio (which is
 * by far the largest and the most personal thing stored), every `koalacast_*`
 * preference and the per-show settings all survived a reset the UI described as
 * deleting all local data. It also left the running stores untouched, so the
 * screen kept rendering records that no longer existed.
 */
export async function resetAllLocalData(): Promise<void> {
	if (player.isActive || player.isPlaying) await player.stop();
	await disableBrowserNotifications();
	await clearAllLocalData();
	await audioDownloads.clearAll();
	await clearWatchedFeeds();
	clearPodcastPlaybackSettingsContext();
	prefs.resetSynced();
	await player.loadContext();
}
