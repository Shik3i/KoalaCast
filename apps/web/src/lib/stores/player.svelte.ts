// Global "now playing" store. A single Player instance is mounted in the root
// layout and reads from here; any page can start playback by calling
// player.play(track). Uses Svelte 5 runes so reads in components stay reactive.

export interface CurrentTrack {
	episode_id: string;
	podcast_id: string;
	title: string;
	podcast_title: string;
	artwork_url: string;
	enclosure_url: string;
	duration_ms: number;
}

class PlayerStore {
	current = $state<CurrentTrack | null>(null);
	// Bumped every time playback is (re)requested, so the Player can autoplay even
	// when the same track is selected again.
	playToken = $state(0);

	play(track: CurrentTrack) {
		this.current = track;
		this.playToken++;
	}

	stop() {
		this.current = null;
	}

	get isActive() {
		return this.current !== null;
	}
}

export const player = new PlayerStore();
