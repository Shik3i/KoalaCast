<script lang="ts">
	import { onMount } from 'svelte';
	import { goto, replaceState } from '$app/navigation';
	import { page } from '$app/stores';
	import { saveLocalSubscription, getLocalSubscriptions } from '$lib/idb/db';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { reveal } from '$lib/actions/reveal';
	import { optimizeArtwork } from '$lib/artwork';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import { slide, fade } from 'svelte/transition';
	import { SUPPORTED_LANGUAGES, regionForLanguage } from '$lib/data/languages';
	import { GENRES, genreLabel } from '$lib/genres';
	import { t } from '$lib/i18n';
	import { cacheContent, CONTENT_TTL, contentCacheKey, readCachedContent } from '$lib/cache/content';

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
	// Monotonic id so a slow earlier query can't overwrite the results of a newer one.
	let searchReqId = 0;
	let searchController: AbortController | null = null;

	// Search filters. Languages start pre-selected from the listener's settings —
	// the same default as Discover — but unlike Discover they can be cleared here,
	// because search is exactly where someone goes looking for the one show that
	// falls outside their usual languages.
	let filterLanguages = $state<string[]>([...prefs.languages]);
	let filterGenre = $state<string>('');
	let showFilters = $state(false);
	// The term the visible results came from.
	let lastExecutedQuery = $state('');

	const hasNonDefaultFilters = $derived(
		filterGenre !== '' || !sameLanguages(filterLanguages, prefs.languages)
	);
	const activeFilterCount = $derived((filterGenre ? 1 : 0) + (sameLanguages(filterLanguages, prefs.languages) ? 0 : 1));

	function sameLanguages(a: string[], b: string[]): boolean {
		return a.length === b.length && [...a].sort().join(',') === [...b].sort().join(',');
	}

	// Re-runs the visible query after filters change.
	function rerunSearch() {
		const query = searchQuery.trim() || lastExecutedQuery;
		if (query) executeSearch(query);
	}

	function toggleFilterLanguage(code: string) {
		filterLanguages = filterLanguages.includes(code)
			? filterLanguages.filter((l) => l !== code)
			: [...filterLanguages, code];
		rerunSearch();
	}

	function setFilterGenre(genre: string) {
		filterGenre = filterGenre === genre ? '' : genre;
		rerunSearch();
	}

	// "Clear filters" drops every restriction, including the settings-derived
	// languages, so the next query searches everything.
	function clearFilters() {
		filterLanguages = [];
		filterGenre = '';
		rerunSearch();
	}

	function resetFiltersToSettings() {
		filterLanguages = [...prefs.languages];
		filterGenre = '';
		rerunSearch();
	}

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
		const initialQuery = $page.url.searchParams.get('q')?.trim() || '';
		const initialLanguages = $page.url.searchParams.get('languages')?.split(',').filter(Boolean);
		if (initialLanguages?.length) filterLanguages = initialLanguages;
		filterGenre = $page.url.searchParams.get('genre') || '';
		if (initialQuery) {
			searchQuery = initialQuery;
			void executeSearch(initialQuery);
		}
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

	function clearSearch() {
		searchController?.abort();
		searchController = null;
		searchReqId++;
		searchQuery = '';
		searchResults = [];
		lastExecutedQuery = '';
		provider = '';
		errorMessage = '';
	}

	$effect(() => {
		const q = searchQuery;
		if (searchTimeout) clearTimeout(searchTimeout);
		if (q.trim().length > 1) {
			searchTimeout = setTimeout(() => {
				executeSearch(q);
			}, 300);
		} else if (!q.trim() && lastExecutedQuery) {
			searchResults = [];
			lastExecutedQuery = '';
			provider = '';
		}
	});

	async function executeSearch(query: string) {
		if (!query.trim()) return;
		searchController?.abort();
		const controller = new AbortController();
		searchController = controller;
		const reqId = ++searchReqId;
		lastExecutedQuery = query.trim();
		isSearching = searchResults.length === 0;
		errorMessage = '';

		// The storefront follows the first selected language; the language filter
		// itself is what actually restricts results (a storefront is a market, not
		// a language). With no language filter, search the widest storefront.
		const params = new URLSearchParams({ q: query, region: regionForLanguage(filterLanguages[0] ?? 'en') });
		if (filterLanguages.length > 0) params.set('languages', filterLanguages.join(','));
		if (filterGenre) params.set('category', filterGenre);
		const visibleParams = new URLSearchParams();
		visibleParams.set('q', query.trim());
		if (filterLanguages.length) visibleParams.set('languages', filterLanguages.join(','));
		if (filterGenre) visibleParams.set('genre', filterGenre);
		replaceState(`/search?${visibleParams}`, {});

		const path = `/api/v1/podcasts/search?${params}`;
		const cacheKey = contentCacheKey(path);
		const cached = await readCachedContent<{ results?: any[]; provider?: string }>(
			cacheKey,
			CONTENT_TTL.search
		);
		if (reqId !== searchReqId) return;
		if (cached) {
			searchResults = cached.value.results ?? [];
			provider = cached.value.provider ?? '';
			isSearching = false;
			if (cached.fresh) {
				if (searchController === controller) searchController = null;
				return;
			}
		}

		try {
			const res = await fetch(path, { signal: controller.signal, cache: 'no-cache' });
			const data = await res.json();
			if (reqId !== searchReqId) return; // superseded by a newer query
			searchResults = data.results ?? [];
			if (data.provider) provider = data.provider;
			if (res.ok) await cacheContent(cacheKey, data);
		} catch (err) {
			if (reqId !== searchReqId) return;
			if (!cached) errorMessage = t('search.searchError');
		} finally {
			if (reqId === searchReqId) isSearching = false;
			if (searchController === controller) searchController = null;
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
				toast.error(t('discover.openError'));
				return;
			} catch (_) {
				toast.error(t('discover.openError'));
				return;
			}
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
			toast.success(t('toast.subscribed', { title: pod.title || pod.trackName }));
		} catch (err) {
			console.error(err);
			toast.error(t('toast.subscribeError'));
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
				errorMessage = data.error || t('search.rssError');
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
			toast.success(t('toast.feedAdded'));
			goto(`/podcast/${data.id}`);
		} catch (err) {
			errorMessage = t('search.rssError');
		} finally {
			isAddingRss = false;
		}
	}
</script>

<div class="search-experience">
	<div class="search-hero">
		<h1>{t('search.title')}</h1>

		<form onsubmit={handleSearchSubmit} class="search-field" class:searching={isSearching}>
			<i class="ph ph-magnifying-glass lead" aria-hidden="true"></i>
			<input
				type="text"
				placeholder={t('search.placeholder')}
				bind:value={searchQuery}
				aria-label={t('search.title')}
			/>
			{#if searchQuery}
				<button type="button" class="clear" onclick={clearSearch} aria-label={t('common.clearSearch')} title={t('common.clearSearch')} transition:fade={{ duration: 120 }}>
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
			{/if}
			<button type="submit" class="sr-only">{t('search.submit')}</button>
		</form>

		<!-- Filters. Languages are pre-selected from Settings so the default search
		     matches Discover; "Clear filters" drops them to search everything. -->
		<div class="filter-row">
			<button
				class="filter-toggle"
				class:open={showFilters}
				class:has-active={activeFilterCount > 0}
				onclick={() => (showFilters = !showFilters)}
				aria-expanded={showFilters}
			>
				<i class="ph ph-funnel" aria-hidden="true"></i>
				{t('search.filters')}
				{#if activeFilterCount > 0}
					<span class="filter-count">{activeFilterCount}</span>
				{/if}
				<i class="ph ph-caret-down chev" aria-hidden="true"></i>
			</button>

			{#if hasNonDefaultFilters}
				<button class="filter-clear" onclick={clearFilters}>
					<i class="ph ph-x" aria-hidden="true"></i>
					{t('search.clearFilters')}
				</button>
			{/if}
				{#if hasNonDefaultFilters}
					<button class="filter-reset" onclick={resetFiltersToSettings}>
					<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i>
					{t('search.resetFilters')}
					</button>
				{/if}
				<button class="rss-toggle" class:open={showRss} onclick={() => (showRss = !showRss)} aria-expanded={showRss}>
					<i class="ph ph-rss" aria-hidden="true"></i>
					{t('search.addByRss')}
					<i class="ph ph-caret-down chev" aria-hidden="true"></i>
				</button>
			</div>

		{#if showFilters}
			<div class="filter-panel" transition:slide={{ duration: 220 }}>
				<div class="filter-group">
					<span class="filter-label">
						<i class="ph ph-translate" aria-hidden="true"></i>
						{t('search.languageFilter')}
					</span>
					<div class="filter-pills">
						{#each SUPPORTED_LANGUAGES as lang (lang.code)}
							<button
								class="filter-pill"
								class:active={filterLanguages.includes(lang.code)}
								onclick={() => toggleFilterLanguage(lang.code)}
								aria-pressed={filterLanguages.includes(lang.code)}
							>
								<span class="flag-emoji">{lang.flag}</span>
								{lang.name}
							</button>
						{/each}
					</div>
					<p class="filter-hint">{t('search.filterHint')}</p>
				</div>

				<div class="filter-group">
					<span class="filter-label">
						<i class="ph ph-squares-four" aria-hidden="true"></i>
						{t('search.genreFilter')}
					</span>
					<div class="filter-pills">
						<button class="filter-pill" class:active={filterGenre === ''} onclick={() => setFilterGenre('')}>
							{t('search.allGenres')}
						</button>
						{#each GENRES as g (g.name)}
							<button
								class="filter-pill"
								class:active={filterGenre === g.name}
								onclick={() => setFilterGenre(g.name)}
								aria-pressed={filterGenre === g.name}
							>
								<i class="ph {g.icon}" aria-hidden="true"></i>
								{genreLabel(g.name)}
							</button>
						{/each}
					</div>
				</div>
			</div>
		{/if}

		{#if !searchQuery.trim() && recentSearches.length > 0}
			<div class="recent-row" transition:slide={{ duration: 180 }}>
				<span class="recent-label">{t('search.recent')}</span>
				{#each recentSearches as q}
					<button class="recent-chip" onclick={() => runRecent(q)}>{q}</button>
				{/each}
				<button class="recent-clear" onclick={clearHistory} aria-label={t('search.clearHistory')} title={t('search.clearHistory')}>
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
			</div>
		{/if}

			{#if showRss}
			<form onsubmit={handleAddDirectRss} class="rss-field" transition:slide={{ duration: 260 }}>
				<i class="ph ph-link lead" aria-hidden="true"></i>
				<input
					type="url"
					placeholder={t('search.rssPlaceholder')}
					bind:value={rssUrlInput}
					required
				/>
				<button type="submit" class="go" disabled={isAddingRss}>
					{isAddingRss ? t('search.adding') : t('search.add')}
				</button>
			</form>
		{/if}
	</div>

	{#if errorMessage}
		<div class="alert error" transition:slide={{ duration: 200 }}>{errorMessage}</div>
	{/if}

	<!-- Results Grid -->
	<section class="results-section" aria-live="polite">
		{#if isSearching || lastExecutedQuery}
			<div class="results-head">
				<h2>{isSearching ? t('search.searchingLive') : t('search.results', { count: visibleResults.length })}</h2>
				{#if provider && searchResults.length > 0}
					<span class="provider-credit">
						{#if provider === 'podcastindex'}
							{t('search.poweredByIndex')} <a href="https://podcastindex.org" target="_blank" rel="noopener noreferrer">Podcast Index</a>
						{:else}
							{t('search.resultsViaApple')}
						{/if}
					</span>
				{/if}
			</div>
		{/if}

		{#if isSearching && searchResults.length === 0}
			<div class="results-grid">
				{#each Array(8) as _}
					<div class="result-card skeleton-result">
						<div class="sk-art"><Skeleton width="100%" height="100%" radius="8px" /></div>
						<Skeleton width="80%" height="1rem" />
						<Skeleton width="50%" height="0.8rem" />
						<Skeleton width="100%" height="2.1rem" radius="6px" />
					</div>
				{/each}
			</div>
		{:else if visibleResults.length === 0}
			<div class="empty-state">
				<img class="empty-illustration" src="/illustrations/empty-search.webp" width="176" height="176" loading="lazy" decoding="async" alt="" />
				<p>{lastExecutedQuery ? t('search.noResults', { query: lastExecutedQuery }) : t('search.startHint')}</p>
			</div>
		{:else}
			<div class="results-grid">
				{#each visibleResults as pod, i (pod.id ?? i)}
					<article class="result-card" use:reveal={{ delay: Math.min(i * 35, 300) }}>
						<button class="art-hit" onclick={() => openPodcastShow(pod)} aria-label={t('discover.openPodcast', { title: pod.title || pod.trackName })} title={pod.title || pod.trackName}>
							<img src={optimizeArtwork(pod.artwork_url || pod.artworkUrl600, 220)} alt="" class="artwork" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						</button>
						<div class="info">
							<h4><button onclick={() => openPodcastShow(pod)} title={pod.title || pod.trackName}>{pod.title || pod.trackName}</button></h4>
							<p class="author">{pod.author || pod.artistName}</p>

							<button
								class="btn-sub"
								class:subscribed={isSubscribed(pod)}
								disabled={isSubscribed(pod)}
								onclick={(e) => handleAddPodcast(e, pod)}
							>
								{#if isSubscribed(pod)}
									<i class="ph ph-check" aria-hidden="true"></i> {t('common.subscribed')}
								{:else}
									<i class="ph ph-plus" aria-hidden="true"></i> {t('common.subscribe')}
								{/if}
							</button>
						</div>
					</article>
				{/each}
			</div>
		{/if}
	</section>
</div>

<style>
	.search-experience {
		display: flex;
		flex-direction: column;
		gap: 16px;
		padding: 22px;
	}

	.search-hero {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.search-hero h1 {
		font-size: clamp(1.8rem, 3vw, 2.4rem);
		font-weight: 800;
		line-height: 1.08;
		letter-spacing: -0.02em;
		margin: 0 0 4px;
	}

	/* Prominent single search field with a soft focus glow. */
	.search-field {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-surface);
		border: 1.5px solid var(--border-subtle);
		min-height: 56px;
		border-radius: 8px;
		padding: 0.35rem 0.4rem 0.35rem 0.9rem;
		box-shadow: var(--shadow-sm);
		transition: border-color 0.2s ease, box-shadow 0.25s ease, transform 0.2s ease;
	}
	.search-field:focus-within {
		border-color: var(--focus-ring);
		box-shadow: 0 0 0 4px color-mix(in srgb, var(--focus-ring) 20%, transparent), var(--shadow-md);
	}
	.search-field .lead { font-size: 1.25rem; color: var(--text-muted); flex-shrink: 0; }
	.search-field input {
		flex: 1;
		border: none;
		background: none;
		outline: none;
		color: var(--text-primary);
		font-size: 1rem;
		padding: 0.5rem 0;
		min-width: 0;
	}
	.search-field input::placeholder { color: var(--text-muted); }

	.clear {
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
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
		color: var(--accent-button-text);
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
		width: 36px;
		height: 36px;
		border-radius: 50%;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
	}
	.recent-clear:hover { background: var(--bg-elevated); color: var(--text-primary); }

	.rss-toggle {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		min-height: 40px;
		margin-left: auto;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		color: var(--text-muted);
		font-size: 0.9rem;
		font-weight: 600;
		padding: 0.4rem 0.85rem;
		border-radius: 20px;
	}
	.rss-toggle:hover { color: var(--accent-green); border-color: var(--accent-green); }
	.rss-toggle .chev { transition: transform 0.28s var(--ease-spring, ease); font-size: 0.95rem; }
	.rss-toggle.open .chev { transform: rotate(180deg); }
	.rss-toggle.open { color: var(--accent-green); }

	.rss-field {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
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
		margin-bottom: 12px;
	}
	.results-section h2 {
		font-size: 1.2rem;
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

	.empty-state {
		min-height: 250px;
		text-align: left;
		padding: 28px clamp(24px, 5vw, 56px);
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		display: flex;
		flex-direction: row;
		justify-content: center;
		align-items: center;
		gap: clamp(24px, 5vw, 64px);
		color: var(--text-muted);
	}
	.empty-state p { max-width: 42ch; font-size: 1rem; line-height: 1.55; }
	.empty-illustration { width: min(176px, 30vw); height: auto; aspect-ratio: 1; object-fit: contain; flex: 0 0 auto; }

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
	.art-hit, .info h4 button { display: block; width: 100%; padding: 0; border: 0; background: transparent; color: inherit; text-align: left; }
	.info h4 button { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font: inherit; }

	.result-card:hover {
		transform: translateY(-4px);
		border-color: var(--accent-green);
	}

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
		color: var(--accent-button-text);
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

	/* Search filters (language + genre) */
	.filter-row {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		flex-wrap: wrap;
		margin: 0;
	}

	.filter-toggle,
	.filter-clear,
	.filter-reset {
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

	.filter-toggle:hover,
	.filter-clear:hover,
	.filter-reset:hover {
		border-color: var(--accent-green);
		color: var(--text-primary);
		background: color-mix(in srgb, var(--accent-green) 8%, var(--bg-surface));
	}

	.filter-toggle.has-active {
		border-color: var(--accent-green);
		color: var(--text-primary);
	}

	.filter-count {
		display: inline-grid;
		place-items: center;
		min-width: 1.25rem;
		height: 1.25rem;
		padding: 0 0.35rem;
		border-radius: 999px;
		background: var(--accent-green);
		color: var(--accent-button-text);
		font-size: 0.72rem;
		font-weight: 700;
	}

	.filter-toggle .chev {
		transition: transform 0.2s ease;
	}

	.filter-toggle.open .chev {
		transform: rotate(180deg);
	}

	.filter-panel {
		display: flex;
		flex-direction: column;
		gap: 1.1rem;
		padding: 1rem 1.15rem;
		margin-bottom: 0.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 16px;
	}

	.filter-group {
		display: flex;
		flex-direction: column;
		gap: 0.55rem;
	}

	.filter-label {
		font-size: 0.88rem;
		font-weight: 700;
		color: var(--text-secondary);
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.filter-pills {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		flex-wrap: wrap;
	}

	.filter-pill {
		display: inline-flex;
		align-items: center;
		gap: 0.45rem;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		padding: 0.4rem 0.85rem;
		border-radius: 20px;
		font-size: 0.88rem;
		font-weight: 600;
		color: var(--text-secondary);
		cursor: pointer;
		transition: var(--transition-smooth);
	}

	.filter-pill:hover {
		border-color: var(--accent-green);
		color: var(--text-primary);
		background: color-mix(in srgb, var(--accent-green) 8%, var(--bg-surface));
	}

	.filter-pill.active {
		background: var(--accent-green);
		color: var(--accent-button-text);
		border-color: var(--accent-green);
	}

	.filter-hint {
		margin: 0;
		font-size: 0.8rem;
		color: var(--text-secondary);
	}

	.flag-emoji {
		font-family: var(--font-sans);
		font-size: 1.1rem;
		line-height: 1;
	}

	@media (max-width: 700px) {
		.search-experience { padding: 16px; }
		.search-field { min-height: 52px; }
		.rss-toggle { margin-left: 0; }
		.empty-state { min-height: 0; flex-direction: column; text-align: center; padding: 24px 18px; }
		.empty-illustration { width: 144px; }
	}
</style>
