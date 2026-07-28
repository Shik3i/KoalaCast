// Global "now playing" store. A single Player instance is mounted in the root
// layout and reads from here; any page can start playback by calling
// player.play(track). Uses Svelte 5 runes so reads in components stay reactive.
//
// The store also owns the play queue (persisted in IndexedDB): the Player plays
// the next queued episode automatically when one ends, and any page can enqueue.

import {
	getLocalQueue,
	addToLocalQueueIfAbsent,
	addManyToLocalQueue,
	removeFromLocalQueue,
	clearLocalQueue,
	getLocalCurrentPlayback,
	saveLocalCurrentPlayback,
	type LocalQueueItem
} from '$lib/idb/db';
import { normalizePlaybackSpeed } from '$lib/player/playback-speed';
import { resolveOfflineAudioUrl } from '$lib/downloads/offline-audio';

export interface CurrentTrack {
	episode_id: string;
	podcast_id: string;
	title: string;
	podcast_title: string;
	artwork_url: string;
	enclosure_url: string;
	duration_ms: number;
	categories?: string[];
}

function itemToTrack(i: LocalQueueItem): CurrentTrack {
	return {
		episode_id: i.episode_id,
		podcast_id: i.podcast_id,
		title: i.title,
		podcast_title: i.podcast_title ?? '',
		artwork_url: i.artwork_url,
		enclosure_url: i.enclosure_url,
		duration_ms: i.duration_ms,
		categories: i.categories
	};
}

class PlayerStore {
	current = $state<CurrentTrack | null>(null);
	// Bumped every time playback is (re)requested, so the Player can autoplay even
	// when the same track is selected again.
	playToken = $state(0);
	// The upcoming episodes, mirrored from IndexedDB (never includes what's playing).
	queue = $state<CurrentTrack[]>([]);
	volume = $state(1);
	playbackSpeed = $state(1);
	defaultPlaybackSpeed = $state(1);
	sleepTimerEndsAt = $state<number | null>(null);
	sleepAtEpisodeEnd = $state(false);
	sleepAtChapterEnd = $state(false);
	isPlaying = $state(false);
	positionMs = $state(0);
	durationMs = $state(0);
	positionUpdatedAt = $state(Date.now());

	play(track: CurrentTrack) {
		this.current = track;
		this.positionMs = 0;
		this.durationMs = track.duration_ms;
		this.positionUpdatedAt = Date.now();
		this.playToken++;
		void saveLocalCurrentPlayback({ ...track, position_ms: 0 }).catch(() => {});
		void resolveOfflineAudioUrl(track.episode_id, track.enclosure_url).then((resolvedUrl) => {
			if (resolvedUrl === track.enclosure_url || this.current?.episode_id !== track.episode_id) return;
			this.current = { ...this.current, enclosure_url: resolvedUrl };
			this.playToken++;
		});
	}

	stop() {
		this.current = null;
		this.isPlaying = false;
		this.positionMs = 0;
		this.durationMs = 0;
		this.positionUpdatedAt = Date.now();
		void saveLocalCurrentPlayback(null).catch(() => {});
	}

	get isActive() {
		return this.current !== null;
	}

	get upNext(): CurrentTrack | null {
		return this.queue[0] ?? null;
	}

	setVolume(v: number) {
		this.volume = Math.max(0, Math.min(1, v));
		try {
			localStorage.setItem('koalacast_volume', String(this.volume));
		} catch (_) {}
	}

	setPlaybackSpeed(speed: number, persist = true) {
		this.playbackSpeed = normalizePlaybackSpeed(speed);
		if (persist) this.defaultPlaybackSpeed = this.playbackSpeed;
		if (!persist) return;
		try {
			localStorage.setItem('koalacast_playback_speed', String(this.playbackSpeed));
		} catch (_) {}
	}

	updatePosition(positionMs: number, durationMs: number) {
		this.positionMs = Math.max(0, positionMs);
		this.durationMs = Math.max(0, durationMs);
		this.positionUpdatedAt = Date.now();
	}

	setSleepTimer(value: string) {
		this.sleepAtEpisodeEnd = value === 'episode';
		this.sleepAtChapterEnd = value === 'chapter';
		if (value === '' || value === 'episode' || value === 'chapter') this.sleepTimerEndsAt = null;
		else this.sleepTimerEndsAt = Date.now() + Number(value) * 60_000;
	}

	async loadQueue() {
		const items = await getLocalQueue();
		this.queue = items
			.filter((i) => i.episode_id !== this.current?.episode_id)
			.map(itemToTrack);
	}

	async loadContext() {
		this.isPlaying = false;
		this.playToken = 0;
		const [items, saved] = await Promise.all([getLocalQueue(), getLocalCurrentPlayback()]);
		this.current = saved ? itemToTrack({ ...saved, id: '', position_order: 0, added_at: 0 }) : null;
		this.positionMs = saved?.position_ms ?? 0;
		this.durationMs = saved?.duration_ms ?? 0;
		this.positionUpdatedAt = Date.now();
		this.queue = items
			.filter((item) => item.episode_id !== this.current?.episode_id)
			.map(itemToTrack);
	}

	async addToQueue(track: CurrentTrack) {
		await addToLocalQueueIfAbsent({
			id: crypto.randomUUID(),
			episode_id: track.episode_id,
			podcast_id: track.podcast_id,
			title: track.title,
			podcast_title: track.podcast_title,
			artwork_url: track.artwork_url,
			enclosure_url: track.enclosure_url,
			duration_ms: track.duration_ms,
			categories: track.categories,
			position_order: Date.now(),
			added_at: Date.now()
		});
		await this.loadQueue();
	}

	async playNextAfterCurrent(track: CurrentTrack) {
		const items = await getLocalQueue();
		const firstPosition = items[0]?.position_order ?? Date.now();
		await addToLocalQueueIfAbsent({
			id: crypto.randomUUID(),
			episode_id: track.episode_id,
			podcast_id: track.podcast_id,
			title: track.title,
			podcast_title: track.podcast_title,
			artwork_url: track.artwork_url,
			enclosure_url: track.enclosure_url,
			duration_ms: track.duration_ms,
			categories: track.categories,
			position_order: firstPosition - 1,
			added_at: Date.now()
		});
		await this.loadQueue();
	}

	async addManyToQueue(tracks: CurrentTrack[]) {
		const existing = new Set((await getLocalQueue()).map((item) => item.episode_id));
		const now = Date.now();
		await addManyToLocalQueue(
			tracks
				.filter((track) => !existing.has(track.episode_id))
				.map((track, index) => ({
					id: crypto.randomUUID(),
					...track,
					position_order: now + index,
					added_at: now + index
				}))
		);
		await this.loadQueue();
	}

	async removeFromQueue(episode_id: string) {
		const items = await getLocalQueue();
		const item = items.find((i) => i.episode_id === episode_id);
		if (item) await removeFromLocalQueue(item.id);
		await this.loadQueue();
	}

	async clearQueue() {
		await clearLocalQueue();
		this.queue = [];
	}

	// Play a queue item now, removing it from the queue.
	async playFromQueue(track: CurrentTrack) {
		await this.removeFromQueue(track.episode_id);
		this.play(track);
	}

	// Advance to the next queued episode (called when the current one ends).
	// Returns true if something was played.
	async playNext(): Promise<boolean> {
		if (this.current) await this.removeFromQueue(this.current.episode_id);
		const items = await getLocalQueue();
		const next = items.sort((a, b) => a.position_order - b.position_order)[0];
		if (next) {
			await removeFromLocalQueue(next.id);
			this.play(itemToTrack(next));
			await this.loadQueue();
			return true;
		}
		await this.loadQueue();
		return false;
	}
}

export const player = new PlayerStore();
