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
	import { prefs } from '$lib/stores/prefs.svelte';
	import { reveal } from '$lib/actions/reveal';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import Onboarding from '$lib/components/Onboarding.svelte';
	import { GENRES } from '$lib/genres';
	import { optimizeArtwork } from '$lib/artwork';
	import { SUPPORTED_REGIONS, DEFAULT_REGION } from '$lib/data/regions';

	interface PodcastItem {
		id: string;
		title: string;
		author: string;
		feed_url: string;
		artwork_url: string;
		category?: string;
		categories?: string[];
		description?: string;
	}

	let mounted = $state(false);
	let forYou = $state<PodcastItem[]>([]);

	const PAGE_SIZE = 60;

	let discoverPodcasts = $state<PodcastItem[]>([]);
	let subscribedIds = $state<string[]>([]);
	let subscribedFeeds = $state<string[]>([]);
	let continueItems = $state<LocalPlaybackState[]>([]);
	let selectedCategory = $state<string>('All');
	let selectedRegion = $state<string>(DEFAULT_REGION);
	let searchQuery = $state<string>('');
	let isLoading = $state(true);
	let isLoadingMore = $state(false);
	let isSubmitting = $state(false);
	let limit = $state(PAGE_SIZE);
	let reachedEnd = $state(false);
	// Monotonic id so a slow earlier discover response can't overwrite a newer one
	// (e.g. quick category switches or a load-more landing after a category change).
	let discoverReqId = 0;

	const categories = ['All', ...GENRES.map((g) => g.name)];

	function selectRegion(code: string) {
		selectedRegion = code;
		limit = PAGE_SIZE;
		reachedEnd = false;
		loadDiscover();
	}

	// Discover now pulls a genre-specific top chart from the server (per selected
	// category) instead of client-filtering one flat overall chart — so each
	// category returns a full list. iTunes charts have no offset, so "load more"
	// simply requests a larger limit and replaces the list.
	async function loadDiscover() {
		const reqId = ++discoverReqId;
		const params = new URLSearchParams({ limit: String(limit), region: selectedRegion });
		if (selectedCategory !== 'All') params.set('category', selectedCategory);
		try {
			const res = await fetch(`/api/v1/podcasts/discover?${params}`);
			const data = await res.json();
			if (reqId !== discoverReqId) return; // a newer request superseded this one
			const results: PodcastItem[] = data.results ?? [];
			if (results.length > 0) {
				discoverPodcasts = results;
				reachedEnd = results.length < limit;
			} else if (selectedCategory === 'All' && limit === PAGE_SIZE) {
				discoverPodcasts = FEATURED_PODCASTS;
				reachedEnd = true;
			} else {
				discoverPodcasts = [];
				reachedEnd = true;
			}
		} catch (err) {
			if (reqId !== discoverReqId) return;
			if (selectedCategory === 'All' && limit === PAGE_SIZE) discoverPodcasts = FEATURED_PODCASTS;
			reachedEnd = true;
		}
	}

	function selectCategory(cat: string) {
		if (cat === selectedCategory) return;
		selectedCategory = cat;
		limit = PAGE_SIZE;
		reachedEnd = false;
		isLoading = true;
		loadDiscover().finally(() => (isLoading = false));
	}

	async function loadMore() {
		if (isLoadingMore || reachedEnd) return;
		isLoadingMore = true;
		limit += PAGE_SIZE;
		await loadDiscover();
		isLoadingMore = false;
	}

	// A card counts as subscribed if either its resolved id or its (stable) feed URL
	// matches a stored subscription. The feed URL is the reliable key: the id shown
	// on a discover card (iTunes id/slug) differs from the UUID saved after resolving.
	function isSubscribed(pod: PodcastItem) {
		return (
			subscribedIds.includes(pod.id) || (!!pod.feed_url && subscribedFeeds.includes(pod.feed_url))
		);
	}

	onMount(async () => {
		mounted = true;
		const subs = await getLocalSubscriptions();
		subscribedIds = subs.map((s) => s.podcast_id);
		subscribedFeeds = subs.map((s) => s.feed_url).filter(Boolean);
		continueItems = await getRecentPlaybackStates(8);

		await loadDiscover();
		isLoading = false;
	});

	// Build a "For You" rail from the user's interest genres (Podcast Index trending
	// per genre, interleaved + deduped). Re-runs whenever interests change (e.g.
	// after onboarding). Everything stays on-device; only category trending is fetched.
	async function loadForYou(picks: string[]) {
		if (!mounted || picks.length === 0) {
			forYou = [];
			return;
		}
		const lists = await Promise.all(
			picks.slice(0, 4).map(async (g) => {
				try {
					const res = await fetch(`/api/v1/podcasts/discover?category=${encodeURIComponent(g)}&limit=10`);
					const data = await res.json();
					return (data.results ?? []) as PodcastItem[];
				} catch (_) {
					return [] as PodcastItem[];
				}
			})
		);
		const seen = new Set<string>();
		const merged: PodcastItem[] = [];
		const maxLen = Math.max(0, ...lists.map((l) => l.length));
		for (let i = 0; i < maxLen; i++) {
			for (const l of lists) {
				const p = l[i];
				if (!p) continue;
				const key = p.feed_url || p.id;
				if (seen.has(key) || prefs.isHidden(p.categories)) continue;
				seen.add(key);
				merged.push(p);
			}
		}
		forYou = merged.slice(0, 15);
	}

	$effect(() => {
		loadForYou(prefs.interests);
	});

	// Category is now resolved server-side; the hero search box stays a quick
	// client-side text filter over the currently loaded chart. Vetoed genres are
	// hidden everywhere.
	let filteredPodcasts = $derived(
		discoverPodcasts.filter((pod) => {
			if (prefs.isHidden(pod.categories)) return false;
			return (
				!searchQuery.trim() ||
				pod.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
				pod.author.toLowerCase().includes(searchQuery.toLowerCase())
			);
		})
	);

	// Spotlight the #1 chart entry as a wide featured card (only in the default,
	// unfiltered chart view with enough results to spare one).
	const showFeatured = $derived(!isLoading && !searchQuery.trim() && filteredPodcasts.length >= 5);
	const featured = $derived(showFeatured ? filteredPodcasts[0] : null);
	const gridPodcasts = $derived(showFeatured ? filteredPodcasts.slice(1) : filteredPodcasts);

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

{#if mounted && !prefs.onboarded}
	<Onboarding />
{/if}

<div class="discover-experience">
	<!-- Clean Hero Header -->
	<section class="hero-section">
		<div class="hero-content">
			<span class="pill-badge"><i class="ph ph-sparkle" aria-hidden="true"></i> Open Source Podcatcher</span>
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

	<!-- For You (from chosen interest genres) -->
	{#if forYou.length > 0}
		<section class="foryou-section" use:reveal>
			<div class="section-title-row">
				<h2><i class="ph-fill ph-sparkle" aria-hidden="true"></i> For You</h2>
				<a class="foryou-edit" href="/settings#interests">Edit interests</a>
			</div>
			<div class="foryou-rail">
				{#each forYou as pod (pod.feed_url || pod.id)}
					<button class="foryou-card" onclick={() => openPodcastShow(pod)}>
						<img
							class="fy-art"
							src={pod.artwork_url || '/placeholder.svg'}
							alt={pod.title}
							loading="lazy"
							onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
						/>
						<span class="fy-title" title={pod.title}>{pod.title}</span>
						<span class="fy-author">{pod.author}</span>
					</button>
				{/each}
			</div>
		</section>
	{/if}

	<!-- Region / Country Selector -->
	<div class="region-bar">
		<span class="region-label"><i class="ph ph-globe-hemisphere-west" aria-hidden="true"></i> Region:</span>
		<div class="region-pills">
			{#each SUPPORTED_REGIONS as reg}
				<button
					type="button"
					class="region-pill"
					class:active={selectedRegion === reg.code}
					onclick={() => selectRegion(reg.code)}
				>
					<span class="flag-emoji">{reg.flag}</span>
					<span>{reg.name}</span>
				</button>
			{/each}
		</div>
	</div>

	<!-- Category Pills -->
	<div class="category-bar">
		{#each categories as cat}
			<button
				class="cat-pill"
				class:active={selectedCategory === cat}
				onclick={() => selectCategory(cat)}
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
			{#if featured}
				<button class="featured-card" use:reveal onclick={() => openPodcastShow(featured)}>
					<div class="featured-art">
						<img
							src={optimizeArtwork(featured.artwork_url, 300)}
							alt={featured.title}
							width="220"
							height="220"
							loading="eager"
							fetchpriority="high"
							decoding="async"
							onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
						/>
					</div>
					<div class="featured-info">
						<span class="featured-tag">
							<i class="ph-fill ph-trend-up" aria-hidden="true"></i>
							#1 {selectedCategory === 'All' ? 'Trending' : selectedCategory}
						</span>
						<h3>{featured.title}</h3>
						<span class="featured-author">{featured.author}</span>
						{#if featured.description}<p class="featured-desc">{featured.description}</p>{/if}
						<span class="featured-cta"><i class="ph-fill ph-play" aria-hidden="true"></i> View episodes</span>
					</div>
				</button>
			{/if}

			<div class="podcast-grid">
				{#each gridPodcasts as pod, i (pod.id)}
					<article class="podcast-card" use:reveal={{ delay: Math.min(i * 40, 320) }}>
						<button class="card-hit" onclick={() => openPodcastShow(pod)} aria-label={`Open ${pod.title}`}></button>
						<div class="cover-wrapper">
							<img
								src={optimizeArtwork(pod.artwork_url, 220)}
								alt={pod.title}
								width="220"
								height="220"
								class="cover-art"
								loading="lazy"
								decoding="async"
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

			{#if !reachedEnd && !searchQuery.trim()}
				<div class="load-more-row">
					<button class="btn-load-more" onclick={loadMore} disabled={isLoadingMore}>
						{#if isLoadingMore}
							<span class="spinner-sm"></span> Loading…
						{:else}
							<i class="ph ph-arrow-down" aria-hidden="true"></i> Load more
						{/if}
					</button>
				</div>
			{/if}
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
		background: #52b788;
		color: #0b1411;
		padding: 0.3rem 0.85rem;
		border-radius: 30px;
		font-size: 0.8rem;
		font-weight: 800;
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
		background: #52b788;
		color: #0b1411;
		font-weight: 800;
		border-color: #52b788;
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

	/* Wide featured spotlight card */
	.featured-card {
		width: 100%;
		display: grid;
		grid-template-columns: 220px 1fr;
		gap: 1.75rem;
		text-align: left;
		background:
			radial-gradient(120% 140% at 0% 0%, color-mix(in srgb, var(--accent-green) 16%, transparent), transparent 60%),
			var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 20px;
		padding: 1.5rem;
		margin-bottom: 1.75rem;
		overflow: hidden;
		transition: transform 0.3s var(--ease-spring), box-shadow 0.3s ease, border-color 0.2s ease;
	}
	.featured-card:hover {
		transform: translateY(-4px);
		box-shadow: var(--shadow-lg);
		border-color: var(--accent-green);
	}
	.featured-art {
		aspect-ratio: 1;
		border-radius: 14px;
		overflow: hidden;
		box-shadow: var(--shadow-md);
	}
	.featured-art img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.4s ease; }
	.featured-card:hover .featured-art img { transform: scale(1.05); }
	.featured-info { display: flex; flex-direction: column; justify-content: center; gap: 0.55rem; min-width: 0; }
	.featured-tag {
		display: inline-flex;
		align-items: center;
		gap: 0.35rem;
		width: fit-content;
		background: #52b788;
		color: #0b1411;
		font-size: 0.72rem;
		font-weight: 900;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		padding: 0.25rem 0.7rem;
		border-radius: 999px;
	}
	.featured-info h3 { font-size: clamp(1.4rem, 2.6vw, 2rem); font-weight: 800; line-height: 1.15; letter-spacing: -0.02em; }
	.featured-author { color: var(--text-secondary); font-weight: 600; }
	.featured-desc {
		color: var(--text-muted);
		font-size: 0.92rem;
		line-height: 1.55;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.featured-cta {
		margin-top: 0.35rem;
		display: inline-flex;
		align-items: center;
		gap: 0.45rem;
		color: var(--accent-green);
		font-weight: 700;
		font-size: 0.9rem;
	}

	@media (max-width: 640px) {
		.featured-card { grid-template-columns: 1fr; }
		.featured-art { max-width: 180px; }
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

	.load-more-row {
		display: flex;
		justify-content: center;
		margin-top: 2rem;
	}
	.btn-load-more {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-surface);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
		padding: 0.7rem 1.6rem;
		border-radius: 999px;
		font-weight: 700;
		font-size: 0.9rem;
		transition: border-color 0.2s ease, transform 0.2s ease;
	}
	.btn-load-more:hover:not(:disabled) {
		border-color: var(--accent-green);
		transform: translateY(-2px);
	}
	.spinner-sm {
		width: 14px;
		height: 14px;
		border: 2px solid var(--border-subtle);
		border-top-color: var(--accent-green);
		border-radius: 50%;
		animation: spin 0.7s linear infinite;
	}
	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	/* For You rail */
	.foryou-section { display: flex; flex-direction: column; gap: 1rem; }
	.foryou-section h2 {
		font-size: 1.35rem;
		font-weight: 800;
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}
	.foryou-section h2 :global(.ph-fill) { color: var(--accent-green); }
	.foryou-edit { font-size: 0.85rem; font-weight: 600; color: var(--text-muted); }
	.foryou-edit:hover { color: var(--accent-green); text-decoration: none; }
	.foryou-rail {
		display: flex;
		gap: 1rem;
		overflow-x: auto;
		padding-bottom: 0.5rem;
		scroll-snap-type: x mandatory;
		scrollbar-width: none;
	}
	.foryou-rail::-webkit-scrollbar { display: none; }
	.foryou-card {
		scroll-snap-align: start;
		flex: 0 0 auto;
		width: 150px;
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
		background: none;
		border: none;
		text-align: left;
		padding: 0;
	}
	.fy-art {
		width: 150px;
		height: 150px;
		border-radius: 14px;
		object-fit: cover;
		border: 1px solid var(--border-subtle);
		transition: transform 0.25s var(--ease-spring), box-shadow 0.25s ease;
	}
	.foryou-card:hover .fy-art { transform: translateY(-3px); box-shadow: var(--shadow-md); }
	.fy-title {
		font-weight: 700;
		font-size: 0.88rem;
		color: var(--text-primary);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.fy-author {
		font-size: 0.78rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
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

	/* Region / Country selector bar */
	.region-bar {
		display: flex;
		align-items: center;
		gap: 0.85rem;
		flex-wrap: wrap;
		margin-bottom: 0.25rem;
	}

	.region-label {
		font-size: 0.88rem;
		font-weight: 700;
		color: var(--text-secondary);
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.region-pills {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		flex-wrap: wrap;
	}

	.region-pill {
		display: inline-flex;
		align-items: center;
		gap: 0.45rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		padding: 0.4rem 0.85rem;
		border-radius: 20px;
		font-size: 0.88rem;
		font-weight: 600;
		color: var(--text-secondary);
		cursor: pointer;
		transition: var(--transition-smooth);
	}

	.region-pill:hover {
		border-color: var(--accent-green);
		color: var(--text-primary);
		background: color-mix(in srgb, var(--accent-green) 8%, var(--bg-surface));
	}

	.region-pill.active {
		background: var(--accent-green);
		color: #ffffff;
		border-color: var(--accent-green);
	}

	.flag-emoji {
		font-family: 'Twemoji Country Flags', var(--font-sans);
		font-size: 1.1rem;
		line-height: 1;
	}
</style>
