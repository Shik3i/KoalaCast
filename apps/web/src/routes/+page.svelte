<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { FEATURED_PODCASTS } from '$lib/data/featured';
	import {
		saveLocalSubscription,
		getLocalSubscriptions,
		getRecentPlaybackStates,
		type LocalPlaybackState
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { reveal } from '$lib/actions/reveal';
	import Skeleton from '$lib/components/Skeleton.svelte';

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
	let subscribedFeeds = $state<string[]>([]);
	let continueItems = $state<LocalPlaybackState[]>([]);
	let selectedCategory = $state<string>('All');
	let searchQuery = $state<string>('');
	let isLoading = $state(true);
	let isSubmitting = $state(false);

	const categories = ['All', 'Technology', 'News', 'Business', 'Science', 'Comedy', 'Society'];

	// A card counts as subscribed if either its resolved id or its (stable) feed URL
	// matches a stored subscription. The feed URL is the reliable key: the id shown
	// on a discover card (iTunes id/slug) differs from the UUID saved after resolving.
	function isSubscribed(pod: PodcastItem) {
		return (
			subscribedIds.includes(pod.id) || (!!pod.feed_url && subscribedFeeds.includes(pod.feed_url))
		);
	}

	onMount(async () => {
		const subs = await getLocalSubscriptions();
		subscribedIds = subs.map((s) => s.podcast_id);
		subscribedFeeds = subs.map((s) => s.feed_url).filter(Boolean);
		continueItems = await getRecentPlaybackStates(8);

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

	async function openPodcastShow(pod: PodcastItem) {
		if (pod.feed_url) {
			// Resolve or add feed via API first
			try {
				const res = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: pod.feed_url })
				});
				if (res.ok) {
					const data = await res.json();
					if (data.id) {
						goto(`/podcast/${data.id}`);
						return;
					}
				}
			} catch (_) {}
		}

		goto(`/podcast/${pod.id}?feed_url=${encodeURIComponent(pod.feed_url || '')}`);
	}

	function resumePlay(item: LocalPlaybackState) {
		if (!item.enclosure_url) {
			goto(`/episode/${item.episode_id}`);
			return;
		}
		player.play({
			episode_id: item.episode_id,
			podcast_id: item.podcast_id,
			title: item.title || 'Episode',
			podcast_title: item.podcast_title || '',
			artwork_url: item.artwork_url || '',
			enclosure_url: item.enclosure_url,
			duration_ms: item.duration_ms || 0
		});
	}

	async function handleSubscribe(e: Event, pod: PodcastItem) {
		e.stopPropagation();
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
			if (targetFeedUrl) subscribedFeeds = [...subscribedFeeds, targetFeedUrl];
			toast.success(`Subscribed to ${pod.title}`);
		} catch (err) {
			console.error('Failed to subscribe:', err);
			toast.error('Could not subscribe. Please try again.');
		} finally {
			isSubmitting = false;
		}
	}
</script>

<div class="discover-experience">
	<!-- Clean Hero Header -->
	<section class="hero-section">
		<div class="hero-content">
			<span class="pill-badge"><i class="ph ph-sparkle" aria-hidden="true"></i> 🌿 Open Source Podcatcher</span>
			<h1>Discover & Listen to Podcasts</h1>
			<p class="subtitle">Stream shows directly from creators. Simple, calm, privacy-first audio playback.</p>

			<div class="search-bar-hero">
				<i class="ph ph-magnifying-glass search-icon" aria-hidden="true"></i>
				<input
					type="text"
					placeholder="Search podcasts by title, author, or topic..."
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

	<!-- Continue Listening -->
	{#if continueItems.length > 0}
		<section class="continue-section" use:reveal>
			<div class="section-title-row">
				<h2><i class="ph-fill ph-play-circle" aria-hidden="true"></i> Continue Listening</h2>
			</div>
			<div class="continue-rail">
				{#each continueItems as item (item.episode_id)}
					<button class="continue-card" onclick={() => resumePlay(item)}>
						<div class="cc-art">
							<img
								src={item.artwork_url || '/placeholder.svg'}
								alt=""
								loading="lazy"
								onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
							/>
							<span class="cc-play"><i class="ph-fill ph-play" aria-hidden="true"></i></span>
						</div>
						<div class="cc-meta">
							<span class="cc-title" title={item.title}>{item.title || 'Episode'}</span>
							<span class="cc-podcast">{item.podcast_title || ''}</span>
							<span class="cc-progress" aria-hidden="true">
								<span class="cc-progress-fill" style="width:{Math.round(item.progress_percent)}%"></span>
							</span>
						</div>
					</button>
				{/each}
			</div>
		</section>
	{/if}

	<!-- Category Pills -->
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
			<h2>{selectedCategory === 'All' ? 'Top Trending Shows' : `${selectedCategory} Shows`}</h2>
			<span class="count-badge">{filteredPodcasts.length} Shows</span>
		</div>

		{#if isLoading}
			<div class="podcast-grid">
				{#each Array(8) as _}
					<div class="skeleton-card">
						<div class="cover-wrapper"><Skeleton width="100%" height="100%" radius="0" /></div>
						<div class="card-details">
							<Skeleton width="85%" height="1rem" />
							<Skeleton width="55%" height="0.8rem" />
							<Skeleton width="100%" height="2rem" radius="6px" />
						</div>
					</div>
				{/each}
			</div>
		{:else if filteredPodcasts.length === 0}
			<div class="empty-state">
				<i class="ph ph-headphones-slash" aria-hidden="true"></i>
				<p>No podcasts found matching "{searchQuery}". Try another search term!</p>
			</div>
		{:else}
			<div class="podcast-grid">
				{#each filteredPodcasts as pod, i (pod.id)}
					<article class="podcast-card" use:reveal={{ delay: Math.min(i * 40, 320) }}>
						<button class="card-hit" onclick={() => openPodcastShow(pod)} aria-label={`Open ${pod.title}`}></button>
						<div class="cover-wrapper">
							<img
								src={pod.artwork_url || '/placeholder.svg'}
								alt={pod.title}
								class="cover-art"
								loading="lazy"
								onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
							/>
							<div class="cover-overlay">
								<span class="btn-play-overlay">
									<i class="ph-fill ph-play" aria-hidden="true"></i> View Episodes
								</span>
							</div>
							{#if pod.category}
								<span class="cat-tag">{pod.category}</span>
							{/if}
						</div>
						<div class="card-details">
							<h3 title={pod.title}>{pod.title}</h3>
							<span class="author-name">{pod.author}</span>

							<div class="card-actions">
								<button
									class="btn-sub-card"
									class:subscribed={isSubscribed(pod)}
									onclick={(e) => handleSubscribe(e, pod)}
									disabled={isSubmitting || isSubscribed(pod)}
								>
									{#if isSubscribed(pod)}
										<i class="ph ph-check" aria-hidden="true"></i> Subscribed
									{:else}
										<i class="ph ph-plus" aria-hidden="true"></i> Subscribe
									{/if}
								</button>
							</div>
						</div>
					</article>
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

	/* Intentionally a dark "showcase" panel in both themes — text is light, so the
	   gradient must be self-sufficiently dark (no bg-surface bleed) for contrast. */
	.hero-section {
		position: relative;
		overflow: hidden;
		background:
			radial-gradient(90% 130% at 8% 0%, rgba(82, 183, 136, 0.45), transparent 55%),
			linear-gradient(135deg, #1c3a2b 0%, #0e1b15 100%);
		border: 1px solid color-mix(in srgb, #52b788 22%, transparent);
		border-radius: 20px;
		padding: 3.5rem 2.5rem;
		box-shadow: 0 20px 50px rgba(0, 0, 0, 0.32);
	}
	/* Soft animated aurora blob for a bit of life behind the hero copy. */
	.hero-section::after {
		content: '';
		position: absolute;
		top: -40%;
		right: -10%;
		width: 45%;
		height: 160%;
		background: radial-gradient(circle, rgba(116, 198, 157, 0.35), transparent 65%);
		filter: blur(20px);
		animation: hero-float 9s var(--ease-out, ease-in-out) infinite alternate;
		pointer-events: none;
	}
	@keyframes hero-float {
		from { transform: translate(0, 0) scale(1); }
		to { transform: translate(-24px, 20px) scale(1.15); }
	}

	.hero-content {
		position: relative;
		z-index: 1;
		max-width: 680px;
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	.hero-content h1 { color: #f4fbf7; }
	.hero-content .subtitle { color: rgba(232, 245, 240, 0.82); }

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
		background: rgba(9, 16, 13, 0.55);
		border: 1px solid rgba(232, 245, 240, 0.18);
		border-radius: 12px;
		color: #f4fbf7;
		font-size: 1rem;
		outline: none;
		transition: all 0.2s ease;
	}
	.search-bar-hero input::placeholder { color: rgba(232, 245, 240, 0.55); }
	.search-bar-hero .search-icon { color: rgba(232, 245, 240, 0.6); }

	.search-bar-hero input:focus {
		border-color: var(--focus-ring);
		box-shadow: 0 0 0 3px rgba(82, 183, 136, 0.28);
		background: rgba(9, 16, 13, 0.72);
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
		position: relative;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		overflow: hidden;
		display: flex;
		flex-direction: column;
		cursor: pointer;
		transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.25s ease;
	}

	/* Full-card click target — a real button (keyboard-accessible) that sits
	   beneath the interactive subscribe control, so the two never nest. */
	.card-hit {
		position: absolute;
		inset: 0;
		z-index: 1;
		background: none;
		border: none;
		padding: 0;
		cursor: pointer;
	}
	.card-hit:focus-visible {
		outline: 2px solid var(--focus-ring);
		outline-offset: -2px;
		border-radius: 12px;
	}
	.card-details { position: relative; }
	.card-actions { position: relative; z-index: 2; }

	.podcast-card:hover {
		transform: translateY(-4px);
		box-shadow: 0 12px 30px rgba(0, 0, 0, 0.25);
		border-color: var(--accent-green);
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
		background: rgba(0, 0, 0, 0.55);
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

	.btn-play-overlay {
		background: var(--accent-green);
		color: white;
		padding: 0.6rem 1.2rem;
		border-radius: 20px;
		font-weight: 700;
		font-size: 0.85rem;
		display: flex;
		align-items: center;
		gap: 0.4rem;
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
		gap: 0.4rem;
		flex: 1;
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

	.card-actions {
		margin-top: auto;
		padding-top: 0.5rem;
	}

	.btn-sub-card {
		width: 100%;
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
		padding: 0.45rem;
		border-radius: 6px;
		font-weight: 700;
		font-size: 0.8rem;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.4rem;
	}

	.btn-sub-card:hover {
		background: var(--accent-green);
		color: white;
		border-color: var(--accent-green);
	}

	.btn-sub-card.subscribed {
		background: var(--accent-green);
		color: white;
		border-color: var(--accent-green);
	}

	.empty-state {
		padding: 4rem 2rem;
		text-align: center;
		color: var(--text-muted);
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1rem;
	}

	/* Continue Listening rail */
	.continue-section {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}
	.continue-section h2 {
		font-size: 1.35rem;
		font-weight: 800;
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}
	.continue-section h2 :global(.ph-fill) { color: var(--accent-green); }

	.continue-rail {
		display: flex;
		gap: 1rem;
		overflow-x: auto;
		padding-bottom: 0.5rem;
		scroll-snap-type: x mandatory;
		scrollbar-width: none;
	}
	.continue-rail::-webkit-scrollbar { display: none; }

	.continue-card {
		scroll-snap-align: start;
		flex: 0 0 auto;
		width: 240px;
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.6rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		text-align: left;
		transition: transform 0.25s var(--ease-spring), border-color 0.2s ease, box-shadow 0.25s ease;
	}
	.continue-card:hover {
		transform: translateY(-3px);
		border-color: var(--accent-green);
		box-shadow: var(--shadow-md);
	}

	.cc-art {
		position: relative;
		width: 58px;
		height: 58px;
		flex-shrink: 0;
		border-radius: 10px;
		overflow: hidden;
	}
	.cc-art img { width: 100%; height: 100%; object-fit: cover; }
	.cc-play {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgba(0, 0, 0, 0.4);
		color: #fff;
		font-size: 1.3rem;
		opacity: 0;
		transition: opacity 0.2s ease;
	}
	.continue-card:hover .cc-play { opacity: 1; }

	.cc-meta { display: flex; flex-direction: column; gap: 0.25rem; min-width: 0; flex: 1; }
	.cc-title {
		font-weight: 700;
		font-size: 0.88rem;
		color: var(--text-primary);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.cc-podcast {
		font-size: 0.76rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.cc-progress {
		margin-top: 0.15rem;
		height: 4px;
		border-radius: 999px;
		background: var(--bg-elevated);
		overflow: hidden;
	}
	.cc-progress-fill {
		display: block;
		height: 100%;
		border-radius: 999px;
		background: linear-gradient(90deg, var(--accent-green), var(--accent-green-hover));
	}

	/* Skeleton card mirrors the real podcast card layout */
	.skeleton-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		overflow: hidden;
		display: flex;
		flex-direction: column;
	}
	.skeleton-card .card-details { gap: 0.6rem; }
</style>
