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
	reorderLocalQueue,
	clearLocalQueue,
	getLocalCurrentPlayback,
	saveLocalCurrentPlayback,
	type LocalQueueItem
} from '$lib/idb/db';
import { normalizePlaybackSpeed } from '$lib/player/playback-speed';
import { resolveOfflineAudioUrl } from '$lib/downloads/offline-audio';
import { prefs } from '$lib/stores/prefs.svelte';

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

/** Deep enough to walk back through an evening's listening, short enough to stay in memory. */
const HISTORY_LIMIT = 25;

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
	#finalizePlayback: (() => Promise<void>) | null = null;
	current = $state<CurrentTrack | null>(null);
	// Bumped every time playback is (re)requested, so the Player can autoplay even
	// when the same track is selected again.
	playToken = $state(0);
	// The upcoming episodes, mirrored from IndexedDB (never includes what's playing).
	queue = $state<CurrentTrack[]>([]);
	volume = $state(1);
	playbackSpeed = $state(1);
	defaultPlaybackSpeed = $state(1);
	/** Wall-clock instant the timer fires, for display; recomputed as it counts down. */
	sleepTimerEndsAt = $state<number | null>(null);
	/** Listening time left on the timer. Only advances while audio is playing. */
	sleepRemainingMs = $state<number | null>(null);
	sleepAtEpisodeEnd = $state(false);
	sleepAtChapterEnd = $state(false);
	sleepTimerValue = $state('');
	isPlaying = $state(false);
	positionMs = $state(0);
	durationMs = $state(0);
	positionUpdatedAt = $state(Date.now());
	requestedPositionMs: number | null = null;

	/**
	 * Bumped when something outside the player asks it to toggle — an episode row
	 * whose control is showing the "this one is running" animation, for instance.
	 * The row cannot reach the audio element, and calling [play] again for the
	 * episode already playing restarted it: a short stutter and then the same
	 * audio, where the animation had promised a pause.
	 */
	playPauseToken = $state(0);

	requestTogglePlayPause() {
		this.playPauseToken++;
	}

	/**
	 * What was playing before, newest first, so "previous" can mean the previous
	 * episode rather than a fifteen-second jump back. The system's previous-track
	 * button and every car head unit send that command expecting a track change.
	 */
	history = $state<CurrentTrack[]>([]);

	get hasPrevious(): boolean {
		return this.history.length > 0;
	}

	/** Resumes the episode played before this one; the current one goes back on the queue. */
	async playPrevious(): Promise<boolean> {
		const previous = this.history[0];
		if (!previous) return false;
		this.history = this.history.slice(1);
		const interrupted = this.current;
		this.play(previous, undefined, { recordHistory: false });
		if (interrupted) await this.playNextAfterCurrent(interrupted);
		return true;
	}

	play(track: CurrentTrack, positionMs?: number, options: { recordHistory?: boolean } = {}) {
		const previous = this.current;
		if (
			options.recordHistory !== false &&
			previous &&
			previous.episode_id !== track.episode_id
		) {
			this.history = [previous, ...this.history.filter(
				(entry) => entry.episode_id !== previous.episode_id
			)].slice(0, HISTORY_LIMIT);
		}
		this.current = track;
		this.requestedPositionMs =
			positionMs !== undefined && Number.isFinite(positionMs) ? Math.max(0, positionMs) : null;
		this.positionMs = this.requestedPositionMs ?? 0;
		this.durationMs = track.duration_ms;
		this.positionUpdatedAt = Date.now();
		this.playToken++;
		void saveLocalCurrentPlayback({ ...track, position_ms: this.positionMs }).catch(() => {});
		void resolveOfflineAudioUrl(track.episode_id, track.enclosure_url).then((resolvedUrl) => {
			if (resolvedUrl === track.enclosure_url || this.current?.episode_id !== track.episode_id) return;
			this.current = { ...this.current, enclosure_url: resolvedUrl };
			this.playToken++;
		});
	}

	registerPlaybackFinalizer(finalizer: (() => Promise<void>) | null) {
		this.#finalizePlayback = finalizer;
	}

	async stop() {
		await this.#finalizePlayback?.();
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
		prefs.setPlaybackSpeed(this.playbackSpeed);
	}

	activatePreferences(playbackSpeed: number) {
		this.defaultPlaybackSpeed = normalizePlaybackSpeed(playbackSpeed);
		if (!this.current) this.playbackSpeed = this.defaultPlaybackSpeed;
	}

	updatePosition(positionMs: number, durationMs: number) {
		this.positionMs = Number.isFinite(positionMs) ? Math.max(0, positionMs) : 0;
		this.durationMs = Number.isFinite(durationMs) ? Math.max(0, durationMs) : 0;
		this.positionUpdatedAt = Date.now();
	}

	consumeRequestedPosition(): number | null {
		const requested = this.requestedPositionMs;
		this.requestedPositionMs = null;
		return requested;
	}

	setSleepTimer(value: string) {
		const supported = ['', 'chapter', 'episode', '15', '30', '45', '60'];
		const nextValue = supported.includes(value) ? value : '';
		this.sleepTimerValue = nextValue;
		this.sleepAtEpisodeEnd = nextValue === 'episode';
		this.sleepAtChapterEnd = nextValue === 'chapter';
		if (nextValue === '' || nextValue === 'episode' || nextValue === 'chapter') {
			this.sleepRemainingMs = null;
			this.sleepTimerEndsAt = null;
		} else {
			this.sleepRemainingMs = Number(nextValue) * 60_000;
			this.sleepTimerEndsAt = Date.now() + this.sleepRemainingMs;
		}
	}

	/**
	 * Counts the timer down by the time that just elapsed, and reports whether it
	 * has run out.
	 *
	 * The deadline used to be a plain wall-clock instant, so pausing for a phone
	 * call spent the whole timer: coming back, the next few seconds of audio ended
	 * with the player stopping again. "Thirty minutes" means thirty minutes of
	 * listening. [sleepTimerEndsAt] is kept in step for the queue rail, which shows
	 * when the session will end.
	 */
	tickSleepTimer(elapsedMs: number): boolean {
		if (this.sleepRemainingMs === null) return false;
		this.sleepRemainingMs = Math.max(0, this.sleepRemainingMs - Math.max(0, elapsedMs));
		this.sleepTimerEndsAt = Date.now() + this.sleepRemainingMs;
		if (this.sleepRemainingMs > 0) return false;
		this.setSleepTimer('');
		return true;
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
		this.requestedPositionMs = null;
		// History belongs to the account that was listening, not to the browser.
		this.history = [];
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

	/**
	 * Moves one episode up or down the queue.
	 *
	 * The reorder is computed over the *stored* queue, not over [queue], because
	 * that one hides whatever is playing: swapping two neighbours as they appear on
	 * screen could otherwise place them either side of a hidden entry and land them
	 * in an order nobody asked for.
	 */
	async moveInQueue(episode_id: string, direction: -1 | 1) {
		const items = await getLocalQueue();
		const ids = items.map((item) => item.episode_id);
		const index = ids.indexOf(episode_id);
		const target = index + direction;
		if (index < 0 || target < 0 || target >= ids.length) return;
		[ids[index], ids[target]] = [ids[target], ids[index]];
		await reorderLocalQueue(ids);
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
