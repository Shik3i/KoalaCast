<script lang="ts">
	import { saveLocalSubscription } from '$lib/idb/db';

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

			alert(`Subscribed to "${data.title}" successfully!`);
			rssUrlInput = '';
		} catch (err: any) {
			errorMessage = 'Network error attempting to add RSS feed.';
		} finally {
			isAddingRss = false;
		}
	}
</script>

<div class="search-page">
	<h2>Podcast Discovery</h2>

	<!-- Direct RSS Addition Form -->
	<section class="card">
		<h3>Add Podcast by Direct RSS URL</h3>
		<p class="subtitle">Enter any valid RSS or Atom podcast feed URL.</p>
		<form onsubmit={handleAddDirectRss} class="form-row">
			<input
				type="url"
				bind:value={rssUrlInput}
				placeholder="https://example.com/podcast/feed.xml"
				required
			/>
			<button type="submit" disabled={isAddingRss}>
				{isAddingRss ? 'Adding Feed...' : 'Subscribe'}
			</button>
		</form>
	</section>

	<!-- Catalog Search Form -->
	<section class="card">
		<h3>Search Catalog (Podcast Index)</h3>
		<form onsubmit={handleSearch} class="form-row">
			<input
				type="text"
				bind:value={searchQuery}
				placeholder="Search podcast title, author, or keyword..."
				required
			/>
			<button type="submit" disabled={isSearching}>
				{isSearching ? 'Searching...' : 'Search'}
			</button>
		</form>

		{#if searchNotice}
			<div class="notice">{searchNotice}</div>
		{/if}

		{#if errorMessage}
			<div class="error-banner">{errorMessage}</div>
		{/if}

		<div class="results-grid">
			{#each searchResults as result}
				<div class="result-card">
					<img src={result.artwork || '/favicon.png'} alt={result.title} class="artwork" />
					<div class="info">
						<h4>{result.title}</h4>
						<span class="author">{result.author}</span>
						<p class="desc">{result.description}</p>
					</div>
				</div>
			{/each}
		</div>
	</section>
</div>

<style>
	.search-page {
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.card {
		background-color: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.subtitle {
		color: var(--text-secondary);
		margin-bottom: 1rem;

		font-size: 0.9rem;
	}

	.form-row {
		display: flex;
		gap: 1rem;
	}

	.form-row input {
		flex: 1;
		padding: 0.65rem 1rem;
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		background: var(--bg-primary);
		color: var(--text-primary);
	}

	.form-row button {
		background-color: var(--accent-green);
		color: white;
		border: none;
		padding: 0.65rem 1.5rem;
		border-radius: 6px;
		font-weight: 600;
	}

	.notice {
		margin-top: 1rem;
		padding: 0.75rem;
		background-color: var(--bg-elevated);
		border-radius: 6px;
		color: var(--text-secondary);
	}

	.error-banner {
		margin-top: 1rem;
		padding: 0.75rem;
		background-color: #f8d7da;
		color: #721c24;
		border-radius: 6px;
	}

	.results-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
		gap: 1rem;
		margin-top: 1.5rem;
	}

	.result-card {
		display: flex;
		gap: 1rem;
		padding: 1rem;
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
	}

	.artwork {
		width: 64px;
		height: 64px;
		border-radius: 6px;
		object-fit: cover;
	}

	.info h4 {
		font-size: 1rem;
		font-weight: 600;
	}

	.author {
		font-size: 0.85rem;
		color: var(--text-secondary);
	}

	.desc {
		font-size: 0.8rem;
		color: var(--text-muted);
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
		margin-top: 0.25rem;
	}
</style>
