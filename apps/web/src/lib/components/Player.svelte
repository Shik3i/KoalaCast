<script lang="ts">
	import { onMount } from 'svelte';
	import { saveLocalPlaybackState, getLocalPlaybackState } from '../idb/db';

	interface CurrentTrack {
		episode_id: string;
		podcast_id: string;
		title: string;
		podcast_title: string;
		artwork_url: string;
		enclosure_url: string;
		duration_ms: number;
	}

	let { track = null, onTrackComplete = () => {} }: { track?: CurrentTrack | null; onTrackComplete?: () => void } = $props();

	let audioEl: HTMLAudioElement | null = $state(null);
	let isPlaying = $state(false);
	let currentTimeMs = $state(0);
	let durationMs = $state(0);
	let playbackSpeed = $state(1.0);
	let isExpanded = $state(false);
	let autoSaveTimer: any = null;
	let playbackSessionID = crypto.randomUUID();
	let sequenceNum = 0;

	$effect(() => {
		if (track && audioEl) {
			if (audioEl.src !== track.enclosure_url) {
				audioEl.src = track.enclosure_url;
				playbackSessionID = crypto.randomUUID();
				sequenceNum = 0;
				loadSavedPosition(track.episode_id);
			}
		}
	});

	async function loadSavedPosition(epId: string) {
		const state = await getLocalPlaybackState(epId);
		if (state && state.position_ms > 0 && !state.completed && audioEl) {
			audioEl.currentTime = state.position_ms / 1000;
			currentTimeMs = state.position_ms;
		}
	}

	function togglePlay() {
		if (!audioEl || !track) return;
		if (isPlaying) {
			audioEl.pause();
		} else {
			audioEl.play().catch(console.error);
		}
	}

	function seekTo(ms: number) {
		if (!audioEl) return;
		audioEl.currentTime = ms / 1000;
		currentTimeMs = ms;
		saveProgress('SEEK');
	}

	function skip(seconds: number) {
		if (!audioEl) return;
		audioEl.currentTime = Math.max(0, Math.min(audioEl.duration || 0, audioEl.currentTime + seconds));
		currentTimeMs = Math.round(audioEl.currentTime * 1000);
		saveProgress('SEEK');
	}

	function setSpeed(speed: number) {
		playbackSpeed = speed;
		if (audioEl) audioEl.playbackRate = speed;
	}

	function handleTimeUpdate() {
		if (!audioEl) return;
		currentTimeMs = Math.round(audioEl.currentTime * 1000);
		durationMs = Math.round((audioEl.duration || 0) * 1000);

		// Media Session Position State Update
		if ('mediaSession' in navigator && durationMs > 0) {
			try {
				navigator.mediaSession.setPositionState({
					duration: durationMs / 1000,
					playbackRate: playbackSpeed,
					position: currentTimeMs / 1000
				});
			} catch (_) {}
		}
	}

	function handleEnded() {
		isPlaying = false;
		saveProgress('MARK_PLAYED', true);
		onTrackComplete();
	}

	async function saveProgress(eventType = 'PROGRESS_TICK', forceCompleted = false) {
		if (!track) return;

		const dur = durationMs || track.duration_ms || 1;
		const remMs = Math.max(0, dur - currentTimeMs);
		const pct = Math.min(100, (currentTimeMs / dur) * 100);

		// Completion threshold: < 2 min (120,000 ms) or < 5% remaining
		const isCompleted = forceCompleted || remMs < 120000 || pct > 95;

		sequenceNum++;

		// 1. Save to local IndexedDB
		await saveLocalPlaybackState({
			episode_id: track.episode_id,
			podcast_id: track.podcast_id,
			position_ms: currentTimeMs,
			completed: isCompleted,
			progress_percent: pct,
			last_played_at: Date.now()
		});

		// 2. If authenticated, debounced server sync call is dispatched via API endpoint
	}

	function setupMediaSession() {
		if (!('mediaSession' in navigator) || !track) return;

		navigator.mediaSession.metadata = new MediaMetadata({
			title: track.title,
			artist: track.podcast_title,
			artwork: track.artwork_url ? [{ src: track.artwork_url }] : []
		});

		navigator.mediaSession.setActionHandler('play', togglePlay);
		navigator.mediaSession.setActionHandler('pause', togglePlay);
		navigator.mediaSession.setActionHandler('seekbackward', () => skip(-10));
		navigator.mediaSession.setActionHandler('seekforward', () => skip(30));
	}

	onMount(() => {
		autoSaveTimer = setInterval(() => {
			if (isPlaying) saveProgress('PROGRESS_TICK');
		}, 30000);

		const handleKeyDown = (e: KeyboardEvent) => {
			if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;
			if (e.code === 'Space') {
				e.preventDefault();
				togglePlay();
			} else if (e.code === 'ArrowLeft') {
				skip(-10);
			} else if (e.code === 'ArrowRight') {
				skip(30);
			}
		};

		window.addEventListener('keydown', handleKeyDown);

		return () => {
			window.removeEventListener('keydown', handleKeyDown);
			if (autoSaveTimer) clearInterval(autoSaveTimer);
		};
	});

	$effect(() => {
		if (track) setupMediaSession();
	});

	function formatTime(ms: number) {
		const totalSec = Math.floor(ms / 1000);
		const h = Math.floor(totalSec / 3600);
		const m = Math.floor((totalSec % 3600) / 60);
		const s = totalSec % 60;

		if (h > 0) {
			return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
		}
		return `${m}:${s.toString().padStart(2, '0')}`;
	}
</script>

<audio
	bind:this={audioEl}
	onplay={() => (isPlaying = true)}
	onpause={() => {
		isPlaying = false;
		saveProgress('PROGRESS_TICK');
	}}
	ontimeupdate={handleTimeUpdate}
	onended={handleEnded}
></audio>

{#if track}
	<div class="player-bar" class:expanded={isExpanded}>
		<button type="button" class="track-info" onclick={() => (isExpanded = !isExpanded)}>
			<img src={track.artwork_url || '/favicon.png'} alt={track.title} class="artwork" />
			<div class="meta">
				<span class="track-title">{track.title}</span>
				<span class="podcast-title">{track.podcast_title}</span>
			</div>
		</button>

		<div class="controls">
			<button onclick={() => skip(-10)} aria-label="Skip backward 10 seconds">↺ 10s</button>
			<button class="play-btn" onclick={togglePlay} aria-label={isPlaying ? 'Pause' : 'Play'}>
				{isPlaying ? '⏸' : '▶'}
			</button>
			<button onclick={() => skip(30)} aria-label="Skip forward 30 seconds">30s ↻</button>
		</div>

		<div class="timeline">
			<span class="time">{formatTime(currentTimeMs)}</span>
			<input
				type="range"
				min="0"
				max={durationMs || track.duration_ms || 100}
				value={currentTimeMs}
				onchange={(e) => seekTo(Number((e.target as HTMLInputElement).value))}
				aria-label="Playback timeline"
			/>
			<span class="time">{formatTime(durationMs || track.duration_ms)}</span>
		</div>

		<div class="speed-selector">
			<button onclick={() => setSpeed(1.0)} class:active={playbackSpeed === 1.0}>1x</button>
			<button onclick={() => setSpeed(1.25)} class:active={playbackSpeed === 1.25}>1.25x</button>
			<button onclick={() => setSpeed(1.5)} class:active={playbackSpeed === 1.5}>1.5x</button>
		</div>
	</div>
{/if}

<style>
	.player-bar {
		position: fixed;
		bottom: 0;
		left: 0;
		right: 0;
		background-color: var(--player-bg);
		color: var(--player-text);
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.75rem 1.5rem;
		gap: 1.5rem;
		border-top: 1px solid var(--border-subtle);
		z-index: 100;
	}

	.track-info {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		cursor: pointer;
		min-width: 200px;
	}

	.artwork {
		width: 48px;
		height: 48px;
		border-radius: 6px;
		object-fit: cover;
	}

	.meta {
		display: flex;
		flex-direction: column;
		overflow: hidden;
	}

	.track-title {
		font-weight: 600;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.podcast-title {
		font-size: 0.85rem;
		opacity: 0.8;
	}

	.controls {
		display: flex;
		align-items: center;
		gap: 1rem;
	}

	.play-btn {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		background-color: var(--accent-green);
		color: white;
		border: none;
		font-size: 1.25rem;
	}

	.timeline {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		flex: 1;
		max-width: 500px;
	}

	.timeline input[type='range'] {
		flex: 1;
		accent-color: var(--accent-green);
	}

	.time {
		font-size: 0.8rem;
		font-variant-numeric: tabular-nums;
	}

	.speed-selector button {
		background: transparent;
		color: inherit;
		border: 1px solid rgba(255, 255, 255, 0.2);
		padding: 0.2rem 0.5rem;
		border-radius: 4px;
		font-size: 0.8rem;
	}

	.speed-selector button.active {
		background-color: var(--accent-green);
		border-color: var(--accent-green);
	}
</style>
