<script lang="ts">
	import { t } from '$lib/i18n';
	import { confirmDialog } from '$lib/stores/confirm.svelte';
	import { onMount } from 'svelte';
	import {
		getLocalSubscriptions,
		removeLocalSubscription,
		getRecentPlaybackStates,
		reorderLocalQueue,
		getLocalFavorites,
		removeLocalFavorite,
		type LocalSubscription,
		type LocalPlaybackState,
		type LocalFavorite
	} from '$lib/idb/db';
	import { player, type CurrentTrack } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { goto } from '$app/navigation';
	import { reveal } from '$lib/actions/reveal';
	import { optimizeArtwork, SUBSCRIPTION_ARTWORK_SIZE } from '$lib/artwork';
	import { podcastHref } from '$lib/podcast-link';
	import EpisodeProgressButton from '$lib/components/EpisodeProgressButton.svelte';

	let subscriptions = $state<LocalSubscription[]>([]);
	let recentEpisodes = $state<LocalPlaybackState[]>([]);
	let queue = $state<CurrentTrack[]>([]);
	let favorites = $state<LocalFavorite[]>([]);
	let activeTab = $state<'subscriptions' | 'episodes' | 'queue' | 'favorites'>('subscriptions');
	let dragIndex = $state<number | null>(null);
	let libraryQuery = $state('');
	let librarySort = $state<'recent' | 'az'>('recent');
	let activeCover = $state<string | null>(null);
	let longPressTimer: number | null = null;

	const visibleSubscriptions = $derived.by(() => {
		let list = subscriptions.filter((subscription) =>
			subscription.title.toLowerCase().includes(libraryQuery.trim().toLowerCase())
		);
		if (librarySort === 'az') list = [...list].sort((a, b) => a.title.localeCompare(b.title));
		return list;
	});

	onMount(async () => {
		const requestedView = new URLSearchParams(window.location.search).get('view');
		if (requestedView === 'subscriptions' || requestedView === 'episodes' || requestedView === 'queue' || requestedView === 'favorites') {
			activeTab = requestedView;
		}
		subscriptions = await getLocalSubscriptions();
		recentEpisodes = await getRecentPlaybackStates(30);
		await player.loadQueue();
		favorites = await getLocalFavorites();
	});

	// Mirror the store's queue into the local list so the tab stays correct when the
	// queue changes elsewhere (e.g. autoplay advancing to the next episode) — except
	// mid-drag, where the local list is the source of truth until it's persisted.
	$effect(() => {
		const storeQueue = player.queue;
		if (dragIndex === null) queue = [...storeQueue];
	});

	function playFavorite(fav: LocalFavorite) {
		if (!fav.enclosure_url) {
			goto(`/episode/${fav.episode_id}`);
			return;
		}
		player.play({
			episode_id: fav.episode_id,
			podcast_id: fav.podcast_id || '',
			title: fav.title || t('common.episode'),
			podcast_title: fav.podcast_title || '',
			artwork_url: fav.artwork_url || '',
			enclosure_url: fav.enclosure_url,
			duration_ms: fav.duration_ms || 0,
			categories: fav.categories
		});
	}

	async function unfavorite(episode_id: string) {
		await removeLocalFavorite(episode_id);
		favorites = favorites.filter((f) => f.episode_id !== episode_id);
	}

	async function playQueueItem(item: CurrentTrack) {
		await player.playFromQueue(item);
		queue = [...player.queue];
	}

	async function removeQueueItem(episode_id: string) {
		await player.removeFromQueue(episode_id);
		queue = [...player.queue];
	}

	async function emptyQueue() {
		if (!queue.length) return;
		if (await confirmDialog.ask(t('library.confirmClearQueue'))) {
			await player.clearQueue();
			queue = [];
			toast.success(t('toast.queueCleared'));
		}
	}

	// Drag-to-reorder.
	function onDragStart(i: number) {
		dragIndex = i;
	}
	function onDragOver(e: DragEvent, i: number) {
		e.preventDefault();
		if (dragIndex === null || dragIndex === i) return;
		const next = [...queue];
		const [moved] = next.splice(dragIndex, 1);
		next.splice(i, 0, moved);
		queue = next;
		dragIndex = i;
	}
	async function onDrop() {
		if (dragIndex === null) return; // dragend + drop both fire; run once
		// Capture the dragged order before clearing dragIndex (which re-enables the
		// store→local sync effect), so the persisted order is the intended one.
		const orderedIds = queue.map((q) => q.episode_id);
		dragIndex = null;
		await reorderLocalQueue(orderedIds);
		await player.loadQueue();
	}

	// Touch/keyboard-friendly reordering (HTML5 drag-and-drop doesn't work on touch).
	async function moveItem(i: number, dir: -1 | 1) {
		const j = i + dir;
		if (j < 0 || j >= queue.length) return;
		const next = [...queue];
		[next[i], next[j]] = [next[j], next[i]];
		queue = next;
		await reorderLocalQueue(queue.map((q) => q.episode_id));
		await player.loadQueue();
	}

	async function handleUnsubscribe(id: string) {
		if (await confirmDialog.ask(t('library.confirmUnsubscribe'))) {
			await removeLocalSubscription(id);
			subscriptions = subscriptions.filter((s) => s.podcast_id !== id);
			toast.success(t('toast.unsubscribed'));
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
			title: item.title || t('common.episode'),
			podcast_title: item.podcast_title || '',
			artwork_url: item.artwork_url || '',
			enclosure_url: item.enclosure_url,
			duration_ms: item.duration_ms || 0,
			categories: item.categories
		});
	}

	function progressLabel(label: string, progress = 0) {
		const rounded = Math.round(progress);
		return rounded > 0 ? `${label} · ${rounded}%` : label;
	}

	function longPress(node: HTMLElement, podcastId: string) {
		const start = (event: PointerEvent) => {
			if (event.pointerType !== 'touch') return;
			if (longPressTimer !== null) window.clearTimeout(longPressTimer);
			longPressTimer = window.setTimeout(() => {
				activeCover = podcastId;
				longPressTimer = null;
			}, 450);
		};
		const cancel = () => {
			if (longPressTimer !== null) window.clearTimeout(longPressTimer);
			longPressTimer = null;
		};
		node.addEventListener('pointerdown', start);
		node.addEventListener('pointerup', cancel);
		node.addEventListener('pointercancel', cancel);
		node.addEventListener('pointermove', cancel);
		return {
			destroy() {
				cancel();
				node.removeEventListener('pointerdown', start);
				node.removeEventListener('pointerup', cancel);
				node.removeEventListener('pointercancel', cancel);
				node.removeEventListener('pointermove', cancel);
			}
		};
	}
</script>

<svelte:window onpointerdown={(event) => {
	if (!(event.target as HTMLElement)?.closest?.('.quiet-cover-card')) activeCover = null;
}} />

<div class="library-page">
	<div class="lib-head">
		<div><h1>{t('library.title')}</h1><p class="sub">{t('library.showCount', { count: subscriptions.length })}</p></div>
		{#if activeTab === 'subscriptions' && subscriptions.length > 0}
			<label class="library-filter">
				<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
				<input bind:value={libraryQuery} placeholder={t('library.filterPlaceholder')} aria-label={t('library.filterPlaceholder')} />
			</label>
			<div class="library-sort" role="group" aria-label={t('library.sortLabel')}>
				<button aria-pressed={librarySort === 'recent'} class:active={librarySort === 'recent'} onclick={() => (librarySort = 'recent')}>{t('library.sortRecent')}</button>
				<button aria-pressed={librarySort === 'az'} class:active={librarySort === 'az'} onclick={() => (librarySort = 'az')}>A–Z</button>
			</div>
		{/if}
	</div>

	<div class="tabs collection-tabs" role="tablist" aria-label={t('library.sections')}>
		<button role="tab" aria-selected={activeTab === 'subscriptions'} class:active={activeTab === 'subscriptions'} onclick={() => (activeTab = 'subscriptions')}>
			<i class="ph ph-books" aria-hidden="true"></i> {t('library.subscriptions')} <span class="count">{subscriptions.length}</span>
		</button>
		<button role="tab" aria-selected={activeTab === 'episodes'} class:active={activeTab === 'episodes'} onclick={() => (activeTab = 'episodes')}>
			<i class="ph ph-hourglass-medium" aria-hidden="true"></i> {t('library.inProgress')} <span class="count">{recentEpisodes.length}</span>
		</button>
		<button role="tab" aria-selected={activeTab === 'queue'} class:active={activeTab === 'queue'} onclick={() => (activeTab = 'queue')}>
			<i class="ph ph-list-plus" aria-hidden="true"></i> {t('library.queue')} <span class="count">{queue.length}</span>
		</button>
		<button role="tab" aria-selected={activeTab === 'favorites'} class:active={activeTab === 'favorites'} onclick={() => (activeTab = 'favorites')}>
			<i class="ph ph-heart" aria-hidden="true"></i> {t('library.favorites')} <span class="count">{favorites.length}</span>
		</button>
	</div>

	{#if activeTab === 'subscriptions'}
		{#if subscriptions.length === 0}
			<div class="empty-state">
				<img class="empty-illustration" src="/illustrations/empty-library.webp" width="256" height="256" loading="lazy" decoding="async" alt="" />
				<p>{t('library.emptySubscriptions')}</p>
				<div class="empty-actions">
					<a href="/search" class="btn">{t('common.discoverPodcasts')}</a>
					<a href="/settings#opml" class="btn secondary">{t('settings.opmlTitle')}</a>
				</div>
			</div>
		{:else}
			<div class="podcast-grid">
				{#each visibleSubscriptions as sub, i (sub.podcast_id)}
					<article class="podcast-card quiet-cover-card" class:long-pressed={activeCover === sub.podcast_id} use:reveal={{ delay: Math.min(i * 40, 320) }} use:longPress={sub.podcast_id}>
						<a class="cover-link" href={podcastHref(sub)} aria-label={t('library.openShow', { title: sub.title })} title={sub.title}>
							<img src={optimizeArtwork(sub.artwork_url, SUBSCRIPTION_ARTWORK_SIZE)} alt="" class="artwork" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						</a>
						<div class="details cover-overlay">
							<h3 title={sub.title}>{sub.title}</h3>
							<p>{t('library.subscribedHint')}</p>
							<div class="actions">
								<a href={podcastHref(sub)} class="round-action primary" aria-label={t('common.viewEpisodes')} title={t('common.viewEpisodes')}><i class="ph-fill ph-play" aria-hidden="true"></i></a>
								<button class="round-action" onclick={() => goto(podcastHref(sub))} aria-label={t('library.openShow', { title: sub.title })} title={t('library.openShow', { title: sub.title })}><i class="ph ph-list-plus" aria-hidden="true"></i></button>
								<button class="round-action" onclick={() => handleUnsubscribe(sub.podcast_id)} aria-label={t('common.unsubscribe')} title={t('common.unsubscribe')}><i class="ph ph-dots-three" aria-hidden="true"></i></button>
							</div>
						</div>
					</article>
				{/each}
			</div>
		{/if}
	{:else if activeTab === 'episodes'}
		{#if recentEpisodes.length === 0}
			<div class="empty-state">
				<img class="empty-illustration" src="/illustrations/empty-library.webp" width="256" height="256" loading="lazy" decoding="async" alt="" />
				<p>{t('library.emptyInProgress')}</p>
				<a href="/search" class="btn">{t('common.discoverPodcasts')}</a>
			</div>
		{:else}
			<div class="episode-list">
				{#each recentEpisodes as ep (ep.episode_id)}
					<div class="ep-row">
						<a class="ep-art" href={`/episode/${ep.episode_id}`} aria-label={ep.title || t('common.episode')} title={ep.title || t('common.episode')}>
							<img src={optimizeArtwork(ep.artwork_url, 120)} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						</a>
						<div class="ep-body">
							<a class="ep-title" href={`/episode/${ep.episode_id}`} title={ep.title || t('common.episode')}>{ep.title || t('common.episode')}</a>
							<span class="ep-sub" title={ep.podcast_title || undefined}>{ep.podcast_title || ''}</span>
							<span class="ep-bar" aria-hidden="true">
								<span class="ep-bar-fill" style="width:{Math.round(ep.progress_percent)}%"></span>
							</span>
						</div>
						<span class="ep-pct">{Math.round(ep.progress_percent)}%</span>
						<EpisodeProgressButton
							progress={player.current?.episode_id === ep.episode_id && player.durationMs > 0
								? (player.positionMs / player.durationMs) * 100
								: ep.progress_percent}
							current={player.current?.episode_id === ep.episode_id}
							label={progressLabel(t('library.resumeEpisode'), ep.progress_percent)}
							onclick={() => resume(ep)}
						/>
					</div>
				{/each}
			</div>
		{/if}
	{:else if activeTab === 'queue'}
		{#if queue.length === 0}
			<div class="empty-state">
				<img class="empty-illustration queue" src="/illustrations/empty-queue.webp" width="192" height="288" loading="lazy" decoding="async" alt="" />
				<p>{t('library.emptyQueue')}</p>
				<a href="/search" class="btn">{t('common.discoverPodcasts')}</a>
			</div>
		{:else}
			<div class="queue-head">
				<span class="queue-hint">{t('library.dragToReorder')}</span>
				<button class="btn-clear" onclick={emptyQueue}>
					<i class="ph ph-trash" aria-hidden="true"></i> {t('library.clearQueue')}
				</button>
			</div>
			<div class="episode-list">
				{#each queue as item, i (item.episode_id)}
					<div
						class="ep-row"
						role="listitem"
						class:dragging={dragIndex === i}
						draggable="true"
						ondragstart={() => onDragStart(i)}
						ondragover={(e) => onDragOver(e, i)}
						ondragend={onDrop}
						ondrop={onDrop}
					>
						<div class="reorder-btns">
							<button class="reorder-btn" onclick={() => moveItem(i, -1)} disabled={i === 0} aria-label={t('library.moveUp')} title={t('library.moveUp')}>
								<i class="ph ph-caret-up" aria-hidden="true"></i>
							</button>
							<button class="reorder-btn" onclick={() => moveItem(i, 1)} disabled={i === queue.length - 1} aria-label={t('library.moveDown')} title={t('library.moveDown')}>
								<i class="ph ph-caret-down" aria-hidden="true"></i>
							</button>
						</div>
						<span class="drag-handle" aria-hidden="true" title={t('library.dragToReorder')}><i class="ph ph-dots-six-vertical"></i></span>
						<a class="ep-art" href={`/episode/${item.episode_id}`} aria-label={item.title || t('common.episode')} title={item.title || t('common.episode')}>
							<img src={optimizeArtwork(item.artwork_url, 120)} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						</a>
						<div class="ep-body">
							<a class="ep-title" href={`/episode/${item.episode_id}`} title={item.title || t('common.episode')}>{item.title || t('common.episode')}</a>
							<span class="ep-sub" title={item.podcast_title || t('library.queued')}>{item.podcast_title || t('library.queued')}</span>
						</div>
						<EpisodeProgressButton label={t('library.playEpisode')} onclick={() => playQueueItem(item)} />
						<button class="ep-remove" onclick={() => removeQueueItem(item.episode_id)} aria-label={t('library.removeFromQueue')} title={t('library.removeFromQueue')}>
							<i class="ph ph-x" aria-hidden="true"></i>
						</button>
					</div>
				{/each}
			</div>
		{/if}
	{:else}
		{#if favorites.length === 0}
			<div class="empty-state">
				<img class="empty-illustration" src="/illustrations/empty-library.webp" width="256" height="256" loading="lazy" decoding="async" alt="" />
				<p>{t('library.emptyFavorites')}</p>
				<a href="/search" class="btn">{t('common.discoverPodcasts')}</a>
			</div>
		{:else}
			<div class="episode-list">
				{#each favorites as fav (fav.episode_id)}
					<div class="ep-row">
						<a class="ep-art" href={`/episode/${fav.episode_id}`} aria-label={fav.title || t('common.episode')} title={fav.title || t('common.episode')}>
							<img src={optimizeArtwork(fav.artwork_url, 120)} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						</a>
						<div class="ep-body">
							<a class="ep-title" href={`/episode/${fav.episode_id}`} title={fav.title || t('common.episode')}>{fav.title || t('common.episode')}</a>
							<span class="ep-sub" title={fav.podcast_title || undefined}>{fav.podcast_title || ''}</span>
						</div>
						<EpisodeProgressButton label={t('library.playEpisode')} onclick={() => playFavorite(fav)} />
						<button class="ep-remove fav-heart" onclick={() => unfavorite(fav.episode_id)} aria-label={t('library.removeFromFavorites')} title={t('library.removeFromFavorites')}>
							<i class="ph-fill ph-heart" aria-hidden="true"></i>
						</button>
					</div>
				{/each}
			</div>
		{/if}
	{/if}
</div>

<style>
	.library-page {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.lib-head h1 {
		font-size: clamp(1.6rem, 3vw, 2.1rem);
		font-weight: 800;
		letter-spacing: -0.02em;
		display: flex;
		align-items: center;
		gap: 0.55rem;
	}
	.lib-head h1 :global(.ph-fill) { color: var(--accent-green); }
	.lib-head .sub { color: var(--text-muted); font-size: 0.95rem; margin-top: 0.25rem; }

	.tabs {
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		padding-bottom: 0.25rem;
		scrollbar-width: none;
	}
	.tabs::-webkit-scrollbar { display: none; }

	.tabs button {
		display: inline-flex;
		align-items: center;
		gap: 0.45rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		font-weight: 600;
		font-size: 0.9rem;
		color: var(--text-secondary);
		padding: 0.5rem 1rem;
		border-radius: 999px;
		white-space: nowrap;
		transition: all 0.22s var(--ease-spring, ease);
	}
	.tabs button :global(.ph) { font-size: 1.05rem; }
	.tabs button:hover { border-color: var(--accent-green); color: var(--text-primary); }

	.tabs button.active {
		background: var(--accent-green);
		border-color: var(--accent-green);
		color: var(--accent-button-text);
	}
	.tabs .count {
		font-size: 0.72rem;
		font-weight: 800;
		background: var(--bg-elevated);
		color: var(--text-secondary);
		padding: 0.05rem 0.45rem;
		border-radius: 999px;
		min-width: 1.4em;
		text-align: center;
	}
	.tabs button.active .count {
		background: rgba(255, 255, 255, 0.25);
		color: var(--accent-button-text);
	}

	.empty-state {
		text-align: center;
		padding: 3rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
	}
	.empty-illustration { width: min(256px, 72vw); height: auto; aspect-ratio: 1; object-fit: contain; margin: -1rem auto 0; }
	.empty-illustration.queue { width: min(176px, 54vw); aspect-ratio: 2 / 3; }

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

	.btn {
		display: inline-block;
		margin-top: 1rem;
		background: var(--accent-green);
		color: var(--accent-button-text);
		padding: 0.6rem 1.4rem;
		border-radius: 10px;
		font-weight: 700;
	}
	.btn:hover { text-decoration: none; background: var(--accent-green-hover); }
	.empty-actions { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; }
	.btn.secondary { border: 1px solid var(--border-ui); background: transparent; color: var(--text-primary); }

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

	.ep-art {
		display: block;
		width: 56px;
		height: 56px;
		flex-shrink: 0;
		border-radius: 10px;
		overflow: hidden;
		line-height: 0;
	}
	.ep-art img { width: 100%; height: 100%; object-fit: cover; }

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

	.queue-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1rem; }
	.queue-hint { font-size: 0.8rem; color: var(--text-muted); }
	.drag-handle { color: var(--text-muted); font-size: 1.3rem; cursor: grab; flex-shrink: 0; display: grid; place-items: center; }
	.drag-handle:active { cursor: grabbing; }

	.reorder-btns { display: flex; gap: 2px; flex-shrink: 0; }
	.reorder-btn {
		width: 36px;
		height: 36px;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		font-size: 1rem;
		border-radius: 6px;
		padding: 0;
	}
	.reorder-btn:hover:not(:disabled) { color: var(--accent-green); background: var(--bg-elevated); }
	.reorder-btn:disabled { opacity: 0.3; cursor: default; }

	/* The drag handle is a desktop nicety; on touch the up/down buttons do the work. */
	@media (hover: none) {
		.drag-handle { display: none; }
	}
	.ep-row[draggable='true'] { cursor: default; }
	.ep-row.dragging { opacity: 0.5; border-color: var(--accent-green); }
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
	.fav-heart { color: #e5484d; }
	.fav-heart:hover { background: color-mix(in srgb, #e5484d 14%, transparent); color: #e5484d; }

	@media (max-width: 640px) {
		.ep-pct { display: none; }
	}

	/* Quiet Edition 4b */
	.library-page { gap: 0; padding: 20px 22px 30px; }
	.lib-head {
		display: grid;
		grid-template-columns: auto minmax(180px, 1fr) auto;
		align-items: center;
		gap: 18px;
		margin-bottom: 14px;
	}
	.lib-head h1 { font: 800 26px/1 var(--font-ui); letter-spacing: -.035em; }
	.lib-head .sub { color: var(--ink-4); font: 600 11px/1 var(--font-mono); letter-spacing: .01em; }
	.library-filter {
		display: flex;
		align-items: center;
		gap: 8px;
		height: 36px;
		padding: 0 10px;
		background: var(--bg-sunken);
		border: 1px solid var(--border-ui);
		border-radius: 5px;
		color: var(--ink-4);
	}
	.library-filter input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 12px; }
	.library-sort { display: flex; gap: 2px; }
	.library-sort button {
		padding: 6px 8px;
		border: 0;
		border-radius: 3px;
		background: transparent;
		color: var(--ink-4);
		font: 600 10px/1 var(--font-mono);

	}
	.library-sort button.active { background: var(--accent-wash); color: var(--accent-ink); }
	.collection-tabs { margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid var(--border-hair); }
	.tabs button { min-height: 34px; border-radius: 4px; box-shadow: none; font: 600 10px/1 var(--font-mono); }
	.tabs button.active { background: var(--accent-fill); border-color: var(--accent-fill); color: var(--accent-on); }
	.podcast-grid { grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 16px; }
	.podcast-card.quiet-cover-card {
		position: relative;
		aspect-ratio: 1;
		overflow: hidden;
		padding: 0;
		border: 0;
		border-radius: 6px;
		background: var(--bg-tile);
		box-shadow: none;
	}
	.quiet-cover-card .artwork { width: 100%; height: 100%; border-radius: 0; object-fit: cover; }
	.quiet-cover-card .cover-link { position: absolute; inset: 0; }
	.quiet-cover-card .cover-overlay {
		position: absolute;
		inset: 0;
		display: flex;
		flex-direction: column;
		justify-content: end;
		gap: 4px;
		padding: 12px;
		background: linear-gradient(0deg, rgba(5,10,7,.96) 12%, rgba(5,10,7,.72) 58%, rgba(5,10,7,.15));
		opacity: 0;
		pointer-events: none;
		transition: opacity .16s ease;
	}
	.quiet-cover-card:hover .cover-overlay,
	.quiet-cover-card:focus-visible .cover-overlay,
	.quiet-cover-card:focus-within .cover-overlay,
	.quiet-cover-card.long-pressed .cover-overlay { opacity: 1; pointer-events: auto; }
	.quiet-cover-card .cover-overlay h3 { color: #eaf6f0; font: 700 14px/1.15 var(--font-ui); }
	.quiet-cover-card .cover-overlay p { color: #a9c8ba; font: 500 10px/1.35 var(--font-mono); }
	.quiet-cover-card .actions { display: flex; gap: 5px; margin-top: 6px; }
	.quiet-cover-card .round-action {
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		padding: 0;
		border: 1px solid #4a6558;
		border-radius: 50%;
		background: rgba(5,10,7,.7);
		color: #dcebe4;
	}
	.quiet-cover-card .round-action.primary { background: var(--accent-fill); border-color: var(--accent-fill); color: var(--accent-on); }
	.quiet-cover-card .round-action i { display: block; font-size: 15px; line-height: 1; }
	@media (prefers-reduced-motion: reduce) { .quiet-cover-card .cover-overlay { transition: none; } }
	.episode-list { gap: 0; border-top: 1px solid var(--border-hair); }
	.ep-row { border: 0; border-bottom: 1px solid var(--border-row); border-radius: 0; background: transparent; box-shadow: none; }
	.ep-row:hover { transform: none; background: var(--bg-sunken); }
	.empty-state { box-shadow: none; border-radius: 8px; }

	@media (max-width: 1180px) { .podcast-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
	@media (max-width: 820px) {
		.lib-head { grid-template-columns: 1fr; gap: 10px; }
		.library-sort { overflow-x: auto; }
		.podcast-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
	}
	@media (max-width: 560px) {
		.library-page { padding: 16px; }
		.collection-tabs { overflow-x: auto; flex-wrap: nowrap; }
		.podcast-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
		.quiet-cover-card .cover-overlay { padding: 8px; }
		.quiet-cover-card .cover-overlay h3 { font-size: 11px; }
		.quiet-cover-card .cover-overlay p { display: none; }
		.quiet-cover-card .cover-overlay { opacity: 1; pointer-events: auto; background: linear-gradient(0deg, rgba(5,10,7,.96) 8%, rgba(5,10,7,.52) 58%, transparent 78%); }
		.tabs button, .reorder-btn, .ep-remove { min-height: 44px; min-width: 44px; }
	}
</style>
