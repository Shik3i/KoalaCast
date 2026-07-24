<script lang="ts">
	import { onMount } from 'svelte';
	import {
		saveLocalPlaybackState,
		getLocalPlaybackState,
		isLocalFavorite,
		addLocalFavorite,
		removeLocalFavorite
	} from '../idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { dominantColor } from '$lib/color';
	import { toast } from '$lib/stores/toast.svelte';

	let audioEl: HTMLAudioElement | null = $state(null);
	let isPlaying = $state(false);
	let currentTimeMs = $state(0);
	let durationMs = $state(0);
	let playbackSpeed = $state(1.0);
	let showShortcutsModal = $state(false);
	let sleepTimerEndsAt = $state<number | null>(null);
	let sleepAtEpisodeEnd = $state(false);
	let loadError = $state(false);
	let expanded = $state(false);
	let showAccent = $state<string | null>(null);
	let isFav = $state(false);
	let showVolume = $state(false);
	let lastToken = 0;

	import { audioEngine } from '$lib/audio/engine';

	let volumeBoost = $state(false);
	let skipSilence = $state(false);
	let activeTab = $state<'player' | 'chapters' | 'transcript'>('player');

	let chapters = $state<any[]>([]);
	let transcriptCues = $state<any[]>([]);
	let loadingChapters = $state(false);
	let loadingTranscript = $state(false);

	function toggleVolumeBoost() {
		volumeBoost = !volumeBoost;
		if (audioEl) audioEngine.init(audioEl);
		audioEngine.setVolumeBoost(volumeBoost);
		toast.success(volumeBoost ? 'Volume Boost Enabled (2.2x)' : 'Volume Boost Off');
	}

	function toggleSkipSilence() {
		skipSilence = !skipSilence;
		audioEngine.skipSilence = skipSilence;
		if (!skipSilence && audioEl) {
			audioEl.playbackRate = playbackSpeed;
		}
		if (audioEl) audioEngine.init(audioEl);
		audioEngine.resume();
		toast.success(skipSilence ? 'Skip Silence Enabled' : 'Skip Silence Off');
	}

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

	function cycleSpeed() {
		const idx = speeds.indexOf(playbackSpeed);
		setSpeed(speeds[(idx + 1) % speeds.length] ?? 1.0);
	}

	const volIcon = $derived(
		player.volume === 0 ? 'ph-speaker-simple-x' : player.volume < 0.5 ? 'ph-speaker-simple-low' : 'ph-speaker-simple-high'
	);

	function setSpeed(speed: number) {
		playbackSpeed = speed;
		if (audioEl) audioEl.playbackRate = speed;
		try {
			localStorage.setItem('koalacast_playback_speed', speed.toString());
		} catch (_) {}
	}

	function setSleepTimer(value: string) {
		sleepAtEpisodeEnd = value === 'episode';
		if (value === '' ) sleepTimerEndsAt = null;
		else if (value === 'episode') sleepTimerEndsAt = null;
		else sleepTimerEndsAt = Date.now() + Number(value) * 60 * 1000;
	}

	const progressPercent = $derived(
		durationMs > 0 ? Math.min(100, (currentTimeMs / durationMs) * 100) : 0
	);

	function handleTimeUpdate() {
		if (!audioEl) return;
		currentTimeMs = Math.round(audioEl.currentTime * 1000);
		durationMs = Math.round((audioEl.duration || 0) * 1000);

		if (skipSilence && isPlaying && audioEngine.isSilent()) {
			audioEl.playbackRate = Math.min(3.0, playbackSpeed * 2.0);
		} else if (audioEl.playbackRate !== playbackSpeed) {
			audioEl.playbackRate = playbackSpeed;
		}

		if (sleepTimerEndsAt && Date.now() >= sleepTimerEndsAt) {
			audioEl.pause();
			sleepTimerEndsAt = null;
		}

		if ('mediaSession' in navigator && durationMs > 0) {
			try {
				navigator.mediaSession.setPositionState({
					duration: durationMs / 1000,
					playbackRate: audioEl.playbackRate,
					position: currentTimeMs / 1000
				});
			} catch (_) {}
		}
	}

	async function handleEnded() {
		isPlaying = false;
		await saveProgress('MARK_PLAYED', true);
		// Stop here if a "sleep at end of episode" timer is armed.
		if (sleepAtEpisodeEnd) {
			sleepAtEpisodeEnd = false;
			return;
		}
		// Otherwise autoplay the next queued episode, if any.
		await player.playNext();
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
			last_played_at: Date.now(),
			// Denormalized so the "Continue Listening" shelf can render + resume
			// without another fetch.
			title: track.title,
			podcast_title: track.podcast_title,
			artwork_url: track.artwork_url,
			enclosure_url: track.enclosure_url,
			duration_ms: durationMs || track.duration_ms
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

	// Pull a vivid accent out of the current cover art so the player + now-playing
	// view theme themselves around the show. Null → keep the default brand green.
	let lastArt = '';
	$effect(() => {
		const art = track?.artwork_url ?? '';
		if (art === lastArt) return;
		lastArt = art;
		showAccent = null;
		if (!art) return;
		dominantColor(art).then((c) => {
			if (track?.artwork_url === art) showAccent = c;
		});
	});

	// Never leave the full-screen view open once playback is closed.
	$effect(() => {
		if (!track) expanded = false;
	});

	// Track favorite state of the current episode.
	let lastFavEp = '';
	$effect(() => {
		const ep = track?.episode_id ?? '';
		if (ep === lastFavEp) return;
		lastFavEp = ep;
		isFav = false;
		if (ep) isLocalFavorite(ep).then((v) => (track?.episode_id === ep ? (isFav = v) : null));
	});

	async function toggleFavorite() {
		if (!track) return;
		if (isFav) {
			await removeLocalFavorite(track.episode_id);
			isFav = false;
			toast.success('Removed from favorites.');
		} else {
			await addLocalFavorite({
				episode_id: track.episode_id,
				added_at: Date.now(),
				podcast_id: track.podcast_id,
				title: track.title,
				podcast_title: track.podcast_title,
				artwork_url: track.artwork_url,
				enclosure_url: track.enclosure_url,
				duration_ms: track.duration_ms
			});
			isFav = true;
			toast.success('Added to favorites.');
		}
	}

	const accentVars = $derived(
		showAccent
			? `--show-accent:${showAccent};--show-accent-soft:color-mix(in srgb, ${showAccent} 22%, transparent);`
			: '--show-accent:var(--accent-green);--show-accent-soft:color-mix(in srgb, var(--accent-green) 22%, transparent);'
	);

	// Keep the audio element's volume in sync with the store.
	$effect(() => {
		if (audioEl) audioEl.volume = player.volume;
	});

	onMount(() => {
		try {
			const saved = localStorage.getItem('koalacast_playback_speed');
			if (saved) {
				const spd = parseFloat(saved);
				if (spd > 0 && spd <= 3) setSpeed(spd);
			}
			const vol = localStorage.getItem('koalacast_volume');
			if (vol !== null) player.volume = Math.max(0, Math.min(1, parseFloat(vol)));
		} catch (_) {}

		player.loadQueue();

		autoSaveTimer = setInterval(() => {
			if (isPlaying) saveProgress('PROGRESS_TICK');
		}, 30000);

		const handleKeyDown = (e: KeyboardEvent) => {
			if (e.key === 'Escape') {
				if (showShortcutsModal) showShortcutsModal = false;
				else if (expanded) expanded = false;
				return;
			}
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

	const speeds = [0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5];
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
	<div class="modal-overlay" onclick={() => (showShortcutsModal = false)} onkeydown={(e) => e.key === 'Escape' && (showShortcutsModal = false)} role="presentation">
		<div class="modal-content" onclick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="Keyboard shortcuts" tabindex="-1">
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
	<div class="player-shell" style={accentVars}>
		<div class="player-bar">
			<!-- Seek progress line spanning the top of the bar -->
			<div class="progress-track" style="--progress: {progressPercent}%"></div>

			<div class="track-info">
				<button class="art-btn" onclick={() => (expanded = true)} aria-label="Open full-screen player">
					<img
						src={track.artwork_url || '/placeholder.svg'}
						alt={track.title}
						class="artwork"
						onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
					/>
					<span class="art-expand"><i class="ph ph-arrows-out-simple" aria-hidden="true"></i></span>
				</button>
				<div class="meta">
					<a class="track-title" href={`/episode/${track.episode_id}`}>{track.title}</a>
					<a class="podcast-title" href={`/podcast/${track.podcast_id}`}>{track.podcast_title}</a>
				</div>
				{#if isPlaying}
					<div class="eq-bars" aria-label="Audio playing">
						<span class="bar bar1"></span>
						<span class="bar bar2"></span>
						<span class="bar bar3"></span>
						<span class="bar bar4"></span>
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
				<button class="ctrl speed-cycle" onclick={cycleSpeed} aria-label="Playback speed">{playbackSpeed}×</button>
				<div class="vol-wrap">
					<button class="ctrl" onclick={() => (showVolume = !showVolume)} aria-label="Volume">
						<i class="ph {volIcon}" aria-hidden="true"></i>
					</button>
					{#if showVolume}
						<div class="vol-pop">
							<input type="range" min="0" max="1" step="0.05" value={player.volume} oninput={(e) => player.setVolume(Number((e.target as HTMLInputElement).value))} aria-label="Volume level" />
						</div>
					{/if}
				</div>
				<select onchange={(e) => setSleepTimer(e.currentTarget.value)} aria-label="Sleep timer">
					<option value="">💤 Off</option>
					<option value="episode">End of episode</option>
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

	<!-- Full-screen Now Playing -->
	{#if expanded}
		<div class="np-overlay" style={accentVars} role="dialog" aria-modal="true" aria-label="Now playing">
			<div class="np-bg" style="background-image: url({track.artwork_url || '/placeholder.svg'})"></div>
			<button class="np-close" onclick={() => (expanded = false)} aria-label="Close full-screen player">
				<i class="ph ph-caret-down" aria-hidden="true"></i>
			</button>

			<div class="np-content">
				<div class="np-art-wrap" class:playing={isPlaying}>
					<img
						src={track.artwork_url || '/placeholder.svg'}
						alt={track.title}
						class="np-art"
						onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
					/>
				</div>

				<div class="np-meta">
					<a class="np-title" href={`/episode/${track.episode_id}`} onclick={() => (expanded = false)}>{track.title}</a>
					<a class="np-podcast" href={`/podcast/${track.podcast_id}`} onclick={() => (expanded = false)}>{track.podcast_title}</a>
				</div>

				<div class="np-timeline">
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

				<div class="np-controls">
					<button class="np-ctrl" onclick={() => skip(-10)} aria-label="Skip backward 10 seconds">
						<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i><small>10</small>
					</button>
					<button class="np-play" onclick={togglePlay} aria-label={isPlaying ? 'Pause' : 'Play'}>
						<i class="ph-fill {isPlaying ? 'ph-pause' : 'ph-play'}" aria-hidden="true"></i>
					</button>
					<button class="np-ctrl" onclick={() => skip(30)} aria-label="Skip forward 30 seconds">
						<i class="ph ph-arrow-clockwise" aria-hidden="true"></i><small>30</small>
					</button>
				</div>

				<div class="np-volume">
					<i class="ph {volIcon}" aria-hidden="true"></i>
					<input type="range" min="0" max="1" step="0.05" value={player.volume} oninput={(e) => player.setVolume(Number((e.target as HTMLInputElement).value))} aria-label="Volume level" />
				</div>

				<div class="np-extras">
					<button class="np-fav" class:active={isFav} onclick={toggleFavorite} aria-pressed={isFav} aria-label={isFav ? 'Remove from favorites' : 'Add to favorites'}>
						<i class="{isFav ? 'ph-fill ph-heart' : 'ph ph-heart'}" aria-hidden="true"></i>
					</button>
					<button class="np-pill-btn" class:active={volumeBoost} onclick={toggleVolumeBoost} aria-label="Toggle Volume Boost">
						<i class="ph ph-speaker-high" aria-hidden="true"></i> Boost
					</button>
					<button class="np-pill-btn" class:active={skipSilence} onclick={toggleSkipSilence} aria-label="Toggle Skip Silence">
						<i class="ph ph-waveform" aria-hidden="true"></i> Trim Silence
					</button>
					<div class="speed-selector">
						{#each speeds as spd}
							<button onclick={() => setSpeed(spd)} class:active={playbackSpeed === spd}>{spd}x</button>
						{/each}
					</div>
					<select onchange={(e) => setSleepTimer(e.currentTarget.value)} aria-label="Sleep timer">
						<option value="">💤 Sleep off</option>
						<option value="episode">End of episode</option>
						<option value="15">15 min</option>
						<option value="30">30 min</option>
						<option value="45">45 min</option>
						<option value="60">60 min</option>
					</select>
				</div>

				{#if player.upNext}
					<button class="np-upnext" onclick={() => player.upNext && player.playFromQueue(player.upNext)}>
						<span class="upnext-label"><i class="ph ph-queue" aria-hidden="true"></i> Up next</span>
						<span class="upnext-title">{player.upNext.title}</span>
						<i class="ph ph-play" aria-hidden="true"></i>
					</button>
				{/if}
			</div>
		</div>
	{/if}
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
		background: linear-gradient(90deg, var(--show-accent, var(--accent-green)), color-mix(in srgb, var(--show-accent, var(--accent-green)) 60%, #fff));
		transition: width 0.25s linear, background 0.4s ease;
	}

	.art-btn {
		position: relative;
		padding: 0;
		border: none;
		background: none;
		flex-shrink: 0;
		border-radius: 10px;
		line-height: 0;
	}
	.art-expand {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgba(0, 0, 0, 0.45);
		color: #fff;
		border-radius: 10px;
		font-size: 1.2rem;
		opacity: 0;
		transition: opacity 0.2s ease;
	}
	.art-btn:hover .art-expand { opacity: 1; }

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
		background: var(--show-accent, var(--accent-green));
		color: #fff;
		border: none;
		display: grid;
		place-items: center;
		font-size: 1.3rem;
		box-shadow: 0 6px 18px var(--show-accent-soft, color-mix(in srgb, var(--accent-green) 55%, transparent));
		transition: transform 0.15s ease, background 0.4s ease, box-shadow 0.4s ease;
	}
	.play-btn:hover { filter: brightness(1.08); transform: scale(1.06); }

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
			var(--show-accent, var(--accent-green)) 0%,
			var(--show-accent, var(--accent-green)) var(--progress, 0%),
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

	.speed-cycle {
		width: auto;
		min-width: 40px;
		padding: 0 0.5rem;
		font-size: 0.8rem;
		font-weight: 700;
		font-variant-numeric: tabular-nums;
	}

	.vol-wrap { position: relative; display: flex; }
	.vol-pop {
		position: absolute;
		bottom: calc(100% + 8px);
		left: 50%;
		transform: translateX(-50%);
		background: color-mix(in srgb, var(--player-bg) 92%, transparent);
		border: 1px solid color-mix(in srgb, var(--player-text) 15%, transparent);
		border-radius: 10px;
		padding: 0.6rem 0.5rem;
		box-shadow: var(--shadow-lg);
		backdrop-filter: blur(12px);
	}
	.vol-pop input[type='range'] { width: 100px; accent-color: var(--show-accent, var(--accent-green)); }

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
		background: var(--show-accent, var(--accent-green));
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
		background: var(--show-accent, var(--accent-green));
		border-radius: 2px;
		animation: eq-bounce 0.8s ease-in-out infinite alternate;
	}
	.bar1 { height: 60%; animation-delay: 0.1s; }
	.bar2 { height: 100%; animation-delay: 0.3s; }
	.bar3 { height: 40%; animation-delay: 0.2s; }
	.bar4 { height: 80%; animation-delay: 0.45s; }

	.btn-close {
		background: var(--accent-green);
		color: #fff;
		border: none;
		padding: 0.55rem 1.1rem;
		border-radius: 8px;
		font-weight: 600;
		width: 100%;
	}

	/* ---------- Full-screen Now Playing ---------- */
	.np-overlay {
		position: fixed;
		inset: 0;
		z-index: 300;
		display: grid;
		place-items: center;
		overflow: hidden;
		background: var(--player-bg);
		color: var(--player-text);
		animation: np-in 0.35s var(--ease-spring, cubic-bezier(0.16, 1, 0.3, 1));
	}
	/* Blurred cover art bleeding a show-tinted ambient wash behind the content. */
	.np-bg {
		position: absolute;
		inset: -12%;
		background-size: cover;
		background-position: center;
		filter: blur(60px) saturate(150%) brightness(0.55);
		transform: scale(1.2);
		opacity: 0.85;
	}
	.np-overlay::before {
		content: '';
		position: absolute;
		inset: 0;
		background: radial-gradient(120% 90% at 50% 0%, var(--show-accent-soft, transparent), transparent 60%),
			linear-gradient(to bottom, rgba(0, 0, 0, 0.3), rgba(0, 0, 0, 0.72));
	}

	.np-close {
		position: absolute;
		top: 1.1rem;
		left: 1.1rem;
		z-index: 2;
		width: 44px;
		height: 44px;
		border-radius: 50%;
		border: none;
		background: color-mix(in srgb, var(--player-text) 12%, transparent);
		color: var(--player-text);
		display: grid;
		place-items: center;
		font-size: 1.4rem;
		backdrop-filter: blur(8px);
	}
	.np-close:hover { background: color-mix(in srgb, var(--player-text) 22%, transparent); }

	.np-content {
		position: relative;
		z-index: 1;
		width: min(92vw, 440px);
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1.5rem;
		padding: 2rem 1rem;
	}

	.np-art-wrap {
		width: min(72vw, 340px);
		aspect-ratio: 1;
		border-radius: 22px;
		overflow: hidden;
		box-shadow: 0 30px 70px rgba(0, 0, 0, 0.55), 0 0 0 1px color-mix(in srgb, var(--player-text) 10%, transparent);
		transition: transform 0.5s var(--ease-spring, ease);
	}
	.np-art-wrap.playing { animation: np-breathe 5s ease-in-out infinite; }
	.np-art { width: 100%; height: 100%; object-fit: cover; }

	.np-meta { text-align: center; display: flex; flex-direction: column; gap: 0.35rem; max-width: 100%; }
	.np-title {
		font-size: 1.4rem;
		font-weight: 800;
		line-height: 1.25;
		color: var(--player-text);
		letter-spacing: -0.01em;
	}
	.np-title:hover { text-decoration: underline; }
	.np-podcast { font-size: 0.95rem; color: color-mix(in srgb, var(--player-text) 72%, transparent); }

	.np-timeline { display: flex; align-items: center; gap: 0.75rem; width: 100%; }
	.np-timeline input[type='range'] {
		flex: 1;
		-webkit-appearance: none;
		appearance: none;
		height: 6px;
		border-radius: 999px;
		background: linear-gradient(
			90deg,
			var(--show-accent, var(--accent-green)) 0%,
			var(--show-accent, var(--accent-green)) var(--progress, 0%),
			color-mix(in srgb, var(--player-text) 24%, transparent) var(--progress, 0%)
		);
		cursor: pointer;
	}
	.np-timeline input[type='range']::-webkit-slider-thumb {
		-webkit-appearance: none;
		width: 16px;
		height: 16px;
		border-radius: 50%;
		background: #fff;
		box-shadow: 0 2px 8px rgba(0, 0, 0, 0.5);
	}
	.np-timeline input[type='range']::-moz-range-thumb {
		width: 16px; height: 16px; border: none; border-radius: 50%; background: #fff;
	}

	.np-controls { display: flex; align-items: center; gap: 1.75rem; }
	.np-ctrl {
		position: relative;
		width: 54px;
		height: 54px;
		border-radius: 50%;
		border: none;
		background: transparent;
		color: var(--player-text);
		display: grid;
		place-items: center;
		font-size: 1.7rem;
		opacity: 0.9;
	}
	.np-ctrl small {
		position: absolute;
		font-size: 0.55rem;
		font-weight: 700;
		bottom: 12px;
	}
	.np-ctrl:hover { background: color-mix(in srgb, var(--player-text) 12%, transparent); opacity: 1; }
	.np-play {
		width: 76px;
		height: 76px;
		border-radius: 50%;
		border: none;
		background: var(--show-accent, var(--accent-green));
		color: #fff;
		display: grid;
		place-items: center;
		font-size: 2rem;
		box-shadow: 0 12px 34px var(--show-accent-soft, rgba(0,0,0,0.4));
		transition: transform 0.15s ease, filter 0.2s ease;
	}
	.np-play:hover { filter: brightness(1.08); transform: scale(1.05); }

	.np-extras { display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap; justify-content: center; }
	.np-fav {
		width: 44px;
		height: 44px;
		border-radius: 50%;
		border: 1px solid color-mix(in srgb, var(--player-text) 16%, transparent);
		background: color-mix(in srgb, var(--player-text) 8%, transparent);
		color: var(--player-text);
		display: grid;
		place-items: center;
		font-size: 1.25rem;
		transition: transform 0.2s var(--ease-spring, ease), color 0.2s ease;
	}
	.np-fav:hover { color: #ff8b8b; transform: scale(1.08); }
	.np-fav.active { color: #ff6b6b; border-color: color-mix(in srgb, #ff6b6b 45%, transparent); }
	.np-fav.active :global(.ph-fill) { animation: fav-pop 0.3s var(--ease-spring, ease); }

	.np-pill-btn {
		background: color-mix(in srgb, var(--player-text) 8%, transparent);
		color: var(--player-text);
		border: 1px solid color-mix(in srgb, var(--player-text) 16%, transparent);
		padding: 0.4rem 0.8rem;
		border-radius: 999px;
		font-size: 0.82rem;
		font-weight: 600;
		display: inline-flex;
		align-items: center;
		gap: 0.35rem;
		cursor: pointer;
		transition: all 0.2s ease;
	}
	.np-pill-btn:hover { background: color-mix(in srgb, var(--player-text) 16%, transparent); }
	.np-pill-btn.active {
		background: var(--show-accent, var(--accent-green));
		border-color: var(--show-accent, var(--accent-green));
		color: #fff;
	}
	@keyframes fav-pop { 0% { transform: scale(0.6); } 60% { transform: scale(1.25); } 100% { transform: scale(1); } }
	.np-extras select {
		background: color-mix(in srgb, var(--player-text) 10%, transparent);
		color: var(--player-text);
		border: 1px solid color-mix(in srgb, var(--player-text) 16%, transparent);
		padding: 0.4rem 0.6rem;
		border-radius: 9px;
		font-size: 0.82rem;
		font-family: inherit;
	}
	.np-extras select option { color: #111; }

	.np-volume {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		width: min(80vw, 320px);
		color: color-mix(in srgb, var(--player-text) 75%, transparent);
		font-size: 1.15rem;
	}
	.np-volume input[type='range'] {
		flex: 1;
		accent-color: var(--show-accent, var(--accent-green));
		height: 4px;
	}

	.np-upnext {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		width: min(88vw, 380px);
		padding: 0.7rem 0.9rem;
		border-radius: 12px;
		border: 1px solid color-mix(in srgb, var(--player-text) 14%, transparent);
		background: color-mix(in srgb, var(--player-text) 7%, transparent);
		color: var(--player-text);
		text-align: left;
	}
	.np-upnext:hover { background: color-mix(in srgb, var(--player-text) 12%, transparent); }
	.np-upnext .upnext-label {
		display: inline-flex;
		align-items: center;
		gap: 0.35rem;
		font-size: 0.72rem;
		font-weight: 800;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: color-mix(in srgb, var(--player-text) 65%, transparent);
		flex-shrink: 0;
	}
	.np-upnext .upnext-title {
		flex: 1;
		min-width: 0;
		font-size: 0.9rem;
		font-weight: 600;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	@keyframes np-in {
		from { opacity: 0; transform: translateY(3%); }
		to { opacity: 1; transform: translateY(0); }
	}
	@keyframes np-breathe {
		0%, 100% { transform: scale(1); }
		50% { transform: scale(1.02); }
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

	/* Sit the floating mini-player above the mobile bottom tab bar. */
	@media (max-width: 640px) {
		.player-shell { bottom: calc(54px + env(safe-area-inset-bottom, 0px)); padding-bottom: 0.5rem; }
	}

	@media (prefers-reduced-motion: reduce) {
		.player-shell { animation: none; }
		.bar { animation: none; height: 60% !important; }
		.play-btn:hover { transform: none; }
		.np-overlay { animation: none; }
		.np-art-wrap.playing { animation: none; }
	}
</style>
