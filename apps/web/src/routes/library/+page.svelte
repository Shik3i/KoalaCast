<script lang="ts">
	import { onMount } from 'svelte';
	import {
		getLocalSubscriptions,
		removeLocalSubscription,
		getRecentPlaybackStates,
		getLocalQueue,
		removeFromLocalQueue,
		clearLocalQueue,
		type LocalSubscription,
		type LocalPlaybackState,
		type LocalQueueItem
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { goto } from '$app/navigation';

	let subscriptions = $state<LocalSubscription[]>([]);
	let recentEpisodes = $state<LocalPlaybackState[]>([]);
	let queue = $state<LocalQueueItem[]>([]);
	let activeTab = $state<'subscriptions' | 'episodes' | 'queue' | 'favorites'>('subscriptions');

	onMount(async () => {
		subscriptions = await getLocalSubscriptions();
		recentEpisodes = await getRecentPlaybackStates(30);
		queue = await getLocalQueue();
	});

	function playQueueItem(item: LocalQueueItem) {
		player.play({
			episode_id: item.episode_id,
			podcast_id: item.podcast_id,
			title: item.title || 'Episode',
			podcast_title: '',
			artwork_url: item.artwork_url || '',
			enclosure_url: item.enclosure_url,
			duration_ms: item.duration_ms || 0
		});
	}

	async function removeQueueItem(id: string) {
		await removeFromLocalQueue(id);
		queue = queue.filter((q) => q.id !== id);
	}

	async function emptyQueue() {
		if (!queue.length) return;
		if (confirm('Clear the entire queue?')) {
			await clearLocalQueue();
			queue = [];
			toast.success('Queue cleared.');
		}
	}

	async function handleUnsubscribe(id: string) {
		if (confirm('Unsubscribe from this podcast?')) {
			await removeLocalSubscription(id);
			subscriptions = subscriptions.filter((s) => s.podcast_id !== id);
			toast.success('Unsubscribed.');
		}
	}

	function resume(item: LocalPlaybackState) {
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
</script>

<div class="library-page">
	<h2>Library</h2>

	<div class="tabs">
		<button class:active={activeTab === 'subscriptions'} onclick={() => (activeTab = 'subscriptions')}>
			Subscriptions ({subscriptions.length})
		</button>
		<button class:active={activeTab === 'episodes'} onclick={() => (activeTab = 'episodes')}>
			In Progress ({recentEpisodes.length})
		</button>
		<button class:active={activeTab === 'queue'} onclick={() => (activeTab = 'queue')}>
			Queue ({queue.length})
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
	{:else if activeTab === 'episodes'}
		{#if recentEpisodes.length === 0}
			<div class="empty-state">
				<p>Nothing in progress yet. Start an episode and it'll show up here.</p>
				<a href="/search" class="btn">Discover Podcasts</a>
			</div>
		{:else}
			<div class="episode-list">
				{#each recentEpisodes as ep (ep.episode_id)}
					<div class="ep-row">
						<button class="ep-play" onclick={() => resume(ep)} aria-label="Resume episode">
							<img src={ep.artwork_url || '/placeholder.svg'} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
							<span class="ep-play-icon"><i class="ph-fill ph-play" aria-hidden="true"></i></span>
						</button>
						<div class="ep-body">
							<a class="ep-title" href={`/episode/${ep.episode_id}`}>{ep.title || 'Episode'}</a>
							<span class="ep-sub">{ep.podcast_title || ''}</span>
							<span class="ep-bar" aria-hidden="true">
								<span class="ep-bar-fill" style="width:{Math.round(ep.progress_percent)}%"></span>
							</span>
						</div>
						<span class="ep-pct">{Math.round(ep.progress_percent)}%</span>
					</div>
				{/each}
			</div>
		{/if}
	{:else if activeTab === 'queue'}
		{#if queue.length === 0}
			<div class="empty-state">
				<p>Your queue is empty. Add episodes from any show to line them up.</p>
				<a href="/search" class="btn">Discover Podcasts</a>
			</div>
		{:else}
			<div class="queue-head">
				<button class="btn-clear" onclick={emptyQueue}>
					<i class="ph ph-trash" aria-hidden="true"></i> Clear queue
				</button>
			</div>
			<div class="episode-list">
				{#each queue as item (item.id)}
					<div class="ep-row">
						<button class="ep-play" onclick={() => playQueueItem(item)} aria-label="Play episode">
							<img src={item.artwork_url || '/placeholder.svg'} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
							<span class="ep-play-icon"><i class="ph-fill ph-play" aria-hidden="true"></i></span>
						</button>
						<div class="ep-body">
							<a class="ep-title" href={`/episode/${item.episode_id}`}>{item.title || 'Episode'}</a>
							<span class="ep-sub">Queued</span>
						</div>
						<button class="ep-remove" onclick={() => removeQueueItem(item.id)} aria-label="Remove from queue">
							<i class="ph ph-x" aria-hidden="true"></i>
						</button>
					</div>
				{/each}
			</div>
		{/if}
	{:else}
		<div class="empty-state">
			<p>Favorites are coming soon.</p>
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
		color: var(--color-danger);
	}

	.btn {
		display: inline-block;
		margin-top: 1rem;
		background: var(--accent-green);
		color: #fff;
		padding: 0.6rem 1.4rem;
		border-radius: 10px;
		font-weight: 700;
	}
	.btn:hover { text-decoration: none; background: var(--accent-green-hover); }

	/* In-progress episode list */
	.episode-list { display: flex; flex-direction: column; gap: 0.75rem; }
	.ep-row {
		display: flex;
		align-items: center;
		gap: 1rem;
		padding: 0.75rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		transition: border-color 0.2s ease, transform 0.2s ease;
	}
	.ep-row:hover { border-color: var(--accent-green); transform: translateX(3px); }

	.ep-play {
		position: relative;
		width: 56px;
		height: 56px;
		flex-shrink: 0;
		border: none;
		padding: 0;
		border-radius: 10px;
		overflow: hidden;
		line-height: 0;
	}
	.ep-play img { width: 100%; height: 100%; object-fit: cover; }
	.ep-play-icon {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgba(0, 0, 0, 0.4);
		color: #fff;
		font-size: 1.4rem;
		opacity: 0;
		transition: opacity 0.2s ease;
	}
	.ep-play:hover .ep-play-icon { opacity: 1; }

	.ep-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 0.3rem; }
	.ep-title {
		font-weight: 700;
		color: var(--text-primary);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.ep-title:hover { color: var(--accent-green); text-decoration: none; }
	.ep-sub {
		font-size: 0.82rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.ep-bar { height: 4px; border-radius: 999px; background: var(--bg-elevated); overflow: hidden; }
	.ep-bar-fill { display: block; height: 100%; background: linear-gradient(90deg, var(--accent-green), var(--accent-green-hover)); }
	.ep-pct { font-size: 0.78rem; font-weight: 700; color: var(--text-muted); flex-shrink: 0; }

	.queue-head { display: flex; justify-content: flex-end; margin-bottom: 1rem; }
	.btn-clear {
		background: var(--bg-elevated);
		color: var(--text-secondary);
		border: 1px solid var(--border-subtle);
		padding: 0.45rem 0.9rem;
		border-radius: 8px;
		font-weight: 600;
		font-size: 0.85rem;
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
	}
	.btn-clear:hover { color: var(--color-danger); border-color: var(--color-danger-border); }

	.ep-remove {
		flex-shrink: 0;
		width: 36px;
		height: 36px;
		border-radius: 50%;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		font-size: 1.1rem;
	}
	.ep-remove:hover { background: var(--bg-elevated); color: var(--color-danger); }

	@media (max-width: 640px) {
		.ep-pct { display: none; }
	}
</style>
