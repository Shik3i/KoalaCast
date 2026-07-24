<script lang="ts">
	import { onMount } from 'svelte';
	import { FEATURED_PODCASTS } from '$lib/data/featured';
	import { saveLocalSubscription, getLocalSubscriptions } from '$lib/idb/db';

	interface PodcastItem {
		id: string;
		title: string;
		author: string;
		feed_url: string;
		artwork_url: string;
		category?: string;
		description?: string;
	}

	let discoverPodcasts = $state<PodcastItem[]>([]);
	let subscribedIds = $state<string[]>([]);
	let selectedCategory = $state<string>('All');
	let searchQuery = $state<string>('');
	let isLoading = $state(true);
	let isSubmitting = $state(false);

	const categories = ['All', 'Technology', 'News', 'Business', 'Science', 'Comedy', 'Society'];

	onMount(async () => {
		const subs = await getLocalSubscriptions();
		subscribedIds = subs.map((s) => s.podcast_id);

		try {
			const res = await fetch('/api/v1/podcasts/discover');
			const data = await res.json();
			if (data.results && data.results.length > 0) {
				discoverPodcasts = data.results;
			} else {
				discoverPodcasts = FEATURED_PODCASTS;
			}
		} catch (err) {
			discoverPodcasts = FEATURED_PODCASTS;
		} finally {
			isLoading = false;
		}
	});

	let filteredPodcasts = $derived(
		discoverPodcasts.filter((pod) => {
			const matchesCat = selectedCategory === 'All' || (pod.category && pod.category.toLowerCase().includes(selectedCategory.toLowerCase()));
			const matchesQuery = !searchQuery.trim() || pod.title.toLowerCase().includes(searchQuery.toLowerCase()) || pod.author.toLowerCase().includes(searchQuery.toLowerCase());
			return matchesCat && matchesQuery;
		})
	);

	async function handleSubscribe(pod: PodcastItem) {
		isSubmitting = true;
		try {
			let targetFeedUrl = pod.feed_url;
			let targetId = pod.id;

			if (targetFeedUrl) {
				const res = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: targetFeedUrl })
				});
				const data = await res.json();
				if (data.id) targetId = data.id;
			}

			await saveLocalSubscription({
				podcast_id: targetId,
				feed_url: targetFeedUrl || '',
				title: pod.title,
				artwork_url: pod.artwork_url,
				added_at: Date.now()
			});

			subscribedIds = [...subscribedIds, targetId];
		} catch (err) {
			console.error('Failed to subscribe:', err);
		} finally {
			isSubmitting = false;
		}
	}
</script>

<div class="discover-experience">
	<!-- Hero Header with Live Search -->
	<section class="hero-section">
		<div class="hero-content">
			<span class="pill-badge"><i class="ph ph-sparkle" aria-hidden="true"></i> 2026 Next-Gen Podcatcher</span>
			<h1>Discover Millions of Podcasts</h1>
			<p class="subtitle">Stream top shows directly from publishers. Zero ads, zero tracking, instant playback.</p>

			<div class="search-bar-hero">
				<i class="ph ph-magnifying-glass search-icon" aria-hidden="true"></i>
				<input
					type="text"
					placeholder="Search millions of podcasts by title, author, or topic..."
					bind:value={searchQuery}
				/>
				{#if searchQuery}
					<button class="clear-btn" onclick={() => (searchQuery = '')} aria-label="Clear search input">
						<i class="ph ph-x" aria-hidden="true"></i>
					</button>
				{/if}
			</div>
		</div>
	</section>

	<!-- Category Pills Navigation -->
	<div class="category-bar">
		{#each categories as cat}
			<button
				class="cat-pill"
				class:active={selectedCategory === cat}
				onclick={() => (selectedCategory = cat)}
			>
				{cat}
			</button>
		{/each}
	</div>

	<!-- Top Charts Grid -->
	<section class="catalog-section">
		<div class="section-title-row">
			<h2>{selectedCategory === 'All' ? 'Top Trending Podcasts' : `${selectedCategory} Shows`}</h2>
			<span class="count-badge">{filteredPodcasts.length} Shows</span>
		</div>

		{#if isLoading}
			<div class="loading-state">
				<div class="spinner"></div>
				<p>Fetching live top charts...</p>
			</div>
		{:else if filteredPodcasts.length === 0}
			<div class="empty-state">
				<i class="ph ph-headphones-slash" aria-hidden="true"></i>
				<p>No podcasts found matching "{searchQuery}". Try searching another topic!</p>
			</div>
		{:else}
			<div class="podcast-grid">
				{#each filteredPodcasts as pod}
					<div class="podcast-card">
						<div class="cover-wrapper">
							<img src={pod.artwork_url || '/favicon.png'} alt={pod.title} class="cover-art" loading="lazy" />
							<div class="cover-overlay">
								<button
									class="btn-sub-overlay"
									class:subscribed={subscribedIds.includes(pod.id)}
									onclick={() => handleSubscribe(pod)}
									disabled={isSubmitting || subscribedIds.includes(pod.id)}
								>
									{#if subscribedIds.includes(pod.id)}
										<i class="ph ph-check-circle" aria-hidden="true"></i> Subscribed
									{:else}
										<i class="ph ph-plus-circle" aria-hidden="true"></i> Subscribe
									{/if}
								</button>
							</div>
							{#if pod.category}
								<span class="cat-tag">{pod.category}</span>
							{/if}
						</div>
						<div class="card-details">
							<h3 title={pod.title}>{pod.title}</h3>
							<span class="author-name">{pod.author}</span>
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</section>
</div>

<style>
	.discover-experience {
		display: flex;
		flex-direction: column;
		gap: 2.5rem;
	}

	.hero-section {
		background: linear-gradient(135deg, rgba(64, 145, 108, 0.25) 0%, rgba(19, 34, 28, 0.85) 100%), var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 16px;
		padding: 3.5rem 2.5rem;
		box-shadow: 0 16px 40px rgba(0, 0, 0, 0.3);
		backdrop-filter: blur(16px);
	}

	.hero-content {
		max-width: 680px;
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	.pill-badge {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		background: var(--accent-green);
		color: white;
		padding: 0.3rem 0.85rem;
		border-radius: 30px;
		font-size: 0.8rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		width: fit-content;
	}

	.hero-content h1 {
		font-size: 2.5rem;
		font-weight: 800;
		line-height: 1.15;
		letter-spacing: -0.02em;
	}

	.subtitle {
		color: var(--text-secondary);
		font-size: 1.1rem;
		line-height: 1.6;
	}

	.search-bar-hero {
		position: relative;
		display: flex;
		align-items: center;
		margin-top: 0.5rem;
	}

	.search-icon {
		position: absolute;
		left: 1.25rem;
		font-size: 1.3rem;
		color: var(--text-muted);
	}

	.search-bar-hero input {
		width: 100%;
		padding: 1rem 1rem 1rem 3.25rem;
		background: rgba(11, 20, 17, 0.7);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		color: var(--text-primary);
		font-size: 1rem;
		outline: none;
		transition: all 0.2s ease;
	}

	.search-bar-hero input:focus {
		border-color: var(--focus-ring);
		box-shadow: 0 0 0 3px rgba(82, 183, 136, 0.2);
	}

	.clear-btn {
		position: absolute;
		right: 1rem;
		background: none;
		border: none;
		color: var(--text-muted);
		font-size: 1.2rem;
	}

	.category-bar {
		display: flex;
		gap: 0.75rem;
		overflow-x: auto;
		padding-bottom: 0.5rem;
		scrollbar-width: none;
	}

	.cat-pill {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		color: var(--text-secondary);
		padding: 0.5rem 1.25rem;
		border-radius: 30px;
		font-weight: 600;
		font-size: 0.9rem;
		white-space: nowrap;
		transition: all 0.2s ease;
	}

	.cat-pill:hover, .cat-pill.active {
		background: var(--accent-green);
		color: white;
		border-color: var(--accent-green);
	}

	.catalog-section {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.section-title-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.section-title-row h2 {
		font-size: 1.75rem;
		font-weight: 800;
	}

	.count-badge {
		font-size: 0.85rem;
		color: var(--text-muted);
		background: var(--bg-elevated);
		padding: 0.25rem 0.75rem;
		border-radius: 12px;
		font-weight: 600;
	}

	.podcast-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
		gap: 1.75rem;
	}

	.podcast-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		overflow: hidden;
		display: flex;
		flex-direction: column;
		transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.25s ease;
	}

	.podcast-card:hover {
		transform: translateY(-4px);
		box-shadow: 0 12px 30px rgba(0, 0, 0, 0.25);
	}

	.cover-wrapper {
		position: relative;
		width: 100%;
		aspect-ratio: 1;
		overflow: hidden;
		background: var(--bg-elevated);
	}

	.cover-art {
		width: 100%;
		height: 100%;
		object-fit: cover;
		transition: transform 0.3s ease;
	}

	.podcast-card:hover .cover-art {
		transform: scale(1.05);
	}

	.cover-overlay {
		position: absolute;
		inset: 0;
		background: rgba(0, 0, 0, 0.5);
		backdrop-filter: blur(4px);
		display: flex;
		align-items: center;
		justify-content: center;
		opacity: 0;
		transition: opacity 0.2s ease;
	}

	.podcast-card:hover .cover-overlay {
		opacity: 1;
	}

	.btn-sub-overlay {
		background: var(--accent-green);
		color: white;
		border: none;
		padding: 0.6rem 1.2rem;
		border-radius: 20px;
		font-weight: 700;
		font-size: 0.85rem;
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.btn-sub-overlay.subscribed {
		background: rgba(255, 255, 255, 0.9);
		color: #192a23;
	}

	.cat-tag {
		position: absolute;
		bottom: 0.5rem;
		left: 0.5rem;
		background: rgba(0, 0, 0, 0.75);
		backdrop-filter: blur(8px);
		color: white;
		padding: 0.15rem 0.5rem;
		border-radius: 4px;
		font-size: 0.7rem;
		font-weight: 600;
	}

	.card-details {
		padding: 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}

	.card-details h3 {
		font-size: 1rem;
		font-weight: 700;
		line-height: 1.3;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.author-name {
		font-size: 0.82rem;
		color: var(--text-secondary);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.loading-state, .empty-state {
		padding: 4rem 2rem;
		text-align: center;
		color: var(--text-muted);
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1rem;
	}

	.spinner {
		width: 32px;
		height: 32px;
		border: 3px solid var(--border-subtle);
		border-top-color: var(--accent-green);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}
</style>
