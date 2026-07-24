<script lang="ts">
	import { onMount } from 'svelte';
	import { getLocalSubscriptions, removeLocalSubscription, type LocalSubscription } from '$lib/idb/db';

	let subscriptions = $state<LocalSubscription[]>([]);
	let activeTab = $state<'subscriptions' | 'episodes' | 'favorites'>('subscriptions');

	onMount(async () => {
		subscriptions = await getLocalSubscriptions();
	});

	async function handleUnsubscribe(id: string) {
		if (confirm('Unsubscribe from this podcast?')) {
			await removeLocalSubscription(id);
			subscriptions = subscriptions.filter((s) => s.podcast_id !== id);
		}
	}
</script>

<div class="library-page">
	<h2>Library</h2>

	<div class="tabs">
		<button class:active={activeTab === 'subscriptions'} onclick={() => (activeTab = 'subscriptions')}>
			Subscriptions ({subscriptions.length})
		</button>
		<button class:active={activeTab === 'episodes'} onclick={() => (activeTab = 'episodes')}>
			Episodes
		</button>
		<button class:active={activeTab === 'favorites'} onclick={() => (activeTab = 'favorites')}>
			Favorites
		</button>
	</div>

	{#if activeTab === 'subscriptions'}
		{#if subscriptions.length === 0}
			<div class="empty-state">
				<p>You haven't subscribed to any podcasts yet.</p>
				<a href="/search" class="btn">Discover Podcasts</a>
			</div>
		{:else}
			<div class="podcast-grid">
				{#each subscriptions as sub}
					<div class="podcast-card">
						<img src={sub.artwork_url || '/placeholder.svg'} alt={sub.title} class="artwork" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
						<div class="details">
							<h3>{sub.title}</h3>
							<div class="actions">
								<a href={`/podcast/${sub.podcast_id}`} class="btn-sm">View Episodes</a>
								<button class="btn-sm text-danger" onclick={() => handleUnsubscribe(sub.podcast_id)}>Unsubscribe</button>
							</div>
						</div>
					</div>
				{/each}
			</div>
		{/if}
	{:else}
		<div class="empty-state">
			<p>No episodes found in this section.</p>
		</div>
	{/if}
</div>

<style>
	.library-page {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.tabs {
		display: flex;
		gap: 1rem;
		border-bottom: 1px solid var(--border-subtle);
		padding-bottom: 0.5rem;
	}

	.tabs button {
		background: none;
		border: none;
		font-weight: 600;
		font-size: 1rem;
		color: var(--text-secondary);
		padding: 0.5rem 1rem;
		border-bottom: 2px solid transparent;
	}

	.tabs button.active {
		color: var(--accent-green);
		border-bottom-color: var(--accent-green);
	}

	.empty-state {
		text-align: center;
		padding: 3rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
	}

	.podcast-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
		gap: 1.5rem;
	}

	.podcast-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		overflow: hidden;
		display: flex;
		flex-direction: column;
	}

	.artwork {
		width: 100%;
		aspect-ratio: 1;
		object-fit: cover;
	}

	.details {
		padding: 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.details h3 {
		font-size: 1.1rem;
		font-weight: 600;
	}

	.actions {
		display: flex;
		justify-content: space-between;
		align-items: center;
	}

	.btn-sm {
		font-size: 0.85rem;
		padding: 0.35rem 0.75rem;
		border-radius: 4px;
		border: 1px solid var(--border-subtle);
		background: var(--bg-primary);
		color: var(--text-primary);
	}

	.text-danger {
		color: #d90429;
	}
</style>
