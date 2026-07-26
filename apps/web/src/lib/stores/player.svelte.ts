// Global "now playing" store. A single Player instance is mounted in the root
// layout and reads from here; any page can start playback by calling
// player.play(track). Uses Svelte 5 runes so reads in components stay reactive.
//
// The store also owns the play queue (persisted in IndexedDB): the Player plays
// the next queued episode automatically when one ends, and any page can enqueue.

import {
	getLocalQueue,
	addToLocalQueue,
	removeFromLocalQueue,
	clearLocalQueue,
	type LocalQueueItem
} from '$lib/idb/db';

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
	positionMs = $state(0);
	durationMs = $state(0);

	play(track: CurrentTrack) {
		this.current = track;
		this.positionMs = 0;
		this.durationMs = track.duration_ms;
		this.playToken++;
	}

	stop() {
		this.current = null;
		this.positionMs = 0;
		this.durationMs = 0;
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
		this.playbackSpeed = Math.max(0.25, Math.min(4, speed));
		if (persist) this.defaultPlaybackSpeed = this.playbackSpeed;
		if (!persist) return;
		try {
			localStorage.setItem('koalacast_playback_speed', String(this.playbackSpeed));
		} catch (_) {}
	}

	setSleepTimer(value: string) {
		this.sleepAtEpisodeEnd = value === 'episode';
		if (value === '' || value === 'episode') this.sleepTimerEndsAt = null;
		else this.sleepTimerEndsAt = Date.now() + Number(value) * 60_000;
	}

	async loadQueue() {
		const items = await getLocalQueue();
		this.queue = items
			.filter((i) => i.episode_id !== this.current?.episode_id)
			.map(itemToTrack);
	}

	async addToQueue(track: CurrentTrack) {
		const existing = await getLocalQueue();
		if (existing.some((i) => i.episode_id === track.episode_id)) return;
		await addToLocalQueue({
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
