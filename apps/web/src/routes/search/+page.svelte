<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { saveLocalSubscription, getLocalSubscriptions } from '$lib/idb/db';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { reveal } from '$lib/actions/reveal';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import { slide, fade } from 'svelte/transition';

	let searchQuery = $state('');
	let rssUrlInput = $state('');
	let showRss = $state(false);
	let isSearching = $state(false);
	let isAddingRss = $state(false);
	let errorMessage = $state('');
	let searchResults = $state<any[]>([]);
	let provider = $state<string>('');
	let subscribedIds = $state<string[]>([]);
	let subscribedFeeds = $state<string[]>([]);
	let searchTimeout: any = null;

	function isSubscribed(pod: any) {
		const feed = pod.feed_url || pod.feedUrl;
		return subscribedIds.includes(pod.id) || (!!feed && subscribedFeeds.includes(feed));
	}

	function podCategories(pod: any): string[] {
		if (Array.isArray(pod.categories) && pod.categories.length) return pod.categories;
		return pod.category ? [pod.category] : [];
	}

	// Hide vetoed genres from results.
	const visibleResults = $derived(searchResults.filter((pod) => !prefs.isHidden(podCategories(pod))));

	let recentSearches = $state<string[]>([]);
	const HISTORY_KEY = 'koalacast_search_history';

	onMount(async () => {
		const subs = await getLocalSubscriptions();
		subscribedIds = subs.map((s) => s.podcast_id);
		subscribedFeeds = subs.map((s) => s.feed_url).filter(Boolean);
		try {
			recentSearches = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]');
		} catch (_) {}
		executeSearch('technology');
	});

	function rememberSearch(query: string) {
		const q = query.trim();
		if (q.length < 2) return;
		recentSearches = [q, ...recentSearches.filter((s) => s.toLowerCase() !== q.toLowerCase())].slice(0, 8);
		try {
			localStorage.setItem(HISTORY_KEY, JSON.stringify(recentSearches));
		} catch (_) {}
	}

	function clearHistory() {
		recentSearches = [];
		try {
			localStorage.removeItem(HISTORY_KEY);
		} catch (_) {}
	}

	function runRecent(q: string) {
		searchQuery = q;
		executeSearch(q);
	}

	$effect(() => {
		const q = searchQuery;
		if (searchTimeout) clearTimeout(searchTimeout);
		if (q.trim().length > 1) {
			searchTimeout = setTimeout(() => {
				executeSearch(q);
			}, 300);
		}
	});

	async function executeSearch(query: string) {
		if (!query.trim()) return;
		isSearching = true;
		errorMessage = '';

		try {
			const res = await fetch(`/api/v1/podcasts/search?q=${encodeURIComponent(query)}`);
			const data = await res.json();
			if (data.results) {
				searchResults = data.results;
			}
			if (data.provider) provider = data.provider;
		} catch (err) {
			errorMessage = 'Failed to execute search query.';
		} finally {
			isSearching = false;
		}
	}

	function handleSearchSubmit(e: Event) {
		e.preventDefault();
		rememberSearch(searchQuery);
		executeSearch(searchQuery);
	}

	async function openPodcastShow(pod: any) {
		rememberSearch(searchQuery);
		const feedUrl = pod.feed_url || pod.feedUrl;
		if (feedUrl) {
			try {
				const res = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: feedUrl })
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

		goto(`/podcast/${pod.id}?feed_url=${encodeURIComponent(feedUrl || '')}`);
	}

	async function handleAddPodcast(e: Event, pod: any) {
		e.stopPropagation();
		try {
			let feedUrl = pod.feed_url || pod.feedUrl;
			let podId = pod.id;

			if (feedUrl) {
				const res = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: feedUrl })
				});
				const data = await res.json();
				if (data.id) podId = data.id;
			}

			await saveLocalSubscription({
				podcast_id: podId,
				feed_url: feedUrl || '',
				title: pod.title || pod.trackName,
				artwork_url: pod.artwork_url || pod.artworkUrl600,
				added_at: Date.now()
			});

			subscribedIds = [...subscribedIds, podId];
			if (feedUrl) subscribedFeeds = [...subscribedFeeds, feedUrl];
			toast.success(`Subscribed to ${pod.title || pod.trackName}`);
		} catch (err) {
			console.error(err);
			toast.error('Could not subscribe. Please try again.');
		}
	}

	async function handleAddDirectRss(e: Event) {
		e.preventDefault();
		if (!rssUrlInput.trim()) return;
		isAddingRss = true;
		errorMessage = '';

		try {
			const res = await fetch('/api/v1/podcasts/feed', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ feed_url: rssUrlInput.trim() })
			});
			const data = await res.json();
			if (!res.ok) {
				errorMessage = data.error || 'Failed to add RSS feed URL.';
				return;
			}

			await saveLocalSubscription({
				podcast_id: data.id,
				feed_url: data.feed_url,
				title: data.title,
				artwork_url: data.artwork_url,
				added_at: Date.now()
			});

			subscribedIds = [...subscribedIds, data.id];
			if (data.feed_url) subscribedFeeds = [...subscribedFeeds, data.feed_url];
			rssUrlInput = '';
			toast.success('Feed added.');
			goto(`/podcast/${data.id}`);
		} catch (err) {
			errorMessage = 'Failed to fetch RSS feed.';
		} finally {
			isAddingRss = false;
		}
	}
</script>

<div class="search-experience">
	<div class="search-hero">
		<h2>Search podcasts</h2>

		<form onsubmit={handleSearchSubmit} class="search-field" class:searching={isSearching}>
			<i class="ph ph-magnifying-glass lead" aria-hidden="true"></i>
			<input
				type="text"
				placeholder="Search millions of shows by title, author, or topic…"
				bind:value={searchQuery}
				aria-label="Search podcasts"
			/>
			{#if searchQuery}
				<button type="button" class="clear" onclick={() => (searchQuery = '')} aria-label="Clear search" transition:fade={{ duration: 120 }}>
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
			{/if}
			<button type="submit" class="go" disabled={isSearching}>
				{#if isSearching}<span class="spinner-sm" aria-hidden="true"></span>{:else}Search{/if}
			</button>
		</form>

		{#if !searchQuery.trim() && recentSearches.length > 0}
			<div class="recent-row" transition:slide={{ duration: 180 }}>
				<span class="recent-label">Recent</span>
				{#each recentSearches as q}
					<button class="recent-chip" onclick={() => runRecent(q)}>{q}</button>
				{/each}
				<button class="recent-clear" onclick={clearHistory} aria-label="Clear search history">
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
			</div>
		{/if}

		<!-- Collapsed by default: advanced "add by RSS" affordance. -->
		<div class="rss-toggle-row">
			<button class="rss-toggle" class:open={showRss} onclick={() => (showRss = !showRss)} aria-expanded={showRss}>
				<i class="ph ph-rss" aria-hidden="true"></i>
				Add a podcast by RSS URL
				<i class="ph ph-caret-down chev" aria-hidden="true"></i>
			</button>
		</div>

		{#if showRss}
			<form onsubmit={handleAddDirectRss} class="rss-field" transition:slide={{ duration: 260 }}>
				<i class="ph ph-link lead" aria-hidden="true"></i>
				<input
					type="url"
					placeholder="https://example.com/feed.xml"
					bind:value={rssUrlInput}
					required
				/>
				<button type="submit" class="go" disabled={isAddingRss}>
					{isAddingRss ? 'Adding…' : 'Add'}
				</button>
			</form>
		{/if}
	</div>

	{#if errorMessage}
		<div class="alert error" transition:slide={{ duration: 200 }}>{errorMessage}</div>
	{/if}

	<!-- Results Grid -->
	<section class="results-section">
		<div class="results-head">
			<h3>
				{#if isSearching}
					Searching live catalog...
				{:else}
					Results ({visibleResults.length})
				{/if}
			</h3>
			{#if provider && searchResults.length > 0}
				<span class="provider-credit">
					{#if provider === 'podcastindex'}
						Powered by <a href="https://podcastindex.org" target="_blank" rel="noopener noreferrer">Podcast Index</a>
					{:else}
						Results via Apple Podcasts
					{/if}
				</span>
			{/if}
		</div>

		<div class="results-grid">
			{#if isSearching && searchResults.length === 0}
				{#each Array(8) as _}
					<div class="result-card skeleton-result">
						<div class="sk-art"><Skeleton width="100%" height="100%" radius="8px" /></div>
						<Skeleton width="80%" height="1rem" />
						<Skeleton width="50%" height="0.8rem" />
						<Skeleton width="100%" height="2.1rem" radius="6px" />
					</div>
				{/each}
			{:else}
				{#each visibleResults as pod, i (pod.id ?? i)}
					<article class="result-card" use:reveal={{ delay: Math.min(i * 35, 300) }}>
						<button class="card-hit" onclick={() => openPodcastShow(pod)} aria-label={`Open ${pod.title || pod.trackName}`}></button>
						<img src={pod.artwork_url || pod.artworkUrl600 || '/placeholder.svg'} alt={pod.title || pod.trackName} class="artwork" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
						<div class="info">
							<h4>{pod.title || pod.trackName}</h4>
							<p class="author">{pod.author || pod.artistName}</p>

							<button
								class="btn-sub"
								class:subscribed={isSubscribed(pod)}
								onclick={(e) => handleAddPodcast(e, pod)}
							>
								{#if isSubscribed(pod)}
									<i class="ph ph-check" aria-hidden="true"></i> Subscribed
								{:else}
									<i class="ph ph-plus" aria-hidden="true"></i> Subscribe
								{/if}
							</button>
						</div>
					</article>
				{/each}
			{/if}
		</div>
	</section>
</div>

<style>
	.search-experience {
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.search-hero {
		display: flex;
		flex-direction: column;
		gap: 0.9rem;
	}
	.search-hero h2 {
		font-size: clamp(1.6rem, 3vw, 2.1rem);
		font-weight: 800;
		letter-spacing: -0.02em;
	}

	/* Prominent single search field with a soft focus glow. */
	.search-field {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-surface);
		border: 1.5px solid var(--border-subtle);
		border-radius: 16px;
		padding: 0.5rem 0.5rem 0.5rem 1.1rem;
		box-shadow: var(--shadow-sm);
		transition: border-color 0.2s ease, box-shadow 0.25s ease, transform 0.2s ease;
	}
	.search-field:focus-within {
		border-color: var(--focus-ring);
		box-shadow: 0 0 0 4px color-mix(in srgb, var(--focus-ring) 20%, transparent), var(--shadow-md);
	}
	.search-field .lead { font-size: 1.35rem; color: var(--text-muted); flex-shrink: 0; }
	.search-field input {
		flex: 1;
		border: none;
		background: none;
		outline: none;
		color: var(--text-primary);
		font-size: 1.05rem;
		padding: 0.5rem 0;
		min-width: 0;
	}
	.search-field input::placeholder { color: var(--text-muted); }

	.clear {
		display: grid;
		place-items: center;
		width: 34px;
		height: 34px;
		border-radius: 50%;
		border: none;
		background: transparent;
		color: var(--text-muted);
		font-size: 1.05rem;
		flex-shrink: 0;
	}
	.clear:hover { background: var(--bg-elevated); color: var(--text-primary); }

	.go {
		flex-shrink: 0;
		min-width: 92px;
		height: 44px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 0.4rem;
		padding: 0 1.3rem;
		background: var(--accent-green);
		color: #fff;
		border: none;
		border-radius: 12px;
		font-weight: 700;
		font-size: 0.95rem;
		transition: filter 0.2s ease, transform 0.15s ease;
	}
	.go:hover:not(:disabled) { filter: brightness(1.08); transform: translateY(-1px); }
	.go:disabled { opacity: 0.8; }

	.spinner-sm {
		width: 16px;
		height: 16px;
		border: 2px solid rgba(255, 255, 255, 0.45);
		border-top-color: #fff;
		border-radius: 50%;
		animation: spin 0.7s linear infinite;
	}
	@keyframes spin { to { transform: rotate(360deg); } }

	.recent-row {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 0.4rem;
	}
	.recent-label {
		font-size: 0.78rem;
		font-weight: 700;
		color: var(--text-muted);
		margin-right: 0.2rem;
	}
	.recent-chip {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		color: var(--text-secondary);
		padding: 0.3rem 0.75rem;
		border-radius: 999px;
		font-size: 0.82rem;
		font-weight: 600;
	}
	.recent-chip:hover { border-color: var(--accent-green); color: var(--accent-green); }
	.recent-clear {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
	}
	.recent-clear:hover { background: var(--bg-elevated); color: var(--text-primary); }

	/* Collapsed RSS affordance — no wasted space until the user wants it. */
	.rss-toggle-row { display: flex; }
	.rss-toggle {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		background: none;
		border: none;
		color: var(--text-muted);
		font-size: 0.9rem;
		font-weight: 600;
		padding: 0.3rem 0.2rem;
		border-radius: 8px;
	}
	.rss-toggle:hover { color: var(--accent-green); }
	.rss-toggle .chev { transition: transform 0.28s var(--ease-spring, ease); font-size: 0.95rem; }
	.rss-toggle.open .chev { transform: rotate(180deg); }
	.rss-toggle.open { color: var(--accent-green); }

	.rss-field {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		padding: 0.4rem 0.4rem 0.4rem 1rem;
	}
	.rss-field .lead { color: var(--text-muted); font-size: 1.15rem; flex-shrink: 0; }
	.rss-field input {
		flex: 1;
		border: none;
		background: none;
		outline: none;
		color: var(--text-primary);
		font-size: 0.95rem;
		padding: 0.55rem 0;
		min-width: 0;
	}
	.rss-field input::placeholder { color: var(--text-muted); }
	.rss-field .go { height: 40px; min-width: 72px; }

	.alert.error {
		padding: 1rem;
		background: var(--color-danger-bg);
		color: var(--text-primary);
		border: 1px solid var(--color-danger-border);
		border-radius: 8px;
	}

	.skeleton-result { cursor: default; }
	.skeleton-result:hover { transform: none; border-color: var(--border-subtle); }
	.sk-art { width: 100%; aspect-ratio: 1; }

	.results-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 1rem;
		flex-wrap: wrap;
		margin-bottom: 1.25rem;
	}
	.results-section h3 {
		font-size: 1.4rem;
		font-weight: 700;
	}
	.provider-credit {
		font-size: 0.8rem;
		color: var(--text-muted);
	}
	.provider-credit a {
		color: var(--text-secondary);
		font-weight: 600;
		text-decoration: underline;
		text-underline-offset: 2px;
	}
	.provider-credit a:hover { color: var(--accent-green); }

	.results-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
		gap: 1.5rem;
	}

	.result-card {
		position: relative;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		padding: 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		cursor: pointer;
		transition: transform 0.2s ease, border-color 0.2s ease;
	}

	.result-card:hover {
		transform: translateY(-4px);
		border-color: var(--accent-green);
	}

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
	.result-card .btn-sub { position: relative; z-index: 2; }

	.artwork {
		width: 100%;
		aspect-ratio: 1;
		object-fit: cover;
		border-radius: 8px;
	}

	.info h4 {
		font-size: 1rem;
		font-weight: 700;
		line-height: 1.3;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.author {
		font-size: 0.85rem;
		color: var(--text-secondary);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		margin-top: 0.2rem;
		margin-bottom: 0.5rem;
	}

	.btn-sub {
		width: 100%;
		padding: 0.5rem 1rem;
		background: var(--accent-green);
		color: white;
		border: none;
		border-radius: 6px;
		font-weight: 700;
		font-size: 0.85rem;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.4rem;
	}

	.btn-sub.subscribed {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
	}
</style>
