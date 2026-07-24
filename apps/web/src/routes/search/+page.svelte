<script lang="ts">
	import { saveLocalSubscription } from '$lib/idb/db';
	import { FEATURED_PODCASTS, type FeaturedPodcast } from '$lib/data/featured';

	let searchQuery = $state('');
	let rssUrlInput = $state('');
	let isSearching = $state(false);
	let isAddingRss = $state(false);
	let searchNotice = $state('');
	let errorMessage = $state('');
	let searchResults = $state<any[]>([]);

	async function handleSearch(e: Event) {
		e.preventDefault();
		if (!searchQuery.trim()) return;

		isSearching = true;
		errorMessage = '';
		searchNotice = '';

		try {
			const res = await fetch(`/api/v1/podcasts/search?q=${encodeURIComponent(searchQuery)}`);
			const data = await res.json();

			if (!data.search_available) {
				searchNotice = data.message || 'Podcast Index search is unconfigured.';
				searchResults = [];
			} else {
				searchResults = data.results || [];
			}
		} catch (err: any) {
			errorMessage = 'Failed to execute search query.';
		} finally {
			isSearching = false;
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

			// Save locally
			await saveLocalSubscription({
				podcast_id: data.id,
				feed_url: data.feed_url,
				title: data.title,
				artwork_url: data.artwork_url,
				added_at: Date.now()
			});

			alert(`Added "${data.title}" to library!`);
			rssUrlInput = '';
		} catch (err: any) {
			errorMessage = 'Failed to fetch RSS feed.';
		} finally {
			isAddingRss = false;
		}
	}

	async function handleAddFeatured(pod: FeaturedPodcast) {
		isAddingRss = true;
		try {
			const res = await fetch('/api/v1/podcasts/feed', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ feed_url: pod.feed_url })
			});

			const data = await res.json();
			await saveLocalSubscription({
				podcast_id: data.id || pod.id,
				feed_url: pod.feed_url,
				title: pod.title,
				artwork_url: pod.artwork_url,
				added_at: Date.now()
			});

			alert(`Added "${pod.title}" to library!`);
		} catch (err) {
			console.error(err);
		} finally {
			isAddingRss = false;
		}
	}
</script>

<div class="search-page">
	<h2>Add & Discover Podcasts</h2>

	<div class="input-sections">
		<!-- Add Direct RSS -->
		<form onsubmit={handleAddDirectRss} class="rss-form">
			<h3>Add Podcast by RSS Feed URL</h3>
			<div class="input-group">
				<input
					type="url"
					placeholder="https://example.com/feed.xml"
					bind:value={rssUrlInput}
					required
				/>
				<button type="submit" disabled={isAddingRss}>
					{isAddingRss ? 'Fetching Feed...' : 'Add RSS Feed'}
				</button>
			</div>
		</form>

		<!-- Search Catalog -->
		<form onsubmit={handleSearch} class="search-form">
			<h3>Search Podcast Index Catalog</h3>
			<div class="input-group">
				<input
					type="text"
					placeholder="Search by title, topic, or host..."
					bind:value={searchQuery}
					required
				/>
				<button type="submit" disabled={isSearching}>
					{isSearching ? 'Searching...' : 'Search'}
				</button>
			</div>
		</form>
	</div>

	{#if errorMessage}
		<div class="alert error">{errorMessage}</div>
	{/if}

	{#if searchNotice}
		<div class="alert info">
			{searchNotice} Direct RSS feed URL insertion is always available above.
		</div>
	{/if}

	<!-- Search Results or Featured Recommendations -->
	{#if searchResults.length > 0}
		<section class="results-section">
			<h3>Search Results ({searchResults.length})</h3>
			<div class="results-grid">
				{#each searchResults as pod}
					<div class="result-card">
						<img src={pod.artwork_url || '/favicon.png'} alt={pod.title} class="artwork" />
						<div class="info">
							<h4>{pod.title}</h4>
							<p class="author">{pod.author}</p>
							<a href="/podcast/{pod.id}" class="btn-view">View Show</a>
						</div>
					</div>
				{/each}
			</div>
		</section>
	{:else}
		<section class="results-section">
			<h3>Featured Popular Shows</h3>
			<div class="results-grid">
				{#each FEATURED_PODCASTS as pod}
					<div class="result-card">
						<img src={pod.artwork_url} alt={pod.title} class="artwork" />
						<div class="info">
							<h4>{pod.title}</h4>
							<p class="author">{pod.author}</p>
							<button class="btn-view" onclick={() => handleAddFeatured(pod)}>Add to Library</button>
						</div>
					</div>
				{/each}
			</div>
		</section>
	{/if}
</div>

<style>
	.search-page {
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	h2 {
		font-size: 1.8rem;
		font-weight: 700;
	}

	.input-sections {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
		gap: 1.5rem;
	}

	.rss-form, .search-form {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 10px;
		padding: 1.5rem;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.rss-form h3, .search-form h3 {
		font-size: 1.1rem;
		font-weight: 600;
	}

	.input-group {
		display: flex;
		gap: 0.5rem;
	}

	input {
		flex: 1;
		padding: 0.6rem 0.8rem;
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		background: var(--bg-primary);
		color: var(--text-primary);
		font-size: 0.95rem;
	}

	button[type="submit"] {
		padding: 0.6rem 1.2rem;
		background: var(--accent-green);
		color: white;
		border: none;
		border-radius: 6px;
		font-weight: 600;
	}

	.alert {
		padding: 1rem;
		border-radius: 6px;
		font-size: 0.95rem;
	}

	.alert.error {
		background: #ffdddd;
		color: #900;
	}

	.alert.info {
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		color: var(--text-secondary);
	}

	.results-section h3 {
		font-size: 1.3rem;
		margin-bottom: 1rem;
	}

	.results-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
		gap: 1.25rem;
	}

	.result-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.artwork {
		width: 100%;
		aspect-ratio: 1;
		object-fit: cover;
		border-radius: 6px;
	}

	.info h4 {
		font-size: 1rem;
		font-weight: 600;
		line-height: 1.3;
	}

	.author {
		font-size: 0.85rem;
		color: var(--text-secondary);
		margin-top: 0.2rem;
		margin-bottom: 0.75rem;
	}

	.btn-view {
		display: inline-block;
		text-align: center;
		padding: 0.4rem 0.8rem;
		background: var(--accent-green);
		color: white;
		border-radius: 4px;
		font-size: 0.85rem;
		font-weight: 600;
		width: 100%;
		border: none;
	}
</style>
