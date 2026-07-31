<script lang="ts">
	import { t } from '$lib/i18n';
	import { onMount, tick } from 'svelte';
	import {
		saveLocalPlaybackState,
		saveLocalListeningSession,
		getLocalPlaybackState,
		isLocalFavorite,
		addLocalFavorite,
			removeLocalFavorite,
			saveLocalCurrentPlayback,
		type LocalListeningSession
	} from '../idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { dominantColor } from '$lib/color';
	import ShortcutsModal from './ShortcutsModal.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { sync } from '$lib/stores/sync.svelte';
	import {
		getPodcastPlaybackSettings,
		savePodcastPlaybackSettings,
		type PodcastPlaybackSettings
	} from '$lib/stores/podcast-settings';
	import { optimizeArtwork } from '$lib/artwork';
	import { slide } from 'svelte/transition';
	import { parsePlaybackSpeed } from '$lib/player/playback-speed';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { SilenceGate } from '$lib/audio/silence-gate';
	import {
		audioEffectsProxyUrl,
		isCrossOriginAudio,
		publisherAllowsAudioEffects
	} from '$lib/audio/source';
	import PlayPauseIcon from './PlayPauseIcon.svelte';

	let audioEl: HTMLAudioElement | null = $state(null);
	let audioElementGeneration = $state(0);
	let isPlaying = $state(false);
	let currentTimeMs = $state(0);
	let durationMs = $state(0);
	let showShortcutsModal = $state(false);
	let loadError = $state('');
	let loadErrorCode = $state('');
	let expanded = $state(false);
	let nowPlayingDialog: HTMLElement | null = $state(null);
	let playerBarElement: HTMLElement | null = $state(null);
	let previousFocus: HTMLElement | null = null;
	let showAccent = $state<string | null>(null);
	let isFav = $state(false);
	let showVolume = $state(false);
	let lastToken = 0;
	let activeSession: LocalListeningSession | null = null;
	let lastListeningSampleAt = 0;
	let trackSettings = $state<PodcastPlaybackSettings>(getPodcastPlaybackSettings(''));
	let remotePlaybackAvailable = $state(false);
	let remotePlaybackState = $state<RemotePlaybackState>('disconnected');
	const effectiveVolumeBoost = $derived(trackSettings.volumeBoost ?? prefs.volumeBoost);
	const effectiveSkipSilence = $derived(trackSettings.skipSilence ?? prefs.skipSilence);
	$effect(() => {
		const syncedDefaultSpeed = prefs.playbackSpeed;
		player.defaultPlaybackSpeed = syncedDefaultSpeed;
		if (!track || trackSettings.speed === null) {
			player.setPlaybackSpeed(syncedDefaultSpeed, false);
		}
	});
	let pendingIntroOutroSkippedMs = 0;
	let outroHandled = false;
	const graphSources = new Map<string, { source: string; crossOrigin: boolean }>();
	let runtimeConfigPromise: Promise<boolean> | null = null;

	import { audioEngine } from '$lib/audio/engine';

	const silenceGate = new SilenceGate();
	let silenceTimer: ReturnType<typeof setInterval> | null = null;
	let chapters = $state<any[]>([]);
	let transcriptCues = $state<any[]>([]);
	let transcriptQuery = $state('');
	let loadingChapters = $state(false);
	let loadingTranscript = $state(false);
	let showChaptersDrawer = $state(false);
	let showTranscriptDrawer = $state(false);
	const filteredTranscriptCues = $derived(
		transcriptQuery.trim()
			? transcriptCues.filter((cue) =>
					String(cue.text || '').toLowerCase().includes(transcriptQuery.trim().toLowerCase())
				)
			: transcriptCues
	);

	let lastFetchedTrack = '';
	let chaptersGeneration = 0;
	$effect(() => {
		const currentTrack = track;
		const generation = ++chaptersGeneration;
		const controller = new AbortController();
		if (!currentTrack) {
			chapters = [];
			transcriptCues = [];
			return () => controller.abort();
		}
		if (currentTrack.episode_id === lastFetchedTrack) return () => controller.abort();
		lastFetchedTrack = currentTrack.episode_id;

		fetch(`/api/v1/episodes/${currentTrack.episode_id}`, { signal: controller.signal })
			.then((res) => (res.ok ? res.json() : null))
			.then((epData) => {
				if (generation !== chaptersGeneration || track?.episode_id !== currentTrack.episode_id) return;
				if (epData && epData.chapters_url) {
					fetchChapters(epData.chapters_url, currentTrack.episode_id, generation, controller.signal);
				} else {
					chapters = [];
				}
				const transcriptUrl = epData?.transcripts?.[0]?.url;
				if (transcriptUrl) {
					fetchTranscript(transcriptUrl, currentTrack.episode_id, generation, controller.signal);
				} else {
					transcriptCues = [];
				}
			})
			.catch((error) => {
				if (error?.name !== 'AbortError' && generation === chaptersGeneration) chapters = [];
			});
		return () => controller.abort();
	});

	async function fetchChapters(
		url: string,
		episodeId: string,
		generation: number,
		signal: AbortSignal
	) {
		loadingChapters = true;
		try {
			const res = await fetch(`/api/v1/proxy/chapters?url=${encodeURIComponent(url)}`, { signal });
			if (generation !== chaptersGeneration || track?.episode_id !== episodeId) return;
			if (res.ok) {
				const data = await res.json();
				if (generation !== chaptersGeneration || track?.episode_id !== episodeId) return;
				chapters = data.chapters || [];
			} else {
				chapters = [];
			}
		} catch (error: any) {
			if (error?.name !== 'AbortError' && generation === chaptersGeneration) chapters = [];
		} finally {
			if (generation === chaptersGeneration) loadingChapters = false;
		}
	}

	const activeChapterIndex = $derived.by(() => {
		if (!chapters || chapters.length === 0) return -1;
		const curSec = currentTimeMs / 1000;
		for (let i = chapters.length - 1; i >= 0; i--) {
			const startTime = typeof chapters[i].startTime === 'number' ? chapters[i].startTime : chapters[i].start;
			if (curSec >= startTime) {
				return i;
			}
		}
		return -1;
	});

	let previousChapterIndex = $state(-1);
	$effect(() => {
		const currentIdx = activeChapterIndex;
		if (player.sleepAtChapterEnd && previousChapterIndex >= 0 && currentIdx > previousChapterIndex) {
			if (audioEl) audioEl.pause();
			isPlaying = false;
			player.sleepAtChapterEnd = false;
			toast.info(t('player.sleepTimer'));
		}
		previousChapterIndex = currentIdx;
	});

	async function toggleVolumeBoost() {
		const enabled = !effectiveVolumeBoost;
		if (enabled && !(await prepareAudioGraph())) return;
		if (track?.podcast_id && trackSettings.volumeBoost !== null) {
			trackSettings = { ...trackSettings, volumeBoost: enabled, updatedAt: Date.now() };
			savePodcastPlaybackSettings(track.podcast_id, trackSettings);
		} else {
			prefs.setVolumeBoost(enabled);
		}
		audioEngine.setVolumeBoost(enabled);
		if (!enabled && !effectiveSkipSilence) await resetToNativePlayback();
		void audioEngine.resume();
		toast.success(t(enabled ? 'player.boostEnabled' : 'player.boostDisabled'));
	}

	async function fetchTranscript(
		url: string,
		episodeId: string,
		generation: number,
		signal: AbortSignal
	) {
		loadingTranscript = true;
		transcriptQuery = '';
		try {
			const res = await fetch(`/api/v1/proxy/transcript?url=${encodeURIComponent(url)}`, { signal });
			if (generation !== chaptersGeneration || track?.episode_id !== episodeId) return;
			const data = res.ok ? await res.json() : null;
			transcriptCues = Array.isArray(data?.cues) ? data.cues : [];
		} catch (error: any) {
			if (error?.name !== 'AbortError' && generation === chaptersGeneration) transcriptCues = [];
		} finally {
			if (generation === chaptersGeneration) loadingTranscript = false;
		}
	}

	async function toggleSkipSilence() {
		const enabled = !effectiveSkipSilence;
		if (enabled && !(await prepareAudioGraph())) return;
		if (track?.podcast_id && trackSettings.skipSilence !== null) {
			trackSettings = { ...trackSettings, skipSilence: enabled, updatedAt: Date.now() };
			savePodcastPlaybackSettings(track.podcast_id, trackSettings);
		} else {
			prefs.setSkipSilence(enabled);
		}
		audioEngine.skipSilence = enabled;
		if (!enabled) resetSilenceTrimming();
		if (!enabled && !effectiveVolumeBoost) await resetToNativePlayback();
		audioEngine.resume();
		toast.success(t(enabled ? 'player.skipSilenceEnabled' : 'player.skipSilenceDisabled'));
	}

	function resetSilenceTrimming() {
		silenceGate.reset();
		if (audioEl && audioEl.playbackRate !== player.playbackSpeed) {
			audioEl.playbackRate = player.playbackSpeed;
		}
	}

	async function audioEffectsRelayEnabled(): Promise<boolean> {
		if (!runtimeConfigPromise) {
			runtimeConfigPromise = fetch('/api/v1/config', { cache: 'no-store' })
				.then(async (response) => {
					if (!response.ok) return false;
					const config = await response.json();
					return config.audio_effects_proxy_enabled === true;
				})
				.catch(() => false);
		}
		return runtimeConfigPromise;
	}

	async function resolveGraphSource(source: string): Promise<{ source: string; crossOrigin: boolean } | null> {
		const cached = graphSources.get(source);
		if (cached) return cached;
		if (!isCrossOriginAudio(source, location.origin)) {
			const result = { source, crossOrigin: false };
			graphSources.set(source, result);
			return result;
		}
		if (await publisherAllowsAudioEffects(source, location.origin)) {
			const result = { source, crossOrigin: true };
			graphSources.set(source, result);
			return result;
		}
		if (await audioEffectsRelayEnabled()) {
			const result = { source: audioEffectsProxyUrl(source), crossOrigin: false };
			graphSources.set(source, result);
			return result;
		}
		return null;
	}

	async function switchAudioSource(source: string, crossOrigin: boolean): Promise<boolean> {
		if (!audioEl) return false;
		const element = audioEl;
		const current = element.currentSrc || element.src;
		const resolved = new URL(source, location.href).href;
		const expectedCrossOrigin = crossOrigin ? 'anonymous' : null;
		if (current === resolved && element.crossOrigin === expectedCrossOrigin) return true;

		const resumeAt = Number.isFinite(element.currentTime) ? element.currentTime : 0;
		const wasPlaying = !element.paused;
		element.pause();
		if (crossOrigin) element.crossOrigin = 'anonymous';
		else element.removeAttribute('crossorigin');
		element.src = source;

		const ready = await new Promise<boolean>((resolve) => {
			const cleanup = () => {
				element.removeEventListener('loadedmetadata', onReady);
				element.removeEventListener('error', onError);
			};
			const onReady = () => {
				cleanup();
				resolve(true);
			};
			const onError = () => {
				cleanup();
				resolve(false);
			};
			element.addEventListener('loadedmetadata', onReady, { once: true });
			element.addEventListener('error', onError, { once: true });
			element.load();
		});
		if (!ready) return false;
		if (resumeAt > 0 && Number.isFinite(element.duration)) {
			element.currentTime = Math.min(resumeAt, Math.max(0, element.duration - 0.1));
		}
		if (wasPlaying) await element.play().catch(() => {});
		return true;
	}

	async function prepareAudioGraph(): Promise<boolean> {
		if (!audioEl || !track) return false;
		const graphSource = await resolveGraphSource(track.enclosure_url);
		if (!graphSource) {
			toast.error(t('player.effectsCorsUnavailable'));
			return false;
		}
		if (!(await switchAudioSource(graphSource.source, graphSource.crossOrigin))) return false;
		return audioEngine.init(audioEl);
	}

	async function resetToNativePlayback() {
		if (!audioEl || !track || !audioEngine.initialized) return;
		const wasPlaying = !audioEl.paused;
		await saveProgress('PROGRESS_TICK');
		audioEl.pause();
		audioEngine.destroy();
		applyVolume();
		audioElementGeneration++;
		await tick();
		if (wasPlaying) player.playToken++;
	}

	async function requestPlayback() {
		if (!audioEl) return;
		const element = audioEl;
		const needsAudioGraph = effectiveVolumeBoost || effectiveSkipSilence || audioEngine.initialized;

		// Leave ordinary playback on the native media-element output. Creating a
		// MediaElementAudioSourceNode redirects all sound into its AudioContext;
		// doing that from `onplay` can leave the graph suspended and the episode
		// completely silent even though the media element is advancing.
		if (!needsAudioGraph) {
			await element.play().catch(() => {});
			return;
		}

		if (!(await prepareAudioGraph())) {
			await element.play().catch(() => {});
			return;
		}
		audioEngine.skipSilence = effectiveSkipSilence;
		audioEngine.setVolumeBoost(effectiveVolumeBoost);
		applyVolume();
		const running = await audioEngine.resume();
		if (running) await element.play().catch(() => {});
	}

	function sampleSilence() {
		if (!audioEl || !effectiveSkipSilence || !isPlaying) {
			resetSilenceTrimming();
			return;
		}
		const level = audioEngine.getLevel();
		if (level === null) {
			resetSilenceTrimming();
			return;
		}
		const trim = silenceGate.update(level, performance.now());
		const targetRate = trim ? Math.min(3, player.playbackSpeed * 2) : player.playbackSpeed;
		if (audioEl.playbackRate !== targetRate) audioEl.playbackRate = targetRate;
	}

	// Settings can change while the persistent player keeps playing on another
	// route. Apply them immediately instead of waiting for the next play event.
	$effect(() => {
		const boost = effectiveVolumeBoost;
		const trimSilence = effectiveSkipSilence;
		audioEngine.skipSilence = trimSilence;
		audioEngine.setVolumeBoost(boost);
		if (!trimSilence) resetSilenceTrimming();
	});

	let autoSaveTimer: any = null;

	const track = $derived(player.current);

	// React to track / play requests. Setting src for a new track loads the saved
	// position; a bumped playToken (user pressed Play) triggers autoplay — allowed
	// because it originates from a click gesture.
	$effect(() => {
		const t = player.current;
		const token = player.playToken;
		if (!t || !audioEl) return;

		const desiredSource = t.enclosure_url;
		const sourceChanged = audioEl.src !== new URL(desiredSource, location.href).href;
		if (sourceChanged) {
			trackSettings = getPodcastPlaybackSettings(t.podcast_id);
			player.setPlaybackSpeed(trackSettings.speed ?? player.defaultPlaybackSpeed, false);
			outroHandled = false;
			pendingIntroOutroSkippedMs = 0;
			loadError = '';
			loadErrorCode = '';
			audioEl.src = desiredSource;
			currentTimeMs = 0;
			loadSavedPosition(t.episode_id);
		}
		if (token !== lastToken) {
			lastToken = token;
			if (!sourceChanged) {
				const requestedPosition = player.consumeRequestedPosition();
				if (requestedPosition !== null) {
					const apply = () => {
						if (!audioEl || player.current?.episode_id !== t.episode_id) return;
						audioEl.currentTime = requestedPosition / 1000;
						currentTimeMs = requestedPosition;
						player.updatePosition(requestedPosition, durationMs || t.duration_ms);
					};
					if (audioEl.readyState >= 1 /* HAVE_METADATA */) apply();
					else audioEl.addEventListener('loadedmetadata', apply, { once: true });
				}
			}
			requestPlayback();
		}
	});

	async function loadSavedPosition(epId: string) {
		const requestedPosition = player.consumeRequestedPosition();
		const state = await getLocalPlaybackState(epId);
		if (!audioEl) return;
		const el = audioEl;
		const resume = requestedPosition === null && !!state && state.position_ms > 0 && !state.completed;
		const seconds =
			requestedPosition !== null
				? requestedPosition / 1000
				: resume
					? (state?.position_ms ?? 0) / 1000
					: trackSettings.skipIntroSeconds;
		if (seconds <= 0) return;
		const apply = () => {
			// A different track may have loaded while we awaited IndexedDB; only seek
			// if this episode is still the active one.
			if (!track || track.episode_id !== epId) return;
			el.currentTime = seconds;
			currentTimeMs = seconds * 1000;
			player.updatePosition(currentTimeMs, durationMs || track?.duration_ms || 0);
			if (!resume && requestedPosition === null) {
				if (activeSession) activeSession.intro_outro_skipped_ms += currentTimeMs;
				else pendingIntroOutroSkippedMs += currentTimeMs;
			}
		};
		// Seeking before metadata is ready is silently dropped by the browser, so
		// wait for loadedmetadata when the element hasn't parsed the header yet.
		if (el.readyState >= 1 /* HAVE_METADATA */) apply();
		else el.addEventListener('loadedmetadata', apply, { once: true });
	}

	function togglePlay() {
		if (!audioEl || !track) return;
		if (isPlaying) audioEl.pause();
		else requestPlayback();
	}

	function retryPlayback() {
		if (!audioEl || !track) return;
		loadError = '';
		loadErrorCode = '';
		audioEl.load();
		audioEl.currentTime = currentTimeMs / 1000;
		requestPlayback();
	}

	function playbackSource() {
		if (!track) return '';
		if (track.enclosure_url.startsWith('/offline/audio/')) return t('player.sourceOffline');
		try {
			return new URL(track.enclosure_url, location.href).host;
		} catch {
			return t('player.sourceUnknown');
		}
	}

	function seekTo(ms: number) {
		if (!audioEl) return;
		const forwardMs = ms - currentTimeMs;
		if (forwardMs > 2_000 && activeSession) activeSession.manual_skipped_ms += forwardMs;
		audioEl.currentTime = ms / 1000;
		currentTimeMs = ms;
		saveProgress('SEEK');
	}

	function skip(seconds: number) {
		if (!audioEl) return;
		if (seconds > 0 && activeSession) {
			const remaining = Math.max(0, (audioEl.duration || Infinity) - audioEl.currentTime);
			activeSession.manual_skipped_ms += Math.round(Math.min(seconds, remaining) * 1000);
		}
		audioEl.currentTime = Math.max(0, Math.min(audioEl.duration || 0, audioEl.currentTime + seconds));
		currentTimeMs = Math.round(audioEl.currentTime * 1000);
		saveProgress('SEEK');
	}

	const volIcon = $derived(
		player.volume === 0 ? 'ph-speaker-simple-x' : player.volume < 0.5 ? 'ph-speaker-simple-low' : 'ph-speaker-simple-high'
	);

	function setSpeed(speed: number) {
		player.setPlaybackSpeed(speed);
		resetSilenceTrimming();
	}

	function applyCustomSpeed(input: HTMLInputElement) {
		const parsed = parsePlaybackSpeed(input.value);
		if (parsed !== null) {
			setSpeed(parsed);
		}
		input.value = String(player.playbackSpeed);
	}

	function setSleepTimer(value: string) {
		player.setSleepTimer(value);
	}

	const progressPercent = $derived(
		durationMs > 0 ? Math.min(100, (currentTimeMs / durationMs) * 100) : 0
	);
	const remainingMs = $derived(Math.max(0, (durationMs || track?.duration_ms || 0) - currentTimeMs));

	function startListeningSession() {
		if (!track || activeSession) return;
		const timestamp = Date.now();
		activeSession = {
			id: crypto.randomUUID(),
			episode_id: track.episode_id,
			podcast_id: track.podcast_id,
			title: track.title,
			podcast_title: track.podcast_title,
			categories: track.categories,
			started_at: timestamp,
			ended_at: timestamp,
			wall_clock_ms: 0,
			audio_listened_ms: 0,
			speed_saved_ms: 0,
			silence_saved_ms: 0,
			manual_skipped_ms: 0,
			intro_outro_skipped_ms: 0,
			speed_weighted_ms: 0
		};
		activeSession.intro_outro_skipped_ms = pendingIntroOutroSkippedMs;
		pendingIntroOutroSkippedMs = 0;
		lastListeningSampleAt = timestamp;
	}

	function sampleListening() {
		if (!activeSession || !isPlaying || !audioEl) return;
		const timestamp = Date.now();
		// Ignore long gaps caused by suspended tabs or stalled media. Normal
		// `timeupdate` cadence remains well below this ceiling.
		const wallMs = Math.max(0, Math.min(timestamp - lastListeningSampleAt, 5_000));
		lastListeningSampleAt = timestamp;
		if (!wallMs) return;

		const baseSpeed = player.playbackSpeed;
		const actualSpeed = audioEl.playbackRate || baseSpeed;
		const baseAudioMs = wallMs * baseSpeed;
		activeSession.ended_at = timestamp;
		activeSession.wall_clock_ms += wallMs;
		activeSession.audio_listened_ms += baseAudioMs;
		activeSession.speed_weighted_ms += wallMs * baseSpeed;
		activeSession.speed_saved_ms += Math.max(0, wallMs * (baseSpeed - 1));
		activeSession.silence_saved_ms += Math.max(0, wallMs * (actualSpeed - baseSpeed));
	}

	async function flushListeningSession(final = false) {
		if (!activeSession) return;
		sampleListening();
		const session = activeSession;
		const trackedSkipMs =
			session.silence_saved_ms + session.manual_skipped_ms + session.intro_outro_skipped_ms;
		if (session.wall_clock_ms >= 1_000 || trackedSkipMs > 0) {
			await saveLocalListeningSession({ ...session, ended_at: Date.now() });
			if (final && sync.enabled) sync.syncNow();
		}
		if (final) {
			activeSession = null;
			lastListeningSampleAt = 0;
		}
	}

	function handleTimeUpdate() {
		if (!audioEl) return;
		sampleListening();
		currentTimeMs = Number.isFinite(audioEl.currentTime)
			? Math.round(audioEl.currentTime * 1000)
			: 0;
		durationMs = Number.isFinite(audioEl.duration)
			? Math.round(audioEl.duration * 1000)
			: Math.max(0, track?.duration_ms || 0);
		player.updatePosition(currentTimeMs, durationMs || track?.duration_ms || 0);

		if (player.sleepTimerEndsAt && Date.now() >= player.sleepTimerEndsAt) {
			audioEl.pause();
			player.sleepTimerEndsAt = null;
		}
		if (player.sleepAtChapterEnd && activeChapterIndex >= 0) {
			const chapter = chapters[activeChapterIndex];
			const nextChapter = chapters[activeChapterIndex + 1];
			const chapterEnd = Number(
				chapter?.endTime ??
					chapter?.end ??
					nextChapter?.startTime ??
					nextChapter?.start ??
					(Number.isFinite(audioEl.duration) ? audioEl.duration : 0)
			);
			if (chapterEnd > 0 && audioEl.currentTime >= chapterEnd - 0.2) {
				audioEl.pause();
				player.sleepAtChapterEnd = false;
				toast.info(t('player.sleepTimer'));
			}
		}
		const remaining = Math.max(0, durationMs - currentTimeMs);
		if (!outroHandled && trackSettings.skipOutroSeconds > 0 && remaining > 0 && remaining <= trackSettings.skipOutroSeconds * 1000) {
			outroHandled = true;
			if (activeSession) activeSession.intro_outro_skipped_ms += remaining;
			audioEl.currentTime = durationMs / 1000;
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
		sampleListening();
		isPlaying = false;
		player.isPlaying = false;
		await flushListeningSession(true);
		await saveProgress('MARK_PLAYED', true);
		// Stop here if a "sleep at end of episode" timer is armed.
		if (player.sleepAtEpisodeEnd) {
			player.sleepAtEpisodeEnd = false;
			return;
		}
		// Otherwise autoplay the next queued episode, if any.
		await player.playNext();
	}

	async function saveProgress(eventType = 'PROGRESS_TICK', forceCompleted = false) {
		if (!track) return;
		// Only derive completion from a *known* duration. Before metadata loads (and
		// for feeds with no declared duration) the fallback used to be 1ms, which made
		// any position read as 100% complete and wrongly evicted the episode from
		// "Continue Listening". With no real duration, never auto-complete.
		const dur = durationMs || track.duration_ms || 0;
		const hasDuration = dur > 1000;
		const remMs = hasDuration ? Math.max(0, dur - currentTimeMs) : Infinity;
		const pct = hasDuration ? Math.min(100, (currentTimeMs / dur) * 100) : 0;
		const isCompleted = forceCompleted || (hasDuration && (remMs < 120000 || pct > 95));

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
			duration_ms: durationMs || track.duration_ms,
				categories: track.categories
			});
			await saveLocalCurrentPlayback({ ...track, position_ms: currentTimeMs });
		}

	function updateMediaSession() {
		if (!('mediaSession' in navigator) || !track) return;
		try {
			navigator.mediaSession.metadata = new MediaMetadata({
				title: track.title,
				artist: track.podcast_title,
				artwork: track.artwork_url ? [{ src: track.artwork_url, sizes: '512x512' }] : []
			});
			navigator.mediaSession.setActionHandler('play', requestPlayback);
			navigator.mediaSession.setActionHandler('pause', () => audioEl?.pause());
			navigator.mediaSession.setActionHandler('seekbackward', (d) => skip(-(d.seekOffset || 10)));
			navigator.mediaSession.setActionHandler('seekforward', (d) => skip(d.seekOffset || 30));
			navigator.mediaSession.setActionHandler('seekto', (d) => {
				if (audioEl && typeof d.seekTime === 'number') seekTo(d.seekTime * 1000);
			});
			navigator.mediaSession.setActionHandler('nexttrack', () => {
				player.playNext();
			});
			navigator.mediaSession.setActionHandler('previoustrack', () => skip(-15));
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

	// Audio errors and narrower desktop layouts can make the transport taller
	// than its normal 89px. Publish the real height for the surrounding shell.
	$effect(() => {
		if (!playerBarElement || typeof ResizeObserver === 'undefined') return;
		const updateHeight = () => {
			document.documentElement.style.setProperty(
				'--player-bar-height',
				`${Math.ceil(playerBarElement?.getBoundingClientRect().height || 89)}px`
			);
		};
		const observer = new ResizeObserver(updateHeight);
		observer.observe(playerBarElement);
		updateHeight();
		return () => {
			observer.disconnect();
			document.documentElement.style.removeProperty('--player-bar-height');
		};
	});

	$effect(() => {
		if (!expanded || !nowPlayingDialog) return;
		previousFocus = document.activeElement as HTMLElement | null;
		const background = [
			document.querySelector<HTMLElement>('.quiet-main'),
			document.querySelector<HTMLElement>('#navigation-rail'),
			document.querySelector<HTMLElement>('#running-order'),
			document.querySelector<HTMLElement>('.quiet-mobile-nav'),
			document.querySelector<HTMLElement>('.player-bar')
		].filter((element): element is HTMLElement => !!element);
		for (const element of background) element.inert = true;
		requestAnimationFrame(() => nowPlayingDialog?.querySelector<HTMLElement>('.np-close')?.focus());
		return () => {
			for (const element of background) element.inert = false;
			previousFocus?.focus();
			previousFocus = null;
		};
	});

	function trapNowPlayingFocus(event: KeyboardEvent) {
		if (event.key !== 'Tab' || !nowPlayingDialog) return;
		const focusable = [...nowPlayingDialog.querySelectorAll<HTMLElement>(
			'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
		)];
		if (focusable.length === 0) {
			event.preventDefault();
			nowPlayingDialog.focus();
			return;
		}
		const first = focusable[0];
		const last = focusable[focusable.length - 1];
		if (event.shiftKey && document.activeElement === first) {
			event.preventDefault();
			last.focus();
		} else if (!event.shiftKey && document.activeElement === last) {
			event.preventDefault();
			first.focus();
		}
	}

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
			toast.success(t('toast.removedFromFavorites'));
		} else {
			await addLocalFavorite({
				episode_id: track.episode_id,
				added_at: Date.now(),
				podcast_id: track.podcast_id,
				title: track.title,
				podcast_title: track.podcast_title,
				artwork_url: track.artwork_url,
				enclosure_url: track.enclosure_url,
				duration_ms: track.duration_ms,
				categories: track.categories
			});
			isFav = true;
			toast.success(t('toast.addedToFavorites'));
		}
	}

	const accentVars = $derived(
		showAccent
			? `--show-accent:${showAccent};--show-accent-soft:color-mix(in srgb, ${showAccent} 22%, transparent);`
			: '--show-accent:var(--accent-green);--show-accent-soft:color-mix(in srgb, var(--accent-green) 22%, transparent);'
	);

	// Keep the output volume in sync with the store.
	//
	// Which knob depends on whether the Web Audio graph is running. The element's
	// own volume sits before the compressor, so with volume boost on it gets
	// squashed — the slider then moves the output by about half as many dB as it
	// should and feels broken. While the graph exists the element stays wide open
	// and the volume is applied by a gain node at the end of the chain instead.
	$effect(() => {
		// Both reads have to happen here so the effect re-runs when the element is
		// recreated, not only when the volume changes.
		const element = audioEl;
		const volume = player.volume;
		audioEngine.setVolume(volume);
		if (element) element.volume = audioEngine.initialized ? 1 : volume;
	});

	/**
	 * Called at the two moments the graph appears or goes away, because
	 * `audioEngine.initialized` is not reactive state and the effect above cannot
	 * see it change.
	 */
	function applyVolume() {
		audioEngine.setVolume(player.volume);
		if (audioEl) audioEl.volume = audioEngine.initialized ? 1 : player.volume;
	}

	$effect(() => {
		const element = audioEl;
		if (!element || !('remote' in element)) {
			remotePlaybackAvailable = false;
			remotePlaybackState = 'disconnected';
			return;
		}

		const remote = element.remote;
		let availabilityWatchId: number | null = null;
		let disposed = false;
		const updateState = () => {
			remotePlaybackState = remote.state;
			if (remote.state !== 'disconnected') remotePlaybackAvailable = true;
		};
		const updateAvailability = (available: boolean) => {
			if (!disposed) remotePlaybackAvailable = available || remote.state !== 'disconnected';
		};

		updateState();
		remote.addEventListener('connecting', updateState);
		remote.addEventListener('connect', updateState);
		remote.addEventListener('disconnect', updateState);
		remote.watchAvailability(updateAvailability)
			.then((id) => {
				if (disposed) remote.cancelWatchAvailability(id);
				else availabilityWatchId = id;
			})
			.catch(() => {
				if (!disposed) remotePlaybackAvailable = remote.state !== 'disconnected';
			});

		return () => {
			disposed = true;
			remote.removeEventListener('connecting', updateState);
			remote.removeEventListener('connect', updateState);
			remote.removeEventListener('disconnect', updateState);
			if (availabilityWatchId !== null) remote.cancelWatchAvailability(availabilityWatchId);
		};
	});

	async function chooseRemotePlayback() {
		if (!audioEl || !remotePlaybackAvailable) return;
		try {
			await audioEl.remote.prompt();
		} catch (error) {
			if (error instanceof DOMException && error.name === 'NotAllowedError') return;
			toast.error(t('player.remotePlaybackFailed'));
		}
	}

	onMount(() => {
		player.registerPlaybackFinalizer(async () => {
			if (!audioEl) return;
			const element = audioEl;
			element.pause();
			await flushListeningSession(true);
			await saveProgress('PROGRESS_TICK');
			element.removeAttribute('src');
			element.load();
			isPlaying = false;
			player.isPlaying = false;
			resetSilenceTrimming();
		});
		try {
			const saved = localStorage.getItem('koalacast_playback_speed');
			if (saved) {
				const spd = parseFloat(saved);
				if (spd >= 0.25 && spd <= 4) setSpeed(spd);
			}
			const vol = localStorage.getItem('koalacast_volume');
			if (vol !== null) player.volume = Math.max(0, Math.min(1, parseFloat(vol)));
		} catch (_) {}

		player.loadQueue();

		autoSaveTimer = setInterval(() => {
			if (isPlaying) {
				saveProgress('PROGRESS_TICK');
				flushListeningSession();
			}
		}, 30000);
		silenceTimer = setInterval(sampleSilence, 50);

		const handleKeyDown = (e: KeyboardEvent) => {
			if (e.key === 'Escape') {
				if (showShortcutsModal) showShortcutsModal = false;
				else if (expanded) expanded = false;
				return;
			}
			const target = e.target as HTMLElement | null;
			if (
				target?.closest(
					'input, textarea, select, button, a[href], [contenteditable="true"], [role="slider"], [role="spinbutton"]'
				)
			) return;
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
		const handleVisibility = () => {
			if (document.visibilityState === 'hidden') flushListeningSession();
		};
		document.addEventListener('visibilitychange', handleVisibility);
		return () => {
			player.registerPlaybackFinalizer(null);
			window.removeEventListener('keydown', handleKeyDown);
			document.removeEventListener('visibilitychange', handleVisibility);
			flushListeningSession(true);
			if (autoSaveTimer) clearInterval(autoSaveTimer);
			if (silenceTimer) clearInterval(silenceTimer);
			resetSilenceTrimming();
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

	const speeds = [0.75, 1.0, 1.15, 1.25, 1.5, 1.75, 2.0, 2.5];
</script>

{#key audioElementGeneration}
	<audio
		bind:this={audioEl}
		preload="metadata"
	onplay={() => {
		isPlaying = true;
		player.isPlaying = true;
		loadError = '';
		loadErrorCode = '';
		startListeningSession();
	}}
	oncanplay={() => {
		loadError = '';
		loadErrorCode = '';
	}}
	onpause={() => {
		sampleListening();
		resetSilenceTrimming();
		isPlaying = false;
		player.isPlaying = false;
		flushListeningSession(true);
		saveProgress('PROGRESS_TICK');
	}}
	onerror={() => {
		isPlaying = false;
		player.isPlaying = false;
		const code = audioEl?.error?.code || 0;
		loadErrorCode = code ? `MEDIA_ERR_${code}` : 'MEDIA_ERR_UNKNOWN';
		loadError = audioEl?.error?.message || t('player.loadError');
	}}
	ontimeupdate={handleTimeUpdate}
		onended={handleEnded}
	></audio>
{/key}

<ShortcutsModal bind:show={showShortcutsModal} />

{#if track}
	<div class="player-shell" style={accentVars}>
		<div class="player-bar" bind:this={playerBarElement}>
			<!-- Seek progress line spanning the top of the bar -->
			<div class="progress-track" style="--progress: {progressPercent}%"></div>

			<div class="track-info">
				<button class="art-btn" onclick={() => (expanded = true)} aria-label={t('player.openFullscreen')} title={t('player.openFullscreen')}>
					<img
						src={optimizeArtwork(track.artwork_url, 120)}
						alt={track.title}
						class="artwork"
						onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')}
					/>
					<span class="art-expand"><i class="ph ph-arrows-out-simple" aria-hidden="true"></i></span>
				</button>
				<div class="meta">
					<a class="track-title" href={`/episode/${track.episode_id}`}>{track.title}</a>
					<a class="podcast-title" href={`/podcast/${track.podcast_id}`}>{track.podcast_title}</a>
						<span class="mobile-player-meta">-{formatTime(remainingMs)} · {player.playbackSpeed}×{#if player.upNext} · {t('player.nextLabel', { title: player.upNext.title })}{/if}</span>
				</div>
				<button class="track-icon" class:active={isFav} onclick={toggleFavorite} aria-label={isFav ? t('player.removeFavorite') : t('player.saveEpisode')} title={isFav ? t('player.removeFavorite') : t('player.saveEpisode')}>
					<i class="{isFav ? 'ph-fill' : 'ph'} ph-bookmark-simple"></i>
				</button>
				<a class="track-icon" href={`/podcast/${track.podcast_id}`} aria-label={t('player.openShow')} title={t('player.openShow')}>
					<i class="ph ph-arrow-square-out"></i>
				</a>
				{#if isPlaying}
					<div class="eq-bars" aria-label={t('player.audioPlaying')}>
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
						<span>
							<strong>{t('player.loadError')}</strong>
							<small>{t('player.source')}: {playbackSource()} · {loadErrorCode}</small>
						</span>
						<button onclick={retryPlayback}>{t('player.retry')}</button>
					</div>
				{/if}
				<div class="controls">
					<button class="ctrl transport-edge" onclick={() => seekTo(0)} aria-label={t('player.restartEpisode')} title={t('player.restartEpisode')}>
						<i class="ph ph-skip-back" aria-hidden="true"></i>
					</button>
					<button class="ctrl jump-control" onclick={() => skip(-15)} aria-label={t('player.skipBack')} title={t('player.skipBack')}>
						<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i><small>15</small>
					</button>
					<button class="play-btn" onclick={togglePlay} aria-label={isPlaying ? t('player.pause') : t('player.play')} title={isPlaying ? t('player.pause') : t('player.play')}>
						<PlayPauseIcon playing={isPlaying} />
					</button>
					<button class="ctrl jump-control" onclick={() => skip(30)} aria-label={t('player.skipForward30')} title={t('player.skipForward30')}>
						<i class="ph ph-arrow-clockwise" aria-hidden="true"></i><small>30</small>
					</button>
					<button class="ctrl transport-edge" onclick={() => player.playNext()} aria-label={t('player.nextEpisode')} title={t('player.nextEpisode')}>
						<i class="ph ph-skip-forward" aria-hidden="true"></i>
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
						aria-label={t('player.timeline')}
					/>
					<span class="time">-{formatTime(remainingMs)}</span>
				</div>
			</div>

			<div class="extras">
				<input
					class="speed-input"
					inputmode="decimal"
					value={String(player.playbackSpeed)}
					onfocus={(event) => event.currentTarget.select()}
					onblur={(event) => applyCustomSpeed(event.currentTarget)}
					onkeydown={(event) => {
						if (event.key === 'Enter') {
							event.preventDefault();
							applyCustomSpeed(event.currentTarget);
							event.currentTarget.blur();
						}
					}}
					aria-label={t('player.customSpeed')}
					title={t('player.customSpeedHint')}
				/><span class="speed-suffix" aria-hidden="true">×</span>
				<div class="vol-wrap">
					<button class="ctrl" onclick={() => (showVolume = !showVolume)} aria-label={t('player.volume')} title={t('player.volume')}>
						<i class="ph {volIcon}" aria-hidden="true"></i>
					</button>
					{#if showVolume}
						<div class="vol-pop">
							<input type="range" min="0" max="1" step="0.05" value={player.volume} oninput={(e) => player.setVolume(Number((e.target as HTMLInputElement).value))} aria-label={t('player.volumeLevel')} />
						</div>
					{/if}
				</div>
				{#if remotePlaybackAvailable}
					<button
						class="ctrl"
						class:active={remotePlaybackState !== 'disconnected'}
						onclick={chooseRemotePlayback}
						aria-label={t(remotePlaybackState === 'connected' ? 'player.remotePlaybackConnected' : 'player.remotePlayback')}
						title={t(remotePlaybackState === 'connected' ? 'player.remotePlaybackConnected' : 'player.remotePlayback')}
					>
						<i class="ph ph-broadcast" aria-hidden="true"></i>
					</button>
				{/if}
				<select onchange={(e) => setSleepTimer(e.currentTarget.value)} aria-label={t('player.sleepTimer')}>
					<option value="" selected={!player.sleepTimerEndsAt && !player.sleepAtEpisodeEnd && !player.sleepAtChapterEnd}>◐ {t('player.sleepOff')}</option>
					<option value="chapter" selected={player.sleepAtChapterEnd}>⏳ {t('player.sleepChapterEnd')}</option>
					<option value="episode" selected={player.sleepAtEpisodeEnd}>⌛ {t('player.sleepEpisodeEnd')}</option>
					<option value="15">15 min</option>
					<option value="30">30 min</option>
					<option value="45">45 min</option>
					<option value="60">60 min</option>
				</select>
					<a class="queue-button" href="/library?view=queue" aria-label={t('player.openQueue')}>
						<i class="ph ph-list-numbers"></i><span>{t('player.queueCount', { count: player.queue.length })}</span>
				</a>
				<button class="ctrl close-track" onclick={() => player.stop()} aria-label={t('player.closePlayer')} title={t('player.closePlayer')}>
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
			</div>
		</div>
	</div>

	<!-- Full-screen Now Playing -->
	{#if expanded}
			<div bind:this={nowPlayingDialog} class="np-overlay" style={accentVars} role="dialog" aria-modal="true" aria-label={t('player.nowPlaying')} tabindex="-1" onkeydown={trapNowPlayingFocus}>
			<div class="np-bg" style="background-image: url({optimizeArtwork(track.artwork_url, 400)})"></div>
			<button class="np-close" onclick={() => (expanded = false)} aria-label={t('player.closeFullscreen')} title={t('player.closeFullscreen')}>
				<i class="ph ph-caret-down" aria-hidden="true"></i>
			</button>

			<div class="np-content">
				<div class="np-art-wrap" class:playing={isPlaying}>
					<img
						src={optimizeArtwork(track.artwork_url, 400)}
						alt={track.title}
						class="np-art"
						onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')}
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
						aria-label={t('player.timeline')}
					/>
					<span class="time">{formatTime(durationMs || track.duration_ms)}</span>
				</div>

				<div class="np-controls">
					<button class="np-ctrl" onclick={() => skip(-10)} aria-label={t('player.skipBack10')} title={t('player.skipBack10')}>
						<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i><small>10</small>
					</button>
					<button class="np-play" onclick={togglePlay} aria-label={isPlaying ? t('player.pause') : t('player.play')} title={isPlaying ? t('player.pause') : t('player.play')}>
						<PlayPauseIcon playing={isPlaying} />
					</button>
					<button class="np-ctrl" onclick={() => skip(30)} aria-label={t('player.skipForward30')} title={t('player.skipForward30')}>
						<i class="ph ph-arrow-clockwise" aria-hidden="true"></i><small>30</small>
					</button>
				</div>

				<div class="np-volume">
					<i class="ph {volIcon}" aria-hidden="true"></i>
					<input type="range" min="0" max="1" step="0.05" value={player.volume} oninput={(e) => player.setVolume(Number((e.target as HTMLInputElement).value))} aria-label={t('player.volumeLevel')} />
				</div>

				<div class="np-extras">
					<button class="np-fav" class:active={isFav} onclick={toggleFavorite} aria-pressed={isFav} aria-label={isFav ? t('player.removeFavorite') : t('player.addFavorite')} title={isFav ? t('player.removeFavorite') : t('player.addFavorite')}>
						<i class="{isFav ? 'ph-fill ph-heart' : 'ph ph-heart'}" aria-hidden="true"></i>
					</button>
					<button class="np-pill-btn" class:active={effectiveVolumeBoost} onclick={toggleVolumeBoost} aria-pressed={effectiveVolumeBoost} aria-label={t('player.toggleVolumeBoost')} title={t('player.toggleVolumeBoost')}>
						<i class="ph ph-speaker-high" aria-hidden="true"></i> {t('player.boost')}
					</button>
					<button class="np-pill-btn" class:active={effectiveSkipSilence} onclick={toggleSkipSilence} aria-pressed={effectiveSkipSilence} aria-label={t('player.toggleSkipSilence')} title={t('player.toggleSkipSilence')}>
						<i class="ph ph-waveform" aria-hidden="true"></i> {t('player.trimSilence')}
					</button>
					{#if remotePlaybackAvailable}
						<button
							class="np-pill-btn"
							class:active={remotePlaybackState !== 'disconnected'}
							onclick={chooseRemotePlayback}
							aria-label={t(remotePlaybackState === 'connected' ? 'player.remotePlaybackConnected' : 'player.remotePlayback')}
							title={t(remotePlaybackState === 'connected' ? 'player.remotePlaybackConnected' : 'player.remotePlayback')}
						>
							<i class="ph ph-broadcast" aria-hidden="true"></i>
							{t(remotePlaybackState === 'connected' ? 'player.remotePlaybackConnected' : 'player.remotePlayback')}
						</button>
					{/if}
					<div class="speed-selector" role="group" aria-label={t('player.speed')}>
						{#each speeds as spd}
							<button onclick={() => setSpeed(spd)} class:active={player.playbackSpeed === spd} aria-pressed={player.playbackSpeed === spd}>{spd}x</button>
						{/each}
						<input
							class="speed-input"
							inputmode="decimal"
							placeholder={String(player.playbackSpeed)}
							onblur={(event) => applyCustomSpeed(event.currentTarget)}
							onkeydown={(event) => {
								if (event.key === 'Enter') {
									event.preventDefault();
									applyCustomSpeed(event.currentTarget);
									event.currentTarget.blur();
								}
							}}
							aria-label={t('player.customSpeed')}
							title={t('player.customSpeedHint')}
						/>
					</div>
					{#if chapters.length > 0}
						<button class="np-pill-btn" class:active={showChaptersDrawer} onclick={() => (showChaptersDrawer = !showChaptersDrawer)} aria-expanded={showChaptersDrawer} aria-controls="player-chapters-drawer" aria-label={t('player.toggleChapters')} title={t('player.toggleChapters')}>
								<i class="ph ph-list-numbers" aria-hidden="true"></i> {t('player.chaptersCount', { count: chapters.length })}
						</button>
					{/if}
					{#if transcriptCues.length > 0 || loadingTranscript}
						<button class="np-pill-btn" class:active={showTranscriptDrawer} onclick={() => (showTranscriptDrawer = !showTranscriptDrawer)} aria-expanded={showTranscriptDrawer} aria-controls="player-transcript-drawer" aria-label={t('episode.transcript')} title={t('episode.transcript')}>
							<i class="ph ph-article" aria-hidden="true"></i> {t('episode.transcript')}
						</button>
					{/if}
					<select onchange={(e) => setSleepTimer(e.currentTarget.value)} aria-label={t('player.sleepTimer')}>
						<option value="">💤 {t('player.sleepTimerOff')}</option>
						<option value="episode">{t('player.endOfEpisode')}</option>
						<option value="15">15 min</option>
						<option value="30">30 min</option>
						<option value="45">45 min</option>
						<option value="60">60 min</option>
					</select>
				</div>

				{#if showChaptersDrawer && chapters.length > 0}
					<div id="player-chapters-drawer" class="np-chapters-drawer" transition:slide={{ duration: 200 }}>
						<div class="drawer-header">
								<h4><i class="ph ph-list-numbers" aria-hidden="true"></i> {t('player.episodeChaptersCount', { count: chapters.length })}</h4>
							<button class="close-drawer" onclick={() => (showChaptersDrawer = false)} aria-label={t('player.closeChapters')} title={t('player.closeChapters')}>
								<i class="ph ph-x" aria-hidden="true"></i>
							</button>
						</div>
						<div class="chapters-list">
							{#each chapters as ch, i}
								{@const startSec = typeof ch.startTime === 'number' ? ch.startTime : ch.start}
								{@const isActive = activeChapterIndex === i}
								<button
									class="chapter-row"
									class:active={isActive}
									onclick={() => seekTo(startSec * 1000)}
								>
									<span class="ch-time">{formatTime(startSec * 1000)}</span>
									<span class="ch-title">{ch.title}</span>
									{#if isActive}
										<span class="ch-playing-badge"><i class="ph-fill ph-play" aria-hidden="true"></i> {t('player.playing')}</span>
									{/if}
								</button>
							{/each}
						</div>
					</div>
				{/if}

				{#if showTranscriptDrawer}
					<div id="player-transcript-drawer" class="np-chapters-drawer" transition:slide={{ duration: 200 }}>
						<div class="drawer-header">
							<h4><i class="ph ph-article" aria-hidden="true"></i> {t('episode.transcript')}</h4>
							<button class="close-drawer" onclick={() => (showTranscriptDrawer = false)} aria-label={t('player.closeFullscreen')} title={t('player.closeFullscreen')}>
								<i class="ph ph-x" aria-hidden="true"></i>
							</button>
						</div>
						{#if loadingTranscript}
							<p>{t('episode.loadingTranscript')}</p>
						{:else}
							<label class="np-transcript-search">
								<span class="sr-only">{t('episode.searchTranscript')}</span>
								<input type="search" bind:value={transcriptQuery} placeholder={t('episode.searchTranscript')} />
								<small>{t('episode.transcriptMatches', { count: filteredTranscriptCues.length })}</small>
							</label>
							<div class="chapters-list">
								{#each filteredTranscriptCues as cue}
									<button class="chapter-row" onclick={() => seekTo(Number(cue.start || 0) * 1000)}>
										<span class="ch-time">{formatTime(Number(cue.start || 0) * 1000)}</span>
										<span class="ch-title">{cue.text}</span>
									</button>
								{/each}
							</div>
						{/if}
					</div>
				{/if}

				{#if player.upNext}
					<button class="np-upnext" onclick={() => player.upNext && player.playFromQueue(player.upNext)}>
						<span class="upnext-label"><i class="ph ph-queue" aria-hidden="true"></i> {t('player.upNext')}</span>
						<span class="upnext-title" title={player.upNext.title}>{player.upNext.title}</span>
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
		overflow: visible;
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
	.load-error span { display: grid; text-align: left; }
	.load-error small { color: var(--player-muted); font-family: var(--font-mono); }
	.load-error button { border: 1px solid currentColor; color: inherit; background: transparent; padding: 5px 8px; }

	.ctrl {
		width: 44px;
		height: 44px;
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
	.speed-input {
		width: 52px;
		height: 44px;
		box-sizing: border-box;
		border: 1px solid color-mix(in srgb, var(--player-text) 18%, transparent);
		border-radius: 7px;
		background: color-mix(in srgb, var(--player-text) 8%, transparent);
		color: var(--player-text);
		text-align: center;
		font: 700 0.75rem/1 var(--font-mono);
		font-variant-numeric: tabular-nums;
	}
	.speed-input:focus { outline: 2px solid var(--show-accent, var(--accent-green)); outline-offset: 1px; }
	.speed-suffix { margin-left: -5px; font: 700 10px/1 var(--font-mono); color: var(--ink-3); }

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
		justify-content: center;
		flex-wrap: wrap;
		gap: 2px;
		max-width: 100%;
		background: color-mix(in srgb, var(--player-text) 8%, transparent);
		padding: 3px;
		border-radius: 9px;
		align-items: center;
	}
	.speed-selector button {
		background: transparent;
		color: var(--player-text);
		border: none;
		padding: 0.25rem 0.45rem;
		min-height: 44px;
		min-width: 44px;
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
	.speed-selector .speed-input { width: 54px; height: 44px; }

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
		color: var(--accent-button-text);
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
		place-items: start center;
		overflow-x: hidden;
		overflow-y: auto;
		overscroll-behavior: contain;
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
		justify-content: center;
		gap: 1.5rem;
		min-height: 100%;
		box-sizing: border-box;
		padding: max(4.5rem, env(safe-area-inset-top, 0px)) 1rem max(2rem, env(safe-area-inset-bottom, 0px));
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
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-height: 44px;
	}
	.np-title:hover { text-decoration: underline; }
	.np-podcast { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; font-size: 0.95rem; color: color-mix(in srgb, var(--player-text) 72%, transparent); }

	.np-timeline { display: flex; align-items: center; gap: 0.75rem; width: 100%; }
	.np-timeline input[type='range'] {
		flex: 1;
		-webkit-appearance: none;
		appearance: none;
		height: 24px;
		padding-block: 9px;
		box-sizing: border-box;
		border-radius: 999px;
		background: linear-gradient(
			90deg,
			var(--show-accent, var(--accent-green)) 0%,
			var(--show-accent, var(--accent-green)) var(--progress, 0%),
			color-mix(in srgb, var(--player-text) 24%, transparent) var(--progress, 0%)
		);
		/* Keep a compact 24px control area while painting only the 6px track.
		   This must follow `background`, whose shorthand resets background-clip. */
		background-clip: content-box;
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
		min-height: 44px;
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
		min-height: 44px;
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
		height: 44px;
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

	/* Chapters Drawer in Full-Screen Player */
	.np-chapters-drawer {
		width: min(90vw, 420px);
		max-height: 280px;
		background: color-mix(in srgb, var(--player-bg, #000) 80%, rgba(20, 20, 20, 0.95));
		backdrop-filter: blur(16px);
		-webkit-backdrop-filter: blur(16px);
		border: 1px solid color-mix(in srgb, var(--player-text) 18%, transparent);
		border-radius: 16px;
		padding: 0.85rem;
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
		box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
		overflow: hidden;
	}
	.drawer-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding-bottom: 0.4rem;
		border-bottom: 1px solid color-mix(in srgb, var(--player-text) 12%, transparent);
	}
	.drawer-header h4 {
		font-size: 0.92rem;
		font-weight: 700;
		color: var(--player-text);
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}
	.close-drawer {
		background: none;
		border: none;
		color: color-mix(in srgb, var(--player-text) 70%, transparent);
		font-size: 1.1rem;
		display: grid;
		place-items: center;
		padding: 0.2rem;
		border-radius: 50%;
	}
	.close-drawer:hover { color: var(--player-text); background: color-mix(in srgb, var(--player-text) 12%, transparent); }

	.chapters-list {
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
		overflow-y: auto;
		max-height: 210px;
		padding-right: 0.3rem;
	}

	.np-transcript-search {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: center;
		gap: .75rem;
		margin: .75rem 0;
	}

	.np-transcript-search input {
		min-width: 0;
		padding: .7rem .85rem;
		border: 1px solid var(--border-strong);
		border-radius: .6rem;
		background: var(--bg-panel);
		color: var(--ink-strong);
	}
	.chapter-row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		background: color-mix(in srgb, var(--player-text) 5%, transparent);
		border: 1px solid transparent;
		padding: 0.55rem 0.75rem;
		border-radius: 10px;
		text-align: left;
		color: var(--player-text);
		transition: background 0.15s ease, border-color 0.15s ease;
		cursor: pointer;
	}
	.chapter-row:hover {
		background: color-mix(in srgb, var(--player-text) 12%, transparent);
		border-color: color-mix(in srgb, var(--player-text) 20%, transparent);
	}
	.chapter-row.active {
		background: color-mix(in srgb, var(--show-accent, var(--accent-green)) 25%, transparent);
		border-color: var(--show-accent, var(--accent-green));
	}
	.ch-time {
		font-size: 0.78rem;
		font-weight: 700;
		font-family: var(--font-mono, monospace);
		color: color-mix(in srgb, var(--player-text) 75%, transparent);
		flex-shrink: 0;
	}
	.ch-title {
		flex: 1;
		font-size: 0.88rem;
		font-weight: 600;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.ch-playing-badge {
		font-size: 0.72rem;
		font-weight: 800;
		color: var(--show-accent, var(--accent-green));
		background: color-mix(in srgb, var(--show-accent, var(--accent-green)) 20%, transparent);
		padding: 0.15rem 0.45rem;
		border-radius: 999px;
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		flex-shrink: 0;
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

	/* Quiet Edition 4b transport */
	.player-shell {
		padding: 0;
		animation: none;
	}
	.player-bar {
		width: 100%;
		max-width: none;
		min-height: 89px;
		margin: 0;
		padding: 12px 20px;
		grid-template-columns: minmax(220px,1fr) minmax(420px,1.7fr) minmax(220px,1fr);
		gap: 22px;
		border: 0;
		border-top: 1px solid var(--border-ui);
		border-radius: 0;
		background: var(--bg-transport);
		color: var(--ink);
		box-shadow: none;
		backdrop-filter: none;
		-webkit-backdrop-filter: none;
		overflow: visible;
	}
	.progress-track { display: none; }
	.track-info { gap: 9px; }
	.artwork { width: 52px; height: 52px; border-radius: 5px; box-shadow: none; background: var(--bg-tile); }
	.art-expand { border-radius: 5px; }
	.track-title { color: var(--ink-2); font: 700 14px/1.3 var(--font-ui); }
	.podcast-title { color: var(--ink-3); font: 400 11px/1.3 var(--font-sans); }
	.mobile-player-meta { display: none; }
	.track-icon {
		display: grid;
		place-items: center;
		flex: 0 0 auto;
		width: 28px;
		height: 28px;
		padding: 0;
		border: 0;
		background: transparent;
		color: var(--ink-3);
		font-size: 16px;
	}
	.track-icon.active { color: var(--accent-ink); }
	.eq-bars { display: none; }
	.center { gap: 5px; }
	.controls { gap: 18px; }
		.ctrl { width: 44px; height: 44px; color: var(--ink-3); font-size: 16px; opacity: 1; }
	.ctrl:hover { background: transparent; color: var(--ink); }
	.jump-control { position: relative; }
	.jump-control small { position: absolute; font: 700 9px/1 var(--font-mono); }
	.play-btn {
		width: 44px;
		height: 44px;
		background: var(--accent-fill);
		color: var(--accent-on);
		font-size: 17px;
		box-shadow: none;
	}
	:global(:root[data-theme='light']) .play-btn { background: var(--accent-ink); color: #fff; }
	.play-btn:hover { transform: none; filter: none; }
	.timeline { gap: 11px; }
	.timeline input[type='range'] {
		height: 20px;
		padding-block: 8px;
		box-sizing: border-box;
		background: linear-gradient(90deg, var(--accent-fill) 0%, var(--accent-fill) var(--progress,0%), var(--track) var(--progress,0%));
		/* Compact 20px control area, 4px visible track. */
		background-clip: content-box;
	}
	.timeline input[type='range']::-webkit-slider-thumb { width: 12px; height: 12px; background: var(--ink); box-shadow: none; }
	.timeline input[type='range']::-moz-range-thumb { width: 12px; height: 12px; background: var(--ink); }
	.time { min-width: 40px; color: var(--ink-3); font: 500 10px/1 var(--font-mono); opacity: 1; }
	.extras { gap: 6px; }
	.speed-cycle { height: 44px; min-width: 46px; padding: 0 6px; border: 1px solid var(--border-ui); border-radius: 4px; color: var(--ink-2); font: 700 10px/1 var(--font-mono); }
	.vol-wrap .ctrl { border: 0; }
	.extras select {
		height: 44px;
		max-width: 62px;
		padding: 0 5px;
		border: 1px solid var(--border-ui);
		border-radius: 4px;
		background: transparent;
		color: var(--ink-3);
		font: 600 10px/1 var(--font-mono);
	}
	.queue-button {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		height: 44px;
		padding: 0 7px;
		border: 1px solid var(--border-ui);
		border-radius: 4px;
		color: var(--ink-2);
		font: 700 10px/1 var(--font-mono);

	}
	.queue-button i { color: var(--accent-ink); font-size: 15px; }
	.close-track { width: 28px; }

	@media (max-width: 980px) {
		.player-bar { grid-template-columns: minmax(190px,1fr) minmax(360px,1.5fr); }
		.extras { display: none; }
	}
	@media (max-width: 720px) {
		.player-shell {
			bottom: calc(68px + max(env(safe-area-inset-bottom, 0px), 8px) + var(--mobile-browser-inset, 0px));
		}
		.player-bar {
			display: grid;
			grid-template-columns: minmax(0,1fr) auto;
			grid-template-areas: 'info controls';
			min-height: 67px;
			padding: 7px 11px;
			gap: 8px;
		}
		.track-info { grid-area: info; }
		.art-btn { min-width: 44px; min-height: 44px; }
		.track-title { display: flex; align-items: center; min-height: 44px; }
		.artwork { width: 42px; height: 42px; }
		.track-icon, .eq-bars { display: none; }
		.podcast-title { display: none; }
		.mobile-player-meta {
			display: block;
			max-width: 42vw;
			overflow: hidden;
			color: var(--ink-4);
			font: 500 10px/1.35 var(--font-mono);
			text-overflow: ellipsis;

			white-space: nowrap;
		}
		.progress-track { display: block; right: 0; width: var(--progress, 0%); background: var(--accent-fill); }
		.center { grid-area: controls; }
		.controls { gap: 4px; }
		.controls .transport-edge:first-child,
		.controls .jump-control:first-of-type { display: none; }
		.play-btn { width: 44px; height: 44px; }
		.timeline { display: none; }
	}
	@media (max-height: 800px) {
		.np-content { gap: .8rem; justify-content: flex-start; }
		.np-art-wrap { width: min(48vh, 240px); }
		.np-meta { gap: .15rem; }
		.np-title { font-size: 1.1rem; }
		.np-controls { gap: 1rem; }
		.np-play { width: 64px; height: 64px; }
	}
</style>
