<script lang="ts">
	import { onMount } from 'svelte';
	import { FEATURED_PODCASTS, type FeaturedPodcast } from '$lib/data/featured';
	import { saveLocalSubscription, getLocalSubscriptions, getLocalQueue, type LocalQueueItem } from '$lib/idb/db';

	let subscribedIds = $state<string[]>([]);
	let queueItems = $state<LocalQueueItem[]>([]);
	let isSubmitting = $state(false);

	onMount(async () => {
		const subs = await getLocalSubscriptions();
		subscribedIds = subs.map((s) => s.podcast_id);
		queueItems = await getLocalQueue();
	});

	async function handleSubscribe(pod: FeaturedPodcast) {
		isSubmitting = true;
		try {
			// Add feed via Go REST API
			const res = await fetch('/api/v1/podcasts/feed', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ feed_url: pod.feed_url })
			});

			const data = await res.json();
			const podcastId = data.id || pod.id;

			await saveLocalSubscription({
				podcast_id: podcastId,
				feed_url: pod.feed_url,
				title: pod.title,
				artwork_url: pod.artwork_url,
				added_at: Date.now()
			});

			subscribedIds = [...subscribedIds, podcastId];
			alert(`Subscribed to "${pod.title}"!`);
		} catch (err) {
			console.error('Subscribe failed', err);
		} finally {
			isSubmitting = false;
		}
	}
</script>

<div class="discover-page">
	<!-- Hero Section -->
	<section class="hero-banner">
		<div class="hero-content">
			<span class="hero-badge">KoalaCast Premium Player</span>
			<h1>Listen calmly. Synchronize across devices.</h1>
			<p class="tagline">Completely free, open-source, privacy-first podcast player. Zero tracking, zero ads, direct publisher audio streaming.</p>
		</div>
	</section>

	<!-- Discover / Featured Podcasts Grid -->
	<section class="section">
		<div class="section-header">
			<div>
				<h2>Discover Popular Podcasts</h2>
				<p class="section-desc">Explore top open-source, tech, news, and science shows.</p>
			</div>
		</div>

		<div class="podcast-grid">
			{#each FEATURED_PODCASTS as pod}
				<div class="podcast-card">
					<div class="cover-wrapper">
						<img src={pod.artwork_url} alt={pod.title} class="artwork" />
						<span class="category-tag">{pod.category}</span>
					</div>
					<div class="card-content">
						<h3>{pod.title}</h3>
						<span class="author">{pod.author}</span>
						<p class="description">{pod.description}</p>

						<div class="card-footer">
							<span class="ep-count">{pod.episodeCount} Episodes</span>
							<button
								class="btn-subscribe"
								class:subscribed={subscribedIds.includes(pod.id)}
								onclick={() => handleSubscribe(pod)}
								disabled={isSubmitting || subscribedIds.includes(pod.id)}
							>
								{#if subscribedIds.includes(pod.id)}
									<i class="ph ph-check" aria-hidden="true"></i> Subscribed
								{:else}
									<i class="ph ph-plus" aria-hidden="true"></i> Subscribe
								{/if}
							</button>
						</div>
					</div>
				</div>
			{/each}
		</div>
	</section>

	<!-- Up Next / Queue Section -->
	{#if queueItems.length > 0}
		<section class="section">
			<h2>Up Next ({queueItems.length})</h2>
			<div class="queue-list">
				{#each queueItems as item}
					<div class="queue-card">
						<img src={item.artwork_url || '/favicon.png'} alt={item.title} class="queue-art" />
						<div class="queue-meta">
							<h4>{item.title}</h4>
						</div>
					</div>
				{/each}
			</div>
		</section>
	{/if}
</div>

<style>
	.discover-page {
		display: flex;
		flex-direction: column;
		gap: 3rem;
	}

	.hero-banner {
		background: linear-gradient(135deg, rgba(45, 106, 79, 0.4) 0%, rgba(19, 34, 28, 0.9) 100%), var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		padding: 3rem 2.5rem;
		box-shadow: 0 12px 32px rgba(0, 0, 0, 0.15);
	}

	.hero-content {
		max-width: 650px;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.hero-badge {
		display: inline-block;
		background: var(--accent-green);
		color: white;
		padding: 0.25rem 0.75rem;
		border-radius: 20px;
		font-size: 0.8rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		width: fit-content;
	}

	.hero-content h1 {
		font-size: 2.25rem;
		font-weight: 700;
		line-height: 1.2;
	}

	.tagline {
		color: var(--text-secondary);
		font-size: 1.05rem;
		line-height: 1.6;
	}

	.section {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.section-header h2 {
		font-size: 1.6rem;
		font-weight: 700;
	}

	.section-desc {
		color: var(--text-secondary);
		font-size: 0.95rem;
	}

	.podcast-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
		gap: 1.75rem;
	}

	.podcast-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 10px;
		overflow: hidden;
		display: flex;
		flex-direction: column;
	}

	.cover-wrapper {
		position: relative;
		width: 100%;
		aspect-ratio: 16 / 9;
		overflow: hidden;
	}

	.artwork {
		width: 100%;
		height: 100%;
		object-fit: cover;
		transition: transform 0.3s ease;
	}

	.podcast-card:hover .artwork {
		transform: scale(1.04);
	}

	.category-tag {
		position: absolute;
		top: 1rem;
		left: 1rem;
		background: rgba(0, 0, 0, 0.75);
		backdrop-filter: blur(8px);
		color: white;
		padding: 0.2rem 0.6rem;
		border-radius: 4px;
		font-size: 0.75rem;
		font-weight: 600;
	}

	.card-content {
		padding: 1.25rem;
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
		flex: 1;
	}

	.card-content h3 {
		font-size: 1.15rem;
		font-weight: 700;
		line-height: 1.3;
	}

	.author {
		font-size: 0.85rem;
		color: var(--accent-green);
		font-weight: 600;
	}

	.description {
		font-size: 0.9rem;
		color: var(--text-secondary);
		line-height: 1.5;
		display: -webkit-box;
		-webkit-line-clamp: 3;
		line-clamp: 3;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}

	.card-footer {
		margin-top: auto;
		padding-top: 1rem;
		display: flex;
		align-items: center;
		justify-content: space-between;
		border-top: 1px solid var(--border-subtle);
	}

	.ep-count {
		font-size: 0.8rem;
		color: var(--text-muted);
	}

	.btn-subscribe {
		background: var(--accent-green);
		color: white;
		border: none;
		padding: 0.45rem 1rem;
		border-radius: 6px;
		font-weight: 600;
		font-size: 0.85rem;
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.btn-subscribe.subscribed {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
	}

	.queue-list {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.queue-card {
		display: flex;
		align-items: center;
		gap: 1rem;
		padding: 0.75rem 1rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
	}

	.queue-art {
		width: 48px;
		height: 48px;
		border-radius: 6px;
		object-fit: cover;
	}
</style>
