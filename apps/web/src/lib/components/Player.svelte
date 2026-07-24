<script lang="ts">
	import { onMount } from 'svelte';
	import { saveLocalPlaybackState, getLocalPlaybackState } from '../idb/db';
	import { player } from '$lib/stores/player.svelte';

	let audioEl: HTMLAudioElement | null = $state(null);
	let isPlaying = $state(false);
	let currentTimeMs = $state(0);
	let durationMs = $state(0);
	let playbackSpeed = $state(1.0);
	let showShortcutsModal = $state(false);
	let sleepTimerEndsAt = $state<number | null>(null);
	let loadError = $state(false);
	let lastToken = 0;

	let autoSaveTimer: any = null;

	const track = $derived(player.current);

	// React to track / play requests. Setting src for a new track loads the saved
	// position; a bumped playToken (user pressed Play) triggers autoplay — allowed
	// because it originates from a click gesture.
	$effect(() => {
		const t = player.current;
		const token = player.playToken;
		if (!t || !audioEl) return;

		if (audioEl.src !== t.enclosure_url) {
			loadError = false;
			audioEl.src = t.enclosure_url;
			currentTimeMs = 0;
			loadSavedPosition(t.episode_id);
		}
		if (token !== lastToken) {
			lastToken = token;
			audioEl.play().catch(() => {});
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
		if (isPlaying) audioEl.pause();
		else audioEl.play().catch(() => {});
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
		try {
			localStorage.setItem('koalacast_playback_speed', speed.toString());
		} catch (_) {}
	}

	function setSleepTimer(minutes: number | null) {
		sleepTimerEndsAt = minutes === null ? null : Date.now() + minutes * 60 * 1000;
	}

	const progressPercent = $derived(
		durationMs > 0 ? Math.min(100, (currentTimeMs / durationMs) * 100) : 0
	);

	function handleTimeUpdate() {
		if (!audioEl) return;
		currentTimeMs = Math.round(audioEl.currentTime * 1000);
		durationMs = Math.round((audioEl.duration || 0) * 1000);

		if (sleepTimerEndsAt && Date.now() >= sleepTimerEndsAt) {
			audioEl.pause();
			setSleepTimer(null);
		}

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
	}

	async function saveProgress(eventType = 'PROGRESS_TICK', forceCompleted = false) {
		if (!track) return;
		const dur = durationMs || track.duration_ms || 1;
		const remMs = Math.max(0, dur - currentTimeMs);
		const pct = Math.min(100, (currentTimeMs / dur) * 100);
		const isCompleted = forceCompleted || remMs < 120000 || pct > 95;

		await saveLocalPlaybackState({
			episode_id: track.episode_id,
			podcast_id: track.podcast_id,
			position_ms: currentTimeMs,
			completed: isCompleted,
			progress_percent: pct,
			last_played_at: Date.now()
		});
	}

	function updateMediaSession() {
		if (!('mediaSession' in navigator) || !track) return;
		try {
			navigator.mediaSession.metadata = new MediaMetadata({
				title: track.title,
				artist: track.podcast_title,
				artwork: track.artwork_url ? [{ src: track.artwork_url, sizes: '512x512' }] : []
			});
			navigator.mediaSession.setActionHandler('play', () => togglePlay());
			navigator.mediaSession.setActionHandler('pause', () => togglePlay());
			navigator.mediaSession.setActionHandler('seekbackward', () => skip(-10));
			navigator.mediaSession.setActionHandler('seekforward', () => skip(30));
		} catch (_) {}
	}

	$effect(() => {
		if (track) updateMediaSession();
	});

	onMount(() => {
		try {
			const saved = localStorage.getItem('koalacast_playback_speed');
			if (saved) {
				const spd = parseFloat(saved);
				if (spd > 0 && spd <= 3) setSpeed(spd);
			}
		} catch (_) {}

		autoSaveTimer = setInterval(() => {
			if (isPlaying) saveProgress('PROGRESS_TICK');
		}, 30000);

		const handleKeyDown = (e: KeyboardEvent) => {
			if (['INPUT', 'TEXTAREA', 'SELECT'].includes((e.target as HTMLElement)?.tagName)) return;
			if (!player.current) return;
			if (e.code === 'Space') {
				e.preventDefault();
				togglePlay();
			} else if (e.code === 'ArrowLeft') {
				skip(-10);
			} else if (e.code === 'ArrowRight') {
				skip(30);
			} else if (e.key === '?') {
				showShortcutsModal = !showShortcutsModal;
			}
		};

		window.addEventListener('keydown', handleKeyDown);
		return () => {
			window.removeEventListener('keydown', handleKeyDown);
			if (autoSaveTimer) clearInterval(autoSaveTimer);
		};
	});

	function formatTime(ms: number) {
		const totalSec = Math.floor(ms / 1000);
		const h = Math.floor(totalSec / 3600);
		const m = Math.floor((totalSec % 3600) / 60);
		const s = totalSec % 60;
		if (h > 0) return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
		return `${m}:${s.toString().padStart(2, '0')}`;
	}

	const speeds = [1.0, 1.25, 1.5, 2.0];
</script>

<audio
	bind:this={audioEl}
	preload="metadata"
	onplay={() => {
		isPlaying = true;
		loadError = false;
	}}
	oncanplay={() => (loadError = false)}
	onpause={() => {
		isPlaying = false;
		saveProgress('PROGRESS_TICK');
	}}
	onerror={() => {
		isPlaying = false;
		loadError = true;
	}}
	ontimeupdate={handleTimeUpdate}
	onended={handleEnded}
></audio>

{#if showShortcutsModal}
	<div class="modal-overlay" onclick={() => (showShortcutsModal = false)} role="presentation">
		<div class="modal-content" onclick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
			<h3>Keyboard Shortcuts</h3>
			<ul>
				<li><kbd>Space</kbd> Play / Pause</li>
				<li><kbd>←</kbd> Skip 10 seconds back</li>
				<li><kbd>→</kbd> Skip 30 seconds forward</li>
				<li><kbd>?</kbd> Toggle this help</li>
			</ul>
			<button class="btn-close" onclick={() => (showShortcutsModal = false)}>Close</button>
		</div>
	</div>
{/if}

{#if track}
	<div class="player-shell">
		<div class="player-bar">
			<!-- Seek progress line spanning the top of the bar -->
			<div class="progress-track" style="--progress: {progressPercent}%"></div>

			<div class="track-info">
				<img
					src={track.artwork_url || '/placeholder.svg'}
					alt={track.title}
					class="artwork"
					onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
				/>
				<div class="meta">
					<a class="track-title" href={`/episode/${track.episode_id}`}>{track.title}</a>
					<a class="podcast-title" href={`/podcast/${track.podcast_id}`}>{track.podcast_title}</a>
				</div>
				{#if isPlaying}
					<div class="eq-bars" aria-label="Audio playing">
						<span class="bar bar1"></span>
						<span class="bar bar2"></span>
						<span class="bar bar3"></span>
					</div>
				{/if}
			</div>

			<div class="center">
				{#if loadError}
					<div class="load-error" role="alert">
						<i class="ph ph-warning-circle" aria-hidden="true"></i>
						Audio konnte nicht geladen werden — evtl. blockiert ein Tracking-/Werbeblocker die Datei.
					</div>
				{/if}
				<div class="controls">
					<button class="ctrl" onclick={() => skip(-10)} aria-label="Skip backward 10 seconds">
						<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i>
					</button>
					<button class="play-btn" onclick={togglePlay} aria-label={isPlaying ? 'Pause' : 'Play'}>
						<i class="ph-fill {isPlaying ? 'ph-pause' : 'ph-play'}" aria-hidden="true"></i>
					</button>
					<button class="ctrl" onclick={() => skip(30)} aria-label="Skip forward 30 seconds">
						<i class="ph ph-arrow-clockwise" aria-hidden="true"></i>
					</button>
				</div>

				<div class="timeline">
					<span class="time">{formatTime(currentTimeMs)}</span>
					<input
						type="range"
						min="0"
						max={durationMs || track.duration_ms || 100}
						value={currentTimeMs}
						style="--progress: {progressPercent}%"
						onchange={(e) => seekTo(Number((e.target as HTMLInputElement).value))}
						aria-label="Playback timeline"
					/>
					<span class="time">{formatTime(durationMs || track.duration_ms)}</span>
				</div>
			</div>

			<div class="extras">
				<div class="speed-selector">
					{#each speeds as spd}
						<button onclick={() => setSpeed(spd)} class:active={playbackSpeed === spd}>{spd}x</button>
					{/each}
				</div>
				<select onchange={(e) => setSleepTimer(e.currentTarget.value ? Number(e.currentTarget.value) : null)} aria-label="Sleep timer">
					<option value="">💤 Off</option>
					<option value="15">15 min</option>
					<option value="30">30 min</option>
					<option value="45">45 min</option>
					<option value="60">60 min</option>
				</select>
				<button class="ctrl close-track" onclick={() => player.stop()} aria-label="Close player">
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	.player-shell {
		position: fixed;
		bottom: 0;
		left: 0;
		right: 0;
		z-index: 100;
		padding: 0 1rem 1rem;
		pointer-events: none;
		animation: slide-up 0.4s var(--ease-spring, cubic-bezier(0.16, 1, 0.3, 1));
	}

	.player-bar {
		pointer-events: auto;
		position: relative;
		max-width: 1200px;
		margin: 0 auto;
		background: color-mix(in srgb, var(--player-bg) 82%, transparent);
		color: var(--player-text);
		display: grid;
		grid-template-columns: minmax(180px, 1fr) minmax(320px, 1.6fr) minmax(180px, 1fr);
		align-items: center;
		gap: 1.25rem;
		padding: 0.7rem 1.25rem;
		border: 1px solid color-mix(in srgb, var(--player-text) 12%, transparent);
		border-radius: var(--radius-lg, 18px);
		box-shadow: var(--shadow-xl, 0 20px 50px rgba(0, 0, 0, 0.4));
		backdrop-filter: blur(18px) saturate(140%);
		-webkit-backdrop-filter: blur(18px) saturate(140%);
		overflow: hidden;
	}

	.progress-track {
		position: absolute;
		top: 0;
		left: 0;
		height: 3px;
		width: var(--progress, 0%);
		background: linear-gradient(90deg, var(--accent-green), var(--accent-green-hover));
		transition: width 0.25s linear;
	}

	.track-info {
		display: flex;
		align-items: center;
		gap: 0.85rem;
		min-width: 0;
	}

	.artwork {
		width: 52px;
		height: 52px;
		border-radius: 10px;
		object-fit: cover;
		flex-shrink: 0;
		box-shadow: 0 6px 16px rgba(0, 0, 0, 0.35);
	}

	.meta {
		display: flex;
		flex-direction: column;
		min-width: 0;
		gap: 2px;
	}

	.track-title {
		font-weight: 700;
		font-size: 0.92rem;
		color: var(--player-text);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.track-title:hover { text-decoration: underline; }

	.podcast-title {
		font-size: 0.8rem;
		color: color-mix(in srgb, var(--player-text) 70%, transparent);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.center {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.35rem;
	}

	.controls {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.load-error {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		font-size: 0.78rem;
		color: #ffb4b4;
		max-width: 460px;
		text-align: center;
	}

	.ctrl {
		width: 38px;
		height: 38px;
		border-radius: 50%;
		background: transparent;
		color: var(--player-text);
		border: none;
		display: grid;
		place-items: center;
		font-size: 1.15rem;
		opacity: 0.85;
	}
	.ctrl:hover {
		background: color-mix(in srgb, var(--player-text) 12%, transparent);
		opacity: 1;
	}

	.play-btn {
		width: 46px;
		height: 46px;
		border-radius: 50%;
		background: var(--accent-green);
		color: #fff;
		border: none;
		display: grid;
		place-items: center;
		font-size: 1.3rem;
		box-shadow: 0 6px 18px color-mix(in srgb, var(--accent-green) 55%, transparent);
	}
	.play-btn:hover { background: var(--accent-green-hover); transform: scale(1.06); }

	.timeline {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		width: 100%;
	}

	.timeline input[type='range'] {
		flex: 1;
		-webkit-appearance: none;
		appearance: none;
		height: 5px;
		border-radius: 999px;
		background: linear-gradient(
			90deg,
			var(--accent-green) 0%,
			var(--accent-green) var(--progress, 0%),
			color-mix(in srgb, var(--player-text) 22%, transparent) var(--progress, 0%)
		);
		cursor: pointer;
	}
	.timeline input[type='range']::-webkit-slider-thumb {
		-webkit-appearance: none;
		width: 13px;
		height: 13px;
		border-radius: 50%;
		background: #fff;
		box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
		transition: transform 0.15s ease;
	}
	.timeline input[type='range']::-webkit-slider-thumb:hover { transform: scale(1.25); }
	.timeline input[type='range']::-moz-range-thumb {
		width: 13px;
		height: 13px;
		border: none;
		border-radius: 50%;
		background: #fff;
	}

	.time {
		font-size: 0.72rem;
		font-variant-numeric: tabular-nums;
		opacity: 0.75;
		min-width: 38px;
		text-align: center;
	}

	.extras {
		display: flex;
		align-items: center;
		justify-content: flex-end;
		gap: 0.5rem;
	}

	.extras select {
		background: color-mix(in srgb, var(--player-text) 8%, transparent);
		color: var(--player-text);
		border: 1px solid color-mix(in srgb, var(--player-text) 15%, transparent);
		padding: 0.3rem 0.5rem;
		border-radius: 8px;
		font-size: 0.78rem;
		font-family: inherit;
	}
	.extras select option { color: #111; }

	.speed-selector {
		display: flex;
		gap: 2px;
		background: color-mix(in srgb, var(--player-text) 8%, transparent);
		padding: 3px;
		border-radius: 9px;
	}
	.speed-selector button {
		background: transparent;
		color: var(--player-text);
		border: none;
		padding: 0.25rem 0.45rem;
		border-radius: 6px;
		font-size: 0.74rem;
		font-weight: 600;
		opacity: 0.7;
	}
	.speed-selector button.active {
		background: var(--accent-green);
		color: #fff;
		opacity: 1;
	}

	.modal-overlay {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		backdrop-filter: blur(4px);
		display: grid;
		place-items: center;
		z-index: 200;
		animation: fade-in 0.2s ease;
	}

	.modal-content {
		background: var(--bg-surface);
		color: var(--text-primary);
		padding: 1.75rem;
		border-radius: var(--radius-lg, 18px);
		border: 1px solid var(--border-subtle);
		max-width: 360px;
		width: 90%;
		box-shadow: var(--shadow-xl, 0 20px 50px rgba(0, 0, 0, 0.4));
	}

	.modal-content ul {
		list-style: none;
		padding: 0;
		margin: 1rem 0;
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
	}

	.modal-content li { display: flex; align-items: center; gap: 0.6rem; }

	kbd {
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-bottom-width: 2px;
		padding: 0.15rem 0.5rem;
		border-radius: 6px;
		font-family: monospace;
		font-size: 0.8rem;
	}

	.eq-bars {
		display: flex;
		align-items: flex-end;
		gap: 3px;
		height: 18px;
		flex-shrink: 0;
	}
	.bar {
		width: 3px;
		background: var(--accent-green);
		border-radius: 2px;
		animation: eq-bounce 0.8s ease-in-out infinite alternate;
	}
	.bar1 { height: 60%; animation-delay: 0.1s; }
	.bar2 { height: 100%; animation-delay: 0.3s; }
	.bar3 { height: 40%; animation-delay: 0.2s; }

	.btn-close {
		background: var(--accent-green);
		color: #fff;
		border: none;
		padding: 0.55rem 1.1rem;
		border-radius: 8px;
		font-weight: 600;
		width: 100%;
	}

	@keyframes eq-bounce {
		0% { height: 20%; }
		100% { height: 100%; }
	}
	@keyframes slide-up {
		from { transform: translateY(120%); opacity: 0; }
		to { transform: translateY(0); opacity: 1; }
	}
	@keyframes fade-in {
		from { opacity: 0; }
		to { opacity: 1; }
	}

	@media (max-width: 820px) {
		.player-bar {
			grid-template-columns: 1fr auto;
			grid-template-areas: 'info controls' 'timeline timeline';
			gap: 0.6rem 1rem;
		}
		.track-info { grid-area: info; }
		.center { grid-area: controls; flex-direction: row; gap: 0.75rem; }
		.timeline { grid-area: timeline; }
		.extras { display: none; }
	}

	@media (prefers-reduced-motion: reduce) {
		.player-shell { animation: none; }
		.bar { animation: none; height: 60% !important; }
		.play-btn:hover { transform: none; }
	}
</style>
