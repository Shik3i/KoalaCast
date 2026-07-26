<script lang="ts">
	import { t } from '$lib/i18n';
	import { page } from '$app/stores';
	import { browser } from '$app/environment';
	import DOMPurify from 'dompurify';
	import {
		getLocalPlaybackState,
		saveLocalPlaybackState,
		isLocalFavorite,
		addLocalFavorite,
		removeLocalFavorite
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { dominantColor } from '$lib/color';
	import { optimizeArtwork } from '$lib/artwork';
	import { slide } from 'svelte/transition';

	let episodeId = $state('');
	let episode = $state<any>(null);
	let podcast = $state<any>(null);
	let playbackState = $state<any>(null);
	let isLoading = $state(true);
	let isFavorite = $state(false);
	let showAccent = $state<string | null>(null);

	const accentVars = $derived(
		showAccent
			? `--show-accent:${showAccent};--show-accent-soft:color-mix(in srgb, ${showAccent} 18%, transparent);`
			: '--show-accent:var(--accent-green);--show-accent-soft:color-mix(in srgb, var(--accent-green) 14%, transparent);'
	);

	$effect(() => {
		episodeId = $page.params.id || '';
		if (episodeId) loadEpisodeDetails(episodeId);
	});

	// Monotonic id so a slow load for a previous episode can't overwrite the one the
	// user navigated to (this $effect re-runs on every route param change).
	let loadReqId = 0;

	async function loadEpisodeDetails(id: string) {
		const reqId = ++loadReqId;
		isLoading = true;
		// Reset per-episode expandable state so the previous episode's chapters or
		// transcript never bleed into the newly opened one.
		showChapters = false;
		showTranscript = false;
		chaptersLoaded = false;
		transcriptLoaded = false;
		chaptersList = [];
		transcriptCues = [];
		transcriptHtml = '';
		transcriptText = '';
		chaptersError = '';
		transcriptError = '';
		try {
			const res = await fetch(`/api/v1/episodes/${id}`);
			if (res.ok) {
				const epData = await res.json();
				if (reqId !== loadReqId) return; // superseded by a newer navigation
				episode = epData;
				if (episode.podcast_id) {
					const podRes = await fetch(`/api/v1/podcasts/${episode.podcast_id}`);
					if (podRes.ok) {
						const podData = await podRes.json();
						if (reqId !== loadReqId) return;
						podcast = podData;
					}
				}
			}

			const art = episode?.artwork_url || podcast?.artwork_url;
			if (art) dominantColor(art).then((c) => (reqId === loadReqId ? (showAccent = c) : null));

			const state = await getLocalPlaybackState(id);
			if (reqId !== loadReqId) return;
			if (state) playbackState = state;
			isFavorite = await isLocalFavorite(id);
		} catch (err) {
			console.error('Failed to load episode details', err);
		} finally {
			if (reqId === loadReqId) isLoading = false;
		}
	}

	async function toggleFavorite() {
		if (!episode) return;
		if (isFavorite) {
			await removeLocalFavorite(episode.id);
			isFavorite = false;
			toast.success(t('toast.removedFromFavorites'));
		} else {
			await addLocalFavorite({
				episode_id: episode.id,
				added_at: Date.now(),
				podcast_id: episode.podcast_id,
				title: episode.title,
				podcast_title: podcast?.title || '',
				artwork_url: episode.artwork_url || podcast?.artwork_url || '',
				enclosure_url: episode.enclosure_url,
				duration_ms: episode.duration_ms,
				categories: podcast?.categories || (podcast?.category ? [podcast.category] : [])
			});
			isFavorite = true;
			toast.success(t('toast.addedToFavorites'));
		}
	}

	function handlePlay() {
		if (!episode) return;
		player.play({
			episode_id: episode.id,
			podcast_id: episode.podcast_id,
			title: episode.title,
			podcast_title: podcast?.title || '',
			artwork_url: episode.artwork_url || podcast?.artwork_url || '',
			enclosure_url: episode.enclosure_url,
			duration_ms: episode.duration_ms,
			categories: podcast?.categories || (podcast?.category ? [podcast.category] : [])
		});
	}

	const isCurrent = $derived(player.current?.episode_id === episode?.id);

	async function handleAddToQueue() {
		if (!episode) return;
		await player.addToQueue({
			episode_id: episode.id,
			podcast_id: episode.podcast_id,
			title: episode.title,
			podcast_title: podcast?.title || '',
			artwork_url: episode.artwork_url || podcast?.artwork_url || '',
			enclosure_url: episode.enclosure_url,
			duration_ms: episode.duration_ms,
			categories: podcast?.categories || (podcast?.category ? [podcast.category] : [])
		});
		toast.success(t('toast.addedToQueue'));
	}

	function formatDuration(ms: number) {
		if (!ms) return 'Unknown duration';
		const totalSec = Math.floor(ms / 1000);
		const h = Math.floor(totalSec / 3600);
		const m = Math.floor((totalSec % 3600) / 60);
		if (h > 0) return `${h}h ${m}m`;
		return `${m}m`;
	}

	let showChapters = $state(false);
	let chaptersLoading = $state(false);
	let chaptersError = $state('');
	let chaptersList = $state<any[]>([]);
	let chaptersLoaded = $state(false);

	async function toggleChapters() {
		showChapters = !showChapters;
		if (!showChapters || chaptersLoaded || !episode?.chapters_url) return;
		chaptersLoading = true;
		chaptersError = '';
		try {
			const res = await fetch(`/api/v1/proxy/chapters?url=${encodeURIComponent(episode.chapters_url)}`);
			if (res.ok) {
				const data = await res.json();
				chaptersList = data.chapters || [];
				chaptersLoaded = true;
			} else {
				chaptersError = 'Chapters could not be loaded.';
			}
		} catch (_) {
			chaptersError = 'Chapters could not be loaded.';
		} finally {
			chaptersLoading = false;
		}
	}

	let showTranscript = $state(false);
	let transcriptLoading = $state(false);
	let transcriptError = $state('');
	let transcriptCues = $state<any[]>([]);
	let transcriptHtml = $state(''); // sanitized, for html-type transcripts
	let transcriptText = $state(''); // plain text, for vtt/srt/json/plain
	let transcriptLoaded = $state(false);

	async function toggleTranscript() {
		showTranscript = !showTranscript;
		if (!showTranscript || transcriptLoaded || !episode) return;
		transcriptLoading = true;
		transcriptError = '';
		try {
			const transcriptUrl = episode.transcripts?.[0]?.url;
			if (transcriptUrl) {
				const res = await fetch(`/api/v1/proxy/transcript?url=${encodeURIComponent(transcriptUrl)}`);
				if (res.ok) {
					const data = await res.json();
					transcriptCues = data.cues || [];
					transcriptLoaded = true;
					return;
				}
			}

			// Fallback to internal API endpoint if proxy is not used
			const res = await fetch(`/api/v1/episodes/${episode.id}/transcript?i=0`);
			if (!res.ok) {
				transcriptError = 'Transcript could not be loaded.';
				return;
			}
			const data = await res.json();
			const type = (data.type || '').toLowerCase();
			const content: string = data.content || '';
			if (type.includes('html')) {
				transcriptHtml = sanitizeHTML(content);
			} else if (type.includes('json')) {
				transcriptText = jsonTranscriptToText(content);
			} else if (type.includes('vtt') || type.includes('srt') || type.includes('subrip')) {
				transcriptText = cueTranscriptToText(content);
			} else {
				transcriptText = content;
			}
			transcriptLoaded = true;
		} catch (_) {
			transcriptError = 'Transcript could not be loaded.';
		} finally {
			transcriptLoading = false;
		}
	}

	function seekToCue(seconds: number) {
		// Start (or keep) this episode playing, then seek once the media is ready.
		// Waiting on loadedmetadata is reliable; the old fixed 100ms timeout dropped
		// the seek whenever the audio hadn't parsed its header yet.
		if (!isCurrent) handlePlay();
		const audio = document.querySelector('audio') as HTMLAudioElement | null;
		if (!audio) return;
		const doSeek = () => {
			try {
				audio.currentTime = seconds;
			} catch (_) {}
		};
		if (audio.readyState >= 1 /* HAVE_METADATA */) doSeek();
		else audio.addEventListener('loadedmetadata', doSeek, { once: true });
	}

	// Strip WEBVTT/SRT cue numbers + timestamp lines down to readable text.
	function cueTranscriptToText(raw: string): string {
		const lines = raw.split(/\r?\n/);
		const out: string[] = [];
		for (const line of lines) {
			const l = line.trim();
			if (!l || l === 'WEBVTT' || /^\d+$/.test(l) || l.includes('-->')) continue;
			out.push(l.replace(/<[^>]*>/g, '')); // drop inline VTT tags
		}
		return out.join('\n');
	}

	// Podcasting 2.0 JSON transcript → concatenated segment bodies.
	function jsonTranscriptToText(raw: string): string {
		try {
			const j = JSON.parse(raw);
			if (Array.isArray(j?.segments)) {
				return j.segments.map((s: any) => s.body ?? '').join(' ').replace(/\s+/g, ' ').trim();
			}
		} catch (_) {}
		return raw;
	}

	let sanitizeHookRegistered = false;

	function sanitizeHTML(html: string) {
		// Feed show-notes are attacker-controlled (anyone can add an arbitrary feed),
		// so they must go through a real allowlist sanitizer before {@html}. DOMPurify
		// needs a DOM, and this block only ever renders in the browser (episode is
		// populated client-side), so we no-op during SSR.
		if (!html || !browser) return '';
		// Any link opened in a new tab must carry rel="noopener noreferrer" so the
		// destination can't reach back through window.opener (reverse tabnabbing).
		if (!sanitizeHookRegistered) {
			DOMPurify.addHook('afterSanitizeAttributes', (node) => {
				if (
					node instanceof HTMLElement &&
					node.tagName === 'A' &&
					node.getAttribute('target') === '_blank'
				) {
					node.setAttribute('rel', 'noopener noreferrer');
				}
			});
			sanitizeHookRegistered = true;
		}
		return DOMPurify.sanitize(html, {
			USE_PROFILES: { html: true },
			ADD_ATTR: ['target'],
			FORBID_TAGS: ['style', 'form', 'input', 'button'],
			FORBID_ATTR: ['style']
		});
	}
</script>

{#if isLoading}
	<div class="loading">{t('episode.loading')}</div>
{:else if episode}
	<div class="episode-page" style={accentVars}>
		<div class="episode-header">
			<img src={optimizeArtwork(episode.artwork_url || podcast?.artwork_url, 350)} alt={episode.title} class="artwork" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />

			<div class="meta">
				<h2>{episode.title}</h2>
				{#if podcast}
					<span class="podcast-link"><a href={`/podcast/${podcast.id}`}>{podcast.title}</a></span>
				{/if}
				<div class="badges">
					<span class="badge">
						{episode.pub_date ? prefs.formatDate(episode.pub_date) : 'No date'}
					</span>
					<span class="badge">{formatDuration(episode.duration_ms)}</span>
					{#if episode.explicit}
						<span class="badge explicit">{t('episode.explicit')}</span>
					{/if}
				</div>

				<div class="action-buttons">
					<button class="btn-play" onclick={handlePlay}>
						<i class="ph-fill {isCurrent ? 'ph-waveform' : 'ph-play'}" aria-hidden="true"></i>
						{isCurrent ? 'Now Playing' : 'Play Episode'}
					</button>
					<button class="btn-secondary" onclick={handleAddToQueue}>
						<i class="ph ph-plus" aria-hidden="true"></i> {t('episode.addToQueue')}
					</button>
					<button class="btn-fav" class:active={isFavorite} onclick={toggleFavorite} aria-pressed={isFavorite} aria-label={isFavorite ? 'Remove from favorites' : 'Add to favorites'}>
						<i class="{isFavorite ? 'ph-fill ph-heart' : 'ph ph-heart'}" aria-hidden="true"></i>
						{isFavorite ? 'Favorited' : 'Favorite'}
					</button>
					{#if episode.chapters_url}
						<button class="btn-secondary" class:active={showChapters} onclick={toggleChapters} aria-expanded={showChapters}>
							<i class="ph ph-list-numbers" aria-hidden="true"></i> {t('episode.chapters')}
						</button>
					{/if}
					{#if episode.transcripts && episode.transcripts.length > 0}
						<button class="btn-secondary" class:active={showTranscript} onclick={toggleTranscript} aria-expanded={showTranscript}>
							<i class="ph ph-article" aria-hidden="true"></i> {t('episode.transcript')}
						</button>
					{/if}
				</div>
			</div>
		</div>

		{#if showChapters}
			<section class="chapters-card" transition:slide={{ duration: 220 }}>
				<h3><i class="ph ph-list-numbers" aria-hidden="true"></i> {t('episode.episodeChapters')}</h3>
				{#if chaptersLoading}
					<p class="transcript-status">{t('episode.loadingChapters')}</p>
				{:else if chaptersError}
					<p class="transcript-status">{chaptersError}</p>
				{:else if chaptersList.length > 0}
					<div class="chapters-list">
						{#each chaptersList as ch, i}
							<button class="chapter-row" onclick={() => seekToCue(ch.startTime)}>
								<span class="ch-time">{formatDuration(ch.startTime * 1000)}</span>
								{#if ch.img}<img src={optimizeArtwork(ch.img, 120)} alt="" class="ch-img" />{/if}
								<span class="ch-title">{ch.title || `Chapter ${i + 1}`}</span>
								<i class="ph-fill ph-play ch-play" aria-hidden="true"></i>
							</button>
						{/each}
					</div>
				{:else}
					<p class="transcript-status">{t('episode.noChapters')}</p>
				{/if}
			</section>
		{/if}

		{#if showTranscript}
			<section class="transcript-card" transition:slide={{ duration: 220 }}>
				<h3><i class="ph ph-article" aria-hidden="true"></i> {t('episode.transcript')}</h3>
				{#if transcriptLoading}
					<p class="transcript-status">{t('episode.loadingTranscript')}</p>
				{:else if transcriptError}
					<p class="transcript-status">{transcriptError}</p>
				{:else if transcriptCues.length > 0}
					<div class="cue-list">
						{#each transcriptCues as cue}
							<button class="cue-row" onclick={() => seekToCue(cue.start)}>
								<span class="cue-time">{formatDuration(cue.start * 1000)}</span>
								<span class="cue-text">{cue.text}</span>
							</button>
						{/each}
					</div>
				{:else if transcriptHtml}
					<div class="html-content transcript-body">{@html transcriptHtml}</div>
				{:else}
					<div class="transcript-body transcript-text">{transcriptText}</div>
				{/if}
			</section>
		{/if}

		<section class="description-card">
			<h3>{t('episode.showNotes')}</h3>
			<div class="html-content">
				{@html sanitizeHTML(episode.content_encoded || episode.description)}
			</div>
		</section>
	</div>
{:else}
	<div class="error">{t('episode.notFound')}</div>
{/if}

<style>
	.episode-page {
		display: flex;
		flex-direction: column;
		gap: 2rem;
		animation: page-in 0.4s var(--ease-spring, cubic-bezier(0.16, 1, 0.3, 1));
	}

	.episode-header {
		display: flex;
		gap: 2rem;
		background:
			radial-gradient(120% 140% at 0% 0%, var(--show-accent-soft, color-mix(in srgb, var(--accent-green) 14%, transparent)), transparent 60%),
			var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: var(--radius-lg, 18px);
		padding: 2rem;
		transition: background 0.5s ease;
	}

	.artwork {
		width: 200px;
		height: 200px;
		border-radius: 16px;
		object-fit: cover;
		flex-shrink: 0;
		box-shadow: var(--shadow-lg, 0 12px 30px rgba(0, 0, 0, 0.25));
	}

	.meta {
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
		min-width: 0;
	}

	.meta h2 {
		font-size: clamp(1.5rem, 3vw, 2.1rem);
		font-weight: 800;
		line-height: 1.2;
		letter-spacing: -0.02em;
	}

	.podcast-link a {
		color: var(--accent-green);
		font-weight: 700;
	}

	.badges {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin-top: 0.35rem;
	}

	.badge {
		background: var(--bg-elevated);
		color: var(--text-secondary);
		padding: 0.3rem 0.7rem;
		border-radius: 999px;
		font-size: 0.78rem;
		font-weight: 600;
	}

	.badge.explicit {
		background: color-mix(in srgb, #e5484d 18%, transparent);
		color: #e5484d;
	}

	.action-buttons {
		margin-top: 1rem;
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
	}

	.btn-play {
		background: var(--show-accent, var(--accent-green));
		color: #fff;
		border: none;
		padding: 0.7rem 1.5rem;
		border-radius: 12px;
		font-weight: 700;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		box-shadow: 0 8px 20px var(--show-accent-soft, color-mix(in srgb, var(--accent-green) 40%, transparent));
		transition: transform 0.15s ease, filter 0.2s ease;
	}
	.btn-play:hover { filter: brightness(1.08); transform: translateY(-2px); }
	.btn-play :global(.ph) { font-size: 1.15rem; }

	.btn-secondary {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
		padding: 0.7rem 1.3rem;
		border-radius: 12px;
		font-weight: 600;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
	}
	.btn-secondary:hover { border-color: var(--accent-green); color: var(--accent-green); transform: translateY(-2px); }

	.btn-fav {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
		padding: 0.7rem 1.3rem;
		border-radius: 12px;
		font-weight: 600;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		transition: all 0.2s ease;
	}
	.btn-fav :global(.ph), .btn-fav :global(.ph-fill) { font-size: 1.15rem; transition: transform 0.2s var(--ease-spring, ease); }
	.btn-fav:hover { border-color: #e5484d; color: #e5484d; transform: translateY(-2px); }
	.btn-fav.active { border-color: #e5484d; color: #e5484d; background: color-mix(in srgb, #e5484d 12%, var(--bg-surface)); }
	.btn-fav.active :global(.ph-fill) { transform: scale(1.1); }

	.description-card,
	.transcript-card,
	.chapters-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: var(--radius-lg, 18px);
		padding: 2rem;
	}
	.chapters-card h3,
	.transcript-card h3 { display: flex; align-items: center; gap: 0.5rem; }
	.chapters-card h3 :global(.ph),
	.transcript-card h3 :global(.ph) { color: var(--show-accent, var(--accent-green)); }
	.transcript-status { color: var(--text-muted); margin-top: 1rem; }

	.chapters-list, .cue-list {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin-top: 1.25rem;
		max-height: 60vh;
		overflow-y: auto;
	}

	.chapter-row, .cue-row {
		display: flex;
		align-items: center;
		gap: 1rem;
		padding: 0.75rem 1rem;
		border-radius: 12px;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		color: var(--text-primary);
		text-align: left;
		transition: all 0.2s ease;
		cursor: pointer;
	}
	.chapter-row:hover, .cue-row:hover {
		border-color: var(--show-accent, var(--accent-green));
		background: color-mix(in srgb, var(--show-accent, var(--accent-green)) 10%, var(--bg-surface));
	}
	.ch-time, .cue-time {
		font-family: monospace;
		font-size: 0.85rem;
		font-weight: 700;
		color: var(--show-accent, var(--accent-green));
		flex-shrink: 0;
		min-width: 60px;
	}
	.ch-img { width: 36px; height: 36px; border-radius: 6px; object-fit: cover; flex-shrink: 0; }
	.ch-title, .cue-text { flex: 1; font-weight: 500; font-size: 0.92rem; }
	.ch-play { color: var(--text-muted); font-size: 0.95rem; }
	.chapter-row:hover .ch-play { color: var(--show-accent, var(--accent-green)); }
	.transcript-body {
		margin-top: 1rem;
		max-height: 60vh;
		overflow-y: auto;
		line-height: 1.7;
		color: var(--text-secondary);
	}
	.transcript-text { white-space: pre-wrap; overflow-wrap: anywhere; }
	.btn-secondary.active { border-color: var(--show-accent, var(--accent-green)); color: var(--show-accent, var(--accent-green)); }

	.description-card h3 {
		font-size: 1.25rem;
		margin-bottom: 1rem;
		font-weight: 700;
	}

	.html-content {
		line-height: 1.7;
		color: var(--text-secondary);
		overflow-wrap: anywhere;
	}
	.html-content :global(a) { color: var(--accent-green); font-weight: 600; }
	.html-content :global(img) { max-width: 100%; height: auto; border-radius: 10px; }
	.html-content :global(p) { margin-bottom: 0.85rem; }

	.loading, .error {
		padding: 4rem 2rem;
		text-align: center;
		color: var(--text-muted);
	}

	@keyframes page-in {
		from { opacity: 0; transform: translateY(10px); }
		to { opacity: 1; transform: translateY(0); }
	}

	@media (max-width: 640px) {
		.episode-header { flex-direction: column; gap: 1.25rem; padding: 1.25rem; }
		.artwork { width: 140px; height: 140px; }
	}
	@media (prefers-reduced-motion: reduce) {
		.episode-page { animation: none; }
	}
</style>
