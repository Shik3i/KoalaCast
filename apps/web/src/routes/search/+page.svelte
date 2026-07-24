<script lang="ts">
	import { onMount } from 'svelte';
	import { saveLocalSubscription, getLocalSubscriptions } from '$lib/idb/db';

	let searchQuery = $state('');
	let rssUrlInput = $state('');
	let isSearching = $state(false);
	let isAddingRss = $state(false);
	let errorMessage = $state('');
	let searchResults = $state<any[]>([]);
	let subscribedIds = $state<string[]>([]);

	onMount(async () => {
		const subs = await getLocalSubscriptions();
		subscribedIds = subs.map((s) => s.podcast_id);
		// Pre-populate with default search if empty
		executeSearch('technology');
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
		} catch (err) {
			errorMessage = 'Failed to execute search query.';
		} finally {
			isSearching = false;
		}
	}

	function handleSearchSubmit(e: Event) {
		e.preventDefault();
		executeSearch(searchQuery);
	}

	async function handleAddPodcast(pod: any) {
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
		} catch (err) {
			console.error(err);
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
			rssUrlInput = '';
		} catch (err) {
			errorMessage = 'Failed to fetch RSS feed.';
		} finally {
			isAddingRss = false;
		}
	}
</script>

<div class="search-experience">
	<div class="header-row">
		<h2>Search & Add Podcasts</h2>
	</div>

	<div class="search-grid-top">
		<!-- Search Form -->
		<form onsubmit={handleSearchSubmit} class="search-box-card">
			<h3>Search Millions of Shows</h3>
			<div class="input-row">
				<input
					type="text"
					placeholder="Search by keyword, title, or host..."
					bind:value={searchQuery}
					required
				/>
				<button type="submit" disabled={isSearching}>
					{isSearching ? 'Searching...' : 'Search'}
				</button>
			</div>
		</form>

		<!-- Direct RSS Form -->
		<form onsubmit={handleAddDirectRss} class="rss-box-card">
			<h3>Add Podcast by Direct RSS URL</h3>
			<div class="input-row">
				<input
					type="url"
					placeholder="https://example.com/feed.xml"
					bind:value={rssUrlInput}
					required
				/>
				<button type="submit" disabled={isAddingRss}>
					{isAddingRss ? 'Adding...' : 'Add RSS'}
				</button>
			</div>
		</form>
	</div>

	{#if errorMessage}
		<div class="alert error">{errorMessage}</div>
	{/if}

	<!-- Results Grid -->
	<section class="results-section">
		<h3>
			{#if isSearching}
				Searching podcast catalog...
			{:else}
				Results ({searchResults.length})
			{/if}
		</h3>

		<div class="results-grid">
			{#each searchResults as pod}
				<div class="result-card">
					<img src={pod.artwork_url || pod.artworkUrl600 || '/favicon.png'} alt={pod.title || pod.trackName} class="artwork" />
					<div class="info">
						<h4>{pod.title || pod.trackName}</h4>
						<p class="author">{pod.author || pod.artistName}</p>

						<button
							class="btn-sub"
							class:subscribed={subscribedIds.includes(pod.id)}
							onclick={() => handleAddPodcast(pod)}
						>
							{#if subscribedIds.includes(pod.id)}
								<i class="ph ph-check" aria-hidden="true"></i> Subscribed
							{:else}
								<i class="ph ph-plus" aria-hidden="true"></i> Subscribe
							{/if}
						</button>
					</div>
				</div>
			{/each}
		</div>
	</section>
</div>

<style>
	.search-experience {
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	h2 {
		font-size: 2rem;
		font-weight: 800;
	}

	.search-grid-top {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
		gap: 1.5rem;
	}

	.search-box-card, .rss-box-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		padding: 1.5rem;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.search-box-card h3, .rss-box-card h3 {
		font-size: 1.1rem;
		font-weight: 700;
	}

	.input-row {
		display: flex;
		gap: 0.5rem;
	}

	input {
		flex: 1;
		padding: 0.75rem 1rem;
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		background: var(--bg-primary);
		color: var(--text-primary);
		font-size: 0.95rem;
		outline: none;
	}

	input:focus {
		border-color: var(--focus-ring);
	}

	button[type="submit"] {
		padding: 0.75rem 1.25rem;
		background: var(--accent-green);
		color: white;
		border: none;
		border-radius: 8px;
		font-weight: 700;
	}

	.alert.error {
		padding: 1rem;
		background: #ffdddd;
		color: #900;
		border-radius: 8px;
	}

	.results-section h3 {
		font-size: 1.4rem;
		margin-bottom: 1.25rem;
		font-weight: 700;
	}

	.results-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
		gap: 1.5rem;
	}

	.result-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		padding: 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
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
