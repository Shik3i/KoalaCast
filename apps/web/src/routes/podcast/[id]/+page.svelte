<script lang="ts">
	import { page } from '$app/stores';
	import { saveLocalSubscription } from '$lib/idb/db';

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
			// Check if id is an iTunes ID or feed URL passed via query param
			const urlParams = new URLSearchParams(window.location.search);
			const feedUrlParam = urlParams.get('feed_url');

			let targetId = id;

			if (feedUrlParam) {
				const addRes = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: feedUrlParam })
				});
				if (addRes.ok) {
					const addData = await addRes.json();
					if (addData.id) targetId = addData.id;
				}
			}

			// Fetch the podcast first: a numeric iTunes ID is resolved & ingested
			// server-side and comes back with its canonical (UUID) id. Episodes are
			// stored under that id, so they must be fetched with podcast.id — not the
			// original numeric id, which would return zero rows.
			const podRes = await fetch(`/api/v1/podcasts/${targetId}`);
			if (podRes.ok) {
				podcast = await podRes.json();
				const epRes = await fetch(`/api/v1/podcasts/${podcast.id}/episodes`);
				if (epRes.ok) {
					const epData = await epRes.json();
					episodes = epData.episodes || [];
				}
			}
		} catch (err) {
			console.error(err);
		} finally {
			isLoading = false;
		}
	}

	async function handleSubscribe() {
		if (!podcast) return;
		await saveLocalSubscription({
			podcast_id: podcast.id,
			feed_url: podcast.feed_url,
			title: podcast.title,
			artwork_url: podcast.artwork_url,
			added_at: Date.now()
		});
		alert(`Subscribed to "${podcast.title}"!`);
	}

	function formatDuration(ms: number) {
		if (!ms) return '';
		const totalSec = Math.floor(ms / 1000);
		const h = Math.floor(totalSec / 3600);
		const m = Math.floor((totalSec % 3600) / 60);
		if (h > 0) return `${h}h ${m}m`;
		return `${m}m`;
	}

	function playEpisode(ep: any) {
		// Dispatch audio event or navigate to episode
		window.location.href = `/episode/${ep.id}`;
	}
</script>

{#if isLoading}
	<div class="loading-state">
		<div class="spinner"></div>
		<p>Loading show metadata & episodes...</p>
	</div>
{:else if podcast}
	<div class="podcast-page">
		<!-- Podcast Cover & Meta Header -->
		<header class="podcast-header">
			<img
				src={podcast.artwork_url || '/placeholder.svg'}
				alt={podcast.title}
				class="artwork"
				onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
			/>
			<div class="meta">
				<span class="badge">Podcast Show</span>
				<h2>{podcast.title}</h2>
				<span class="author">By {podcast.author}</span>
				<p class="desc">{podcast.description}</p>

				<div class="actions">
					<button class="btn-subscribe" onclick={handleSubscribe}>
						<i class="ph ph-plus" aria-hidden="true"></i> Subscribe to Show
					</button>
				</div>
			</div>
		</header>

		<!-- Episode List -->
		<section class="episodes-section">
			<h3>Episodes ({episodes.length})</h3>
			<div class="episode-list">
				{#each episodes as ep}
					<div class="episode-row">
						<button class="btn-play" onclick={() => playEpisode(ep)} aria-label="Play episode">
							<i class="ph ph-play-fill" aria-hidden="true"></i>
						</button>

						<div class="ep-info">
							<h4><a href={`/episode/${ep.id}`}>{ep.title}</a></h4>
							<p class="ep-desc">{ep.description ? ep.description.replace(/<[^>]*>?/gm, '').slice(0, 160) + '...' : ''}</p>
							<span class="ep-meta">
								{ep.pub_date ? new Date(ep.pub_date * 1000).toLocaleDateString() : 'No Date'}
								{#if ep.duration_ms}
									• {formatDuration(ep.duration_ms)}
								{/if}
							</span>
						</div>
					</div>
				{/each}
			</div>
		</section>
	</div>
{:else}
	<div class="error-state">
		<i class="ph ph-warning-circle" aria-hidden="true"></i>
		<p>Podcast details not found.</p>
	</div>
{/if}

<style>
	.podcast-page {
		display: flex;
		flex-direction: column;
		gap: 3rem;
	}

	.podcast-header {
		display: flex;
		gap: 2.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 16px;
		padding: 2.5rem;
		backdrop-filter: blur(16px);
	}

	.artwork {
		width: 220px;
		height: 220px;
		border-radius: 12px;
		object-fit: cover;
		flex-shrink: 0;
		box-shadow: 0 12px 30px rgba(0, 0, 0, 0.3);
	}

	.meta {
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
		flex: 1;
	}

	.badge {
		background: var(--accent-green);
		color: white;
		padding: 0.2rem 0.6rem;
		border-radius: 20px;
		font-size: 0.75rem;
		font-weight: 700;
		text-transform: uppercase;
		width: fit-content;
	}

	.meta h2 {
		font-size: 2.2rem;
		font-weight: 800;
		line-height: 1.2;
	}

	.author {
		font-size: 1rem;
		color: var(--accent-green);
		font-weight: 700;
	}

	.desc {
		color: var(--text-secondary);
		font-size: 0.95rem;
		line-height: 1.6;
		display: -webkit-box;
		-webkit-line-clamp: 4;
		line-clamp: 4;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}

	.actions {
		margin-top: 0.5rem;
	}

	.btn-subscribe {
		background: var(--accent-green);
		color: white;
		border: none;
		padding: 0.65rem 1.5rem;
		border-radius: 8px;
		font-weight: 700;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
	}

	.episodes-section h3 {
		font-size: 1.5rem;
		font-weight: 800;
		margin-bottom: 1.25rem;
	}

	.episode-list {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.episode-row {
		display: flex;
		align-items: center;
		gap: 1.25rem;
		padding: 1.25rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		transition: transform 0.2s ease;
	}

	.episode-row:hover {
		transform: translateX(4px);
		border-color: var(--accent-green);
	}

	.btn-play {
		width: 48px;
		height: 48px;
		border-radius: 50%;
		background: var(--accent-green);
		color: white;
		border: none;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.4rem;
		flex-shrink: 0;
	}

	.ep-info {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
		flex: 1;
	}

	.ep-info h4 {
		font-size: 1.1rem;
		font-weight: 700;
	}

	.ep-desc {
		font-size: 0.88rem;
		color: var(--text-secondary);
	}

	.ep-meta {
		font-size: 0.8rem;
		color: var(--text-muted);
		font-weight: 600;
	}

	.loading-state, .error-state {
		padding: 5rem 2rem;
		text-align: center;
		color: var(--text-muted);
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1rem;
	}

	.spinner {
		width: 36px;
		height: 36px;
		border: 3px solid var(--border-subtle);
		border-top-color: var(--accent-green);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}
</style>
