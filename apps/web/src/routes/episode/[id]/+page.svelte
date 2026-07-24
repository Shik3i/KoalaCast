<script lang="ts">
	import { page } from '$app/stores';
	import { browser } from '$app/environment';
	import DOMPurify from 'dompurify';
	import { getLocalPlaybackState, saveLocalPlaybackState, type LocalQueueItem, addToLocalQueue } from '$lib/idb/db';

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
					<button class="btn-primary" onclick={handleAddToQueue}>+ Add to Queue</button>
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
	}

	.episode-header {
		display: flex;
		gap: 1.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.artwork {
		width: 160px;
		height: 160px;
		border-radius: 8px;
		object-fit: cover;
	}

	.meta {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	.meta h2 {
		font-size: 1.5rem;
		font-weight: 700;
	}

	.podcast-link a {
		color: var(--accent-green);
		font-weight: 600;
	}

	.badges {
		display: flex;
		gap: 0.5rem;
		margin-top: 0.5rem;
	}

	.badge {
		background: var(--bg-elevated);
		color: var(--text-secondary);
		padding: 0.25rem 0.6rem;
		border-radius: 4px;
		font-size: 0.8rem;
	}

	.badge.explicit {
		background: #f8d7da;
		color: #721c24;
	}

	.action-buttons {
		margin-top: 1rem;
	}

	.btn-primary {
		background: var(--accent-green);
		color: white;
		border: none;
		padding: 0.6rem 1.2rem;
		border-radius: 6px;
		font-weight: 600;

		cursor: pointer;
	}

	.description-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.description-card h3 {
		font-size: 1.2rem;
		margin-bottom: 1rem;
	}

	.html-content {
		line-height: 1.6;
		color: var(--text-primary);
	}
</style>
