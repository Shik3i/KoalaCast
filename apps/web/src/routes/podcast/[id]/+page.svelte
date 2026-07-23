<script lang="ts">
	import { page } from '$app/stores';
	import { onMount } from 'svelte';

	let podcastId = $state('');
	let podcast = $state<any>(null);
	let episodes = $state<any[]>([]);
	let isLoading = $state(true);

	$effect(() => {
		podcastId = $page.params.id || '';
		if (podcastId) loadPodcastData(podcastId);
	});

	async function loadPodcastData(id: string) {
		isLoading = true;
		try {
			const [podRes, epRes] = await Promise.all([
				fetch(`/api/v1/podcasts/${id}`),
				fetch(`/api/v1/podcasts/${id}/episodes`)
			]);

			if (podRes.ok) podcast = await podRes.json();
			if (epRes.ok) {
				const epData = await epRes.json();
				episodes = epData.episodes || [];
			}
		} catch (err) {
			console.error(err);
		} finally {
			isLoading = false;
		}
	}

	function formatDuration(ms: number) {
		if (!ms) return 'Unknown';
		const totalSec = Math.floor(ms / 1000);
		const h = Math.floor(totalSec / 3600);
		const m = Math.floor((totalSec % 3600) / 60);
		if (h > 0) return `${h}h ${m}m`;
		return `${m}m`;
	}
</script>

{#if isLoading}
	<div class="loading">Loading podcast details...</div>
{:else if podcast}
	<div class="podcast-page">
		<header class="podcast-header">
			<img src={podcast.artwork_url || '/favicon.png'} alt={podcast.title} class="artwork" />
			<div class="meta">
				<h2>{podcast.title}</h2>
				<span class="author">By {podcast.author}</span>
				<p class="desc">{podcast.description}</p>
			</div>
		</header>

		<section class="episodes-section">
			<h3>Episodes ({episodes.length})</h3>
			<div class="episode-list">
				{#each episodes as ep}
					<div class="episode-row">
						<div class="ep-info">
							<h4><a href={`/episode/${ep.id}`}>{ep.title}</a></h4>
							<span class="ep-meta">
								{ep.pub_date ? new Date(ep.pub_date * 1000).toLocaleDateString() : 'No Date'} • {formatDuration(ep.duration_ms)}
							</span>
						</div>
					</div>
				{/each}
			</div>
		</section>
	</div>
{:else}
	<div class="error">Podcast not found.</div>
{/if}

<style>
	.podcast-page {
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.podcast-header {
		display: flex;
		gap: 1.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.artwork {
		width: 140px;
		height: 140px;
		border-radius: 8px;
		object-fit: cover;
	}

	.meta h2 {
		font-size: 1.5rem;
		font-weight: 700;
	}

	.author {
		color: var(--text-secondary);
		font-weight: 500;
	}

	.desc {
		margin-top: 0.5rem;
		color: var(--text-primary);
		font-size: 0.95rem;
	}

	.episodes-section h3 {
		font-size: 1.2rem;
		margin-bottom: 1rem;
	}

	.episode-list {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.episode-row {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		padding: 1rem;
	}

	.ep-info h4 a {
		color: var(--text-primary);
		font-weight: 600;
	}

	.ep-meta {
		font-size: 0.85rem;
		color: var(--text-muted);
	}
</style>
