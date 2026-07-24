<script lang="ts">
	import { page } from '$app/stores';
	import { browser } from '$app/environment';
	import DOMPurify from 'dompurify';
	import { getLocalPlaybackState, saveLocalPlaybackState, type LocalQueueItem, addToLocalQueue } from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';

	let episodeId = $state('');
	let episode = $state<any>(null);
	let podcast = $state<any>(null);
	let playbackState = $state<any>(null);
	let isLoading = $state(true);
	let isFavorite = $state(false);

	$effect(() => {
		episodeId = $page.params.id || '';
		if (episodeId) loadEpisodeDetails(episodeId);
	});

	async function loadEpisodeDetails(id: string) {
		isLoading = true;
		try {
			const res = await fetch(`/api/v1/episodes/${id}`);
			if (res.ok) {
				episode = await res.json();
				if (episode.podcast_id) {
					const podRes = await fetch(`/api/v1/podcasts/${episode.podcast_id}`);
					if (podRes.ok) podcast = await podRes.json();
				}
			}

			const state = await getLocalPlaybackState(id);
			if (state) playbackState = state;
		} catch (err) {
			console.error('Failed to load episode details', err);
		} finally {
			isLoading = false;
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
			duration_ms: episode.duration_ms
		});
	}

	const isCurrent = $derived(player.current?.episode_id === episode?.id);

	async function handleAddToQueue() {
		if (!episode) return;
		const queueItem: LocalQueueItem = {
			id: crypto.randomUUID(),
			episode_id: episode.id,
			podcast_id: episode.podcast_id,
			title: episode.title,
			artwork_url: episode.artwork_url || podcast?.artwork_url || '',
			enclosure_url: episode.enclosure_url,
			duration_ms: episode.duration_ms,
			position_order: Date.now(),
			added_at: Date.now()
		};
		await addToLocalQueue(queueItem);
		alert('Added episode to queue!');
	}

	function formatDuration(ms: number) {
		if (!ms) return 'Unknown duration';
		const totalSec = Math.floor(ms / 1000);
		const h = Math.floor(totalSec / 3600);
		const m = Math.floor((totalSec % 3600) / 60);
		if (h > 0) return `${h}h ${m}m`;
		return `${m}m`;
	}

	function sanitizeHTML(html: string) {
		// Feed show-notes are attacker-controlled (anyone can add an arbitrary feed),
		// so they must go through a real allowlist sanitizer before {@html}. DOMPurify
		// needs a DOM, and this block only ever renders in the browser (episode is
		// populated client-side), so we no-op during SSR.
		if (!html || !browser) return '';
		return DOMPurify.sanitize(html, {
			USE_PROFILES: { html: true },
			ADD_ATTR: ['target'],
			FORBID_TAGS: ['style', 'form', 'input', 'button'],
			FORBID_ATTR: ['style']
		});
	}
</script>

{#if isLoading}
	<div class="loading">Loading episode...</div>
{:else if episode}
	<div class="episode-page">
		<div class="episode-header">
			<img src={episode.artwork_url || podcast?.artwork_url || '/placeholder.svg'} alt={episode.title} class="artwork" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />

			<div class="meta">
				<h2>{episode.title}</h2>
				{#if podcast}
					<span class="podcast-link"><a href={`/podcast/${podcast.id}`}>{podcast.title}</a></span>
				{/if}
				<div class="badges">
					<span class="badge">
						{episode.pub_date ? new Date(episode.pub_date * 1000).toLocaleDateString() : 'No Date'}
					</span>
					<span class="badge">{formatDuration(episode.duration_ms)}</span>
					{#if episode.explicit}
						<span class="badge explicit">Explicit</span>
					{/if}
				</div>

				<div class="action-buttons">
					<button class="btn-play" onclick={handlePlay}>
						<i class="ph-fill {isCurrent ? 'ph-waveform' : 'ph-play'}" aria-hidden="true"></i>
						{isCurrent ? 'Now Playing' : 'Play Episode'}
					</button>
					<button class="btn-secondary" onclick={handleAddToQueue}>
						<i class="ph ph-plus" aria-hidden="true"></i> Add to Queue
					</button>
				</div>
			</div>
		</div>

		<section class="description-card">
			<h3>Show Notes & Description</h3>
			<div class="html-content">
				{@html sanitizeHTML(episode.content_encoded || episode.description)}
			</div>
		</section>
	</div>
{:else}
	<div class="error">Episode not found.</div>
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
			radial-gradient(120% 140% at 0% 0%, color-mix(in srgb, var(--accent-green) 14%, transparent), transparent 60%),
			var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: var(--radius-lg, 18px);
		padding: 2rem;
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
		background: var(--accent-green);
		color: #fff;
		border: none;
		padding: 0.7rem 1.5rem;
		border-radius: 12px;
		font-weight: 700;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		box-shadow: 0 8px 20px color-mix(in srgb, var(--accent-green) 40%, transparent);
	}
	.btn-play:hover { background: var(--accent-green-hover); transform: translateY(-2px); }
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

	.description-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: var(--radius-lg, 18px);
		padding: 2rem;
	}

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
