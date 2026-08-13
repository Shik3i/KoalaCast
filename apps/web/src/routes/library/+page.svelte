<script lang="ts">
	import { t } from '$lib/i18n';
	import { confirmDialog } from '$lib/stores/confirm.svelte';
	import { onMount } from 'svelte';
	import {
		getLocalSubscriptions,
		removeLocalSubscription,
		getRecentPlaybackStates,
		getCompletedEpisodeIds,
		reorderLocalQueue,
		getLocalFavorites,
		removeLocalFavorite,
		getLocalNamedQueues,
		saveLocalNamedQueue,
		removeLocalNamedQueue,
		replaceLocalQueueFromSync,
		setLocalSubscriptionFolder,
		type LocalSubscription,
		type LocalPlaybackState,
		type LocalFavorite,
		type LocalNamedQueue,
		type LocalQueueItem,
		getSmartQueues,
		saveSmartQueue,
		removeSmartQueue
	} from '$lib/idb/db';
	import {
		DEFAULT_SMART_QUEUE_RULES,
		evaluateSmartQueue,
		normalizeRules,
		totalDurationMs,
		type SmartQueue,
		type SmartQueueCandidate
	} from '$lib/queues/smart';
	import { readCachedContent } from '$lib/cache/content';
	import { audioDownloads } from '$lib/downloads/manager.svelte';
	import { player, type CurrentTrack } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { goto } from '$app/navigation';
	import { reveal } from '$lib/actions/reveal';
	import { optimizeArtwork, SUBSCRIPTION_ARTWORK_SIZE } from '$lib/artwork';
	import { podcastHref } from '$lib/podcast-link';
	import EpisodeProgressButton from '$lib/components/EpisodeProgressButton.svelte';
	import { waitForAccountContext } from '$lib/stores/account-context';

	let subscriptions = $state<LocalSubscription[]>([]);
	let recentEpisodes = $state<LocalPlaybackState[]>([]);
	let queue = $state<CurrentTrack[]>([]);
	let favorites = $state<LocalFavorite[]>([]);
	let namedQueues = $state<LocalNamedQueue[]>([]);
	let queueName = $state('');
	let activeTab = $state<'subscriptions' | 'episodes' | 'queue' | 'favorites'>('subscriptions');
	let dragIndex = $state<number | null>(null);
	let libraryQuery = $state('');
	let librarySort = $state<'recent' | 'az'>('recent');
	let activeCover = $state<string | null>(null);
	let playingSubscriptionId = $state<string | null>(null);
	let libraryReady = $state(false);
	let activeFolder = $state('');
	let longPressTimer: number | null = null;
	const libraryTabs = ['subscriptions', 'episodes', 'queue', 'favorites'] as const;
	const subscriptionFolders = $derived(
		Array.from(
			new Set(subscriptions.map((subscription) => subscription.folder?.trim()).filter(Boolean))
		).sort((a, b) => String(a).localeCompare(String(b))) as string[]
	);

	function handleTabKey(event: KeyboardEvent) {
		if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
		event.preventDefault();
		const current = libraryTabs.indexOf(activeTab);
		const next = event.key === 'Home'
			? 0
			: event.key === 'End'
				? libraryTabs.length - 1
				: (current + (event.key === 'ArrowRight' ? 1 : -1) + libraryTabs.length) %
					libraryTabs.length;
		activeTab = libraryTabs[next];
		document.querySelector<HTMLElement>(`[data-library-tab="${activeTab}"]`)?.focus();
	}

	const visibleSubscriptions = $derived.by(() => {
		let list = subscriptions.filter((subscription) =>
			subscription.title.toLowerCase().includes(libraryQuery.trim().toLowerCase())
		);
		if (activeFolder) list = list.filter((subscription) => subscription.folder === activeFolder);
		if (librarySort === 'az') list = [...list].sort((a, b) => a.title.localeCompare(b.title));
		return list;
	});

	onMount(async () => {
		await waitForAccountContext();
		const requestedView = new URLSearchParams(window.location.search).get('view');
		if (requestedView === 'subscriptions' || requestedView === 'episodes' || requestedView === 'queue' || requestedView === 'favorites') {
			activeTab = requestedView;
		}
		subscriptions = await getLocalSubscriptions();
		recentEpisodes = await getRecentPlaybackStates(30);
		await player.loadQueue();
		favorites = await getLocalFavorites();
		namedQueues = await getLocalNamedQueues();
		await loadSmartQueues();
		libraryReady = true;
	});

	// ---- Smart queues ----
	let smartQueues = $state<SmartQueue[]>([]);
	let smartCandidates = $state<SmartQueueCandidate[]>([]);
	let completedIds = $state<Set<string>>(new Set());
	let editingSmartQueue = $state<SmartQueue | null>(null);

	const downloadedIds = $derived(
		new Set(
			audioDownloads.items
				.filter((item) => item.state === 'downloaded')
				.map((item) => item.episodeId)
		)
	);

	async function loadSmartQueues() {
		smartQueues = await getSmartQueues();
		completedIds = await getCompletedEpisodeIds();
		// Evaluated over what the Inbox has already cached, so opening the Library
		// never fans out into one request per subscribed show.
		const cached = await Promise.all(
			subscriptions.map(async (sub) => {
				const entry = await readCachedContent<SmartQueueCandidate[]>(
					`inbox:${sub.podcast_id}`,
					Number.MAX_SAFE_INTEGER
				);
				return (entry?.value ?? []).map((episode) => ({
					...episode,
					podcast_id: sub.podcast_id,
					podcast_title: sub.title
				}));
			})
		);
		smartCandidates = cached.flat();
	}

	function formatQueueDuration(ms: number): string {
		const minutes = Math.round(ms / 60_000);
		const hours = Math.floor(minutes / 60);
		return hours > 0 ? `${hours} h ${String(minutes % 60).padStart(2, '0')} min` : `${minutes} min`;
	}

	function smartQueueMatches(queue: SmartQueue): SmartQueueCandidate[] {
		return evaluateSmartQueue(smartCandidates, normalizeRules(queue.rules), {
			completedIds,
			downloadedIds,
			now: Date.now()
		});
	}

	function newSmartQueue() {
		editingSmartQueue = {
			id: crypto.randomUUID(),
			name: '',
			rules: { ...DEFAULT_SMART_QUEUE_RULES },
			updated_at: Date.now()
		};
	}

	async function persistSmartQueue() {
		const queue = editingSmartQueue;
		if (!queue || !queue.name.trim()) return;
		await saveSmartQueue({ ...queue, name: queue.name.trim(), updated_at: Date.now() });
		editingSmartQueue = null;
		await loadSmartQueues();
		toast.success(t('library.smartQueueSaved'));
	}

	async function deleteSmartQueue(queue: SmartQueue) {
		if (!(await confirmDialog.ask(t('library.smartQueueConfirmDelete', { name: queue.name })))) return;
		await removeSmartQueue(queue.id);
		await loadSmartQueues();
	}

	async function fillFromSmartQueue(queue: SmartQueue) {
		const matches = smartQueueMatches(queue);
		if (matches.length === 0) {
			toast.error(t('library.smartQueueNoMatches'));
			return;
		}
		await player.addManyToQueue(
			matches.map((episode) => ({
				episode_id: episode.id,
				podcast_id: episode.podcast_id,
				title: episode.title,
				podcast_title: episode.podcast_title,
				artwork_url: episode.artwork_url ?? '',
				enclosure_url: episode.enclosure_url,
				duration_ms: episode.duration_ms ?? 0
			}))
		);
		toast.success(t('library.smartQueueFilled', { count: matches.length }));
	}

	// Mirror the store's queue into the local list so the tab stays correct when the
	// queue changes elsewhere (e.g. autoplay advancing to the next episode) — except
	// mid-drag, where the local list is the source of truth until it's persisted.
	$effect(() => {
		const storeQueue = player.queue;
		if (dragIndex === null) queue = [...storeQueue];
	});

	function playFavorite(fav: LocalFavorite) {
		// Already the episode running: the control is showing the playing
		// animation, so it has to be a pause, not a restart.
		if (player.current?.episode_id === fav.episode_id) {
			player.requestTogglePlayPause();
			return;
		}
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
		// Already the episode running: the control is showing the playing
		// animation, so it has to be a pause, not a restart.
		if (player.current?.episode_id === item.episode_id) {
			player.requestTogglePlayPause();
			return;
		}
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

	async function saveNamedQueue() {
		const name = queueName.trim();
		if (!name || !queue.length) return;
		const now = Date.now();
		const items: LocalQueueItem[] = queue.map((item, index) => ({
			id: crypto.randomUUID(),
			...item,
			position_order: index,
			added_at: now + index
		}));
		await saveLocalNamedQueue(name, items);
		namedQueues = await getLocalNamedQueues();
		queueName = '';
		toast.success(t('library.namedQueueSaved', { name }));
	}

	async function restoreNamedQueue(namedQueue: LocalNamedQueue) {
		await replaceLocalQueueFromSync(namedQueue.items, Date.now(), { authoritative: true });
		await player.loadQueue();
		queue = [...player.queue];
		toast.success(t('library.namedQueueRestored', { name: namedQueue.name }));
	}

	async function deleteNamedQueue(id: string) {
		await removeLocalNamedQueue(id);
		namedQueues = namedQueues.filter((queue) => queue.id !== id);
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

	async function playLatestUnheard(subscription: LocalSubscription) {
		if (playingSubscriptionId) return;
		playingSubscriptionId = subscription.podcast_id;
		try {
			let podcastId = subscription.podcast_id;
			if (subscription.feed_url && podcastId === subscription.feed_url) {
				const resolution = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: subscription.feed_url })
				});
				if (!resolution.ok) throw new Error(`Feed resolution failed: ${resolution.status}`);
				const resolved = await resolution.json();
				if (!resolved.id) throw new Error('Feed resolution omitted podcast id');
				podcastId = resolved.id;
			}

			const completedIds = await getCompletedEpisodeIds();
			const pageSize = 200;
			let offset = 0;
			let episode: any = null;
			while (!episode) {
				const response = await fetch(
					`/api/v1/podcasts/${encodeURIComponent(podcastId)}/episodes?limit=${pageSize}&offset=${offset}`,
					{ cache: 'no-cache' }
				);
				if (!response.ok) throw new Error(`Episode lookup failed: ${response.status}`);
				const data = await response.json();
				const episodes = Array.isArray(data.episodes) ? data.episodes : [];
				episode = episodes.find(
					(item: any) => item.enclosure_url && !completedIds.has(item.id)
				);
				if (episode || episodes.length < pageSize) break;
				offset += pageSize;
			}

			if (!episode) {
				toast.info(t('library.noUnplayedEpisodes'));
				return;
			}
			player.play({
				episode_id: episode.id,
				podcast_id: podcastId,
				title: episode.title,
				podcast_title: subscription.title,
				artwork_url: episode.artwork_url || subscription.artwork_url,
				enclosure_url: episode.enclosure_url,
				duration_ms: episode.duration_ms || 0
			});
		} catch {
			toast.error(t('library.playEpisodeError'));
		} finally {
			playingSubscriptionId = null;
		}
	}

	async function assignFolder(subscription: LocalSubscription) {
		const folder = window.prompt(
			t('library.folderPrompt', { title: subscription.title }),
			subscription.folder || ''
		);
		if (folder === null) return;
		await setLocalSubscriptionFolder(subscription.podcast_id, folder);
		subscriptions = subscriptions.map((item) =>
			item.podcast_id === subscription.podcast_id ? { ...item, folder: folder.trim() } : item
		);
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

	<div class="segmented collection-tabs" role="tablist" aria-label={t('library.sections')}>
		<button data-library-tab="subscriptions" role="tab" aria-selected={activeTab === 'subscriptions'} tabindex={activeTab === 'subscriptions' ? 0 : -1} class:active={activeTab === 'subscriptions'} onclick={() => (activeTab = 'subscriptions')} onkeydown={handleTabKey}>
			{t('library.subscriptions')}
		</button>
		<button data-library-tab="episodes" role="tab" aria-selected={activeTab === 'episodes'} tabindex={activeTab === 'episodes' ? 0 : -1} class:active={activeTab === 'episodes'} onclick={() => (activeTab = 'episodes')} onkeydown={handleTabKey}>
			{t('library.inProgress')}
		</button>
		<button data-library-tab="queue" role="tab" aria-selected={activeTab === 'queue'} tabindex={activeTab === 'queue' ? 0 : -1} class:active={activeTab === 'queue'} onclick={() => (activeTab = 'queue')} onkeydown={handleTabKey}>
			{t('library.queue')}
		</button>
		<button data-library-tab="favorites" role="tab" aria-selected={activeTab === 'favorites'} tabindex={activeTab === 'favorites' ? 0 : -1} class:active={activeTab === 'favorites'} onclick={() => (activeTab = 'favorites')} onkeydown={handleTabKey}>
			{t('library.favorites')}
		</button>
	</div>

	{#if activeTab === 'subscriptions'}
		{#if subscriptionFolders.length > 0}
			<div class="folder-filter" role="group" aria-label={t('library.folderFilter')}>
				<button class:active={activeFolder === ''} onclick={() => (activeFolder = '')}>
					{t('library.allFolders')}
				</button>
				{#each subscriptionFolders as folder}
					<button class:active={activeFolder === folder} onclick={() => (activeFolder = folder)}>
						<i class="ph ph-folder-simple" aria-hidden="true"></i> {folder}
					</button>
				{/each}
			</div>
		{/if}
		{#if !libraryReady}
			<div class="empty-state" role="status">
				<i class="ph ph-spinner-gap empty-icon" aria-hidden="true"></i>
				<p>{t('common.loading')}</p>
			</div>
		{:else if subscriptions.length === 0}
			<div class="empty-state">
				<i class="ph ph-books empty-icon" aria-hidden="true"></i>
				<img class="empty-illustration" src="/illustrations/empty-library.webp" width="256" height="256" loading="lazy" decoding="async" alt="" />
				<p>{t('library.emptySubscriptions')}</p>
				<div class="empty-actions">
					<a href="/search" class="btn">{t('common.discoverPodcasts')}</a>
					<a href="/settings#opml" class="btn btn-secondary opml-shortcut">{t('settings.opmlTitle')}</a>
				</div>
			</div>
		{:else}
			<div class="podcast-grid">
				{#each visibleSubscriptions as sub, i (sub.podcast_id)}
					<article class="podcast-card quiet-cover-card" class:long-pressed={activeCover === sub.podcast_id} use:reveal={{ delay: Math.min(i * 24, 180), duration: 220, immediate: true }} use:longPress={sub.podcast_id}>
						<a class="cover-link" href={podcastHref(sub)} aria-label={t('library.openShow', { title: sub.title })} title={sub.title}>
							<img src={optimizeArtwork(sub.artwork_url, SUBSCRIPTION_ARTWORK_SIZE)} alt="" class="artwork" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						</a>
						<div class="details cover-overlay">
							<h3 title={sub.title}>{sub.title}</h3>
							<p>{t('library.subscribedHint')}</p>
							<div class="actions">
								<button
									type="button"
									class="round-action primary"
									disabled={playingSubscriptionId !== null}
									onclick={() => playLatestUnheard(sub)}
									aria-label={t('library.playLatestUnheard', { title: sub.title })}
									title={t('library.playLatestUnheard', { title: sub.title })}
								><i class={playingSubscriptionId === sub.podcast_id ? 'ph ph-spinner spinner' : 'ph-fill ph-play'} aria-hidden="true"></i></button>
								<button class="round-action" onclick={() => assignFolder(sub)} aria-label={t('library.assignFolder', { title: sub.title })} title={sub.folder || t('library.assignFolder', { title: sub.title })}><i class="ph ph-folder-simple" aria-hidden="true"></i></button>
								<button class="round-action" onclick={() => handleUnsubscribe(sub.podcast_id)} aria-label={t('common.unsubscribe')} title={t('common.unsubscribe')}><i class="ph ph-minus-circle" aria-hidden="true"></i></button>
							</div>
						</div>
					</article>
				{/each}
			</div>
		{/if}
	{:else if activeTab === 'episodes'}
		{#if recentEpisodes.length === 0}
			<div class="empty-state">
				<i class="ph ph-hourglass-medium empty-icon" aria-hidden="true"></i>
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
		<!--
			A saved question rather than a saved list: "everything unplayed under
			twenty minutes from this week" has a different answer every day, and
			answering it by hand meant scrolling the Inbox and adding episodes one at
			a time. Evaluated over the episode lists the Inbox has already cached.
		-->
		<section class="smart-queues" aria-labelledby="smart-queues-title">
			<div class="named-queue-head">
				<div>
					<h2 id="smart-queues-title">{t('library.smartQueues')}</h2>
					<p>{t('library.smartQueuesHint')}</p>
				</div>
				<button onclick={newSmartQueue}>
					<i class="ph ph-plus" aria-hidden="true"></i> {t('library.smartQueueNew')}
				</button>
			</div>

			{#if editingSmartQueue}
				{@const draft = editingSmartQueue}
				{@const preview = smartQueueMatches(draft)}
				<form class="smart-editor" onsubmit={(event) => { event.preventDefault(); persistSmartQueue(); }}>
					<label class="smart-name">
						<span>{t('library.smartQueueName')}</span>
						<input bind:value={draft.name} placeholder={t('library.smartQueueNamePlaceholder')} required />
					</label>
					<div class="smart-rules">
						<label>
							<span>{t('library.smartQueueMaxLength')}</span>
							<select bind:value={draft.rules.maxDurationMs}>
								<option value={0}>{t('library.smartQueueAnyLength')}</option>
								<option value={600000}>10 min</option>
								<option value={1200000}>20 min</option>
								<option value={1800000}>30 min</option>
								<option value={3600000}>60 min</option>
							</select>
						</label>
						<label>
							<span>{t('library.smartQueueAge')}</span>
							<select bind:value={draft.rules.withinDays}>
								<option value={0}>{t('library.smartQueueAnyAge')}</option>
								<option value={1}>{t('library.smartQueueDays', { count: 1 })}</option>
								<option value={7}>{t('library.smartQueueDays', { count: 7 })}</option>
								<option value={30}>{t('library.smartQueueDays', { count: 30 })}</option>
							</select>
						</label>
						<label>
							<span>{t('library.smartQueueSort')}</span>
							<select bind:value={draft.rules.sort}>
								<option value="newest">{t('library.smartQueueNewest')}</option>
								<option value="oldest">{t('library.smartQueueOldest')}</option>
								<option value="shortest">{t('library.smartQueueShortest')}</option>
							</select>
						</label>
						<label>
							<span>{t('library.smartQueueLimit')}</span>
							<select bind:value={draft.rules.limit}>
								{#each [5, 10, 20, 50] as value}<option {value}>{value}</option>{/each}
							</select>
						</label>
						<label class="smart-toggle">
							<input type="checkbox" bind:checked={draft.rules.unplayedOnly} />
							{t('library.smartQueueUnplayedOnly')}
						</label>
						<label class="smart-toggle">
							<input type="checkbox" bind:checked={draft.rules.downloadedOnly} />
							{t('library.smartQueueDownloadedOnly')}
						</label>
					</div>
					<p class="smart-preview" aria-live="polite">
						{t('library.smartQueuePreview', {
							count: preview.length,
							duration: formatQueueDuration(totalDurationMs(preview))
						})}
					</p>
					<div class="smart-editor-actions">
						<button type="submit" disabled={!draft.name.trim()}>{t('common.save')}</button>
						<button type="button" class="ghost" onclick={() => (editingSmartQueue = null)}>{t('common.cancel')}</button>
					</div>
				</form>
			{/if}

			{#if smartQueues.length > 0}
				<div class="named-queue-list">
					{#each smartQueues as smart (smart.id)}
						{@const matches = smartQueueMatches(smart)}
						<div class="named-queue-row">
							<button class="named-queue-restore" onclick={() => fillFromSmartQueue(smart)}>
								<strong>{smart.name}</strong>
								<span>{t('library.smartQueuePreview', {
									count: matches.length,
									duration: formatQueueDuration(totalDurationMs(matches))
								})}</span>
							</button>
							<button
								class="named-queue-delete"
								onclick={() => (editingSmartQueue = { ...smart, rules: normalizeRules(smart.rules) })}
								aria-label={t('library.smartQueueEdit', { name: smart.name })}
							>
								<i class="ph ph-pencil-simple" aria-hidden="true"></i>
							</button>
							<button
								class="named-queue-delete"
								onclick={() => deleteSmartQueue(smart)}
								aria-label={t('library.smartQueueDelete', { name: smart.name })}
							>
								<i class="ph ph-trash" aria-hidden="true"></i>
							</button>
						</div>
					{/each}
				</div>
			{/if}
		</section>

		<section class="named-queues" aria-labelledby="named-queues-title">
			<div class="named-queue-head">
				<div>
					<h2 id="named-queues-title">{t('library.namedQueues')}</h2>
					<p>{t('library.namedQueuesHint')}</p>
				</div>
				<div class="named-queue-save">
					<input
						bind:value={queueName}
						placeholder={t('library.namedQueuePlaceholder')}
						aria-label={t('library.namedQueuePlaceholder')}
						onkeydown={(event) => event.key === 'Enter' && saveNamedQueue()}
					/>
					<button onclick={saveNamedQueue} disabled={!queueName.trim() || queue.length === 0}>
						<i class="ph ph-floppy-disk" aria-hidden="true"></i>
						{t('library.saveQueue')}
					</button>
				</div>
			</div>
			{#if namedQueues.length > 0}
				<div class="named-queue-list">
					{#each namedQueues as namedQueue (namedQueue.id)}
						<div class="named-queue-row">
							<button class="named-queue-restore" onclick={() => restoreNamedQueue(namedQueue)}>
								<strong>{namedQueue.name}</strong>
								<span>{t('library.namedQueueEpisodes', { count: namedQueue.items.length })}</span>
							</button>
							<button
								class="named-queue-delete"
								onclick={() => deleteNamedQueue(namedQueue.id)}
								aria-label={t('library.deleteNamedQueue', { name: namedQueue.name })}
							>
								<i class="ph ph-trash" aria-hidden="true"></i>
							</button>
						</div>
					{/each}
				</div>
			{/if}
		</section>
		{#if queue.length === 0}
			<div class="empty-state">
				<i class="ph ph-list-plus empty-icon" aria-hidden="true"></i>
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
				<i class="ph ph-heart empty-icon" aria-hidden="true"></i>
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

	.folder-filter {
		display: flex;
		gap: 0.45rem;
		margin-bottom: 1rem;
		overflow-x: auto;
	}
	.folder-filter button {
		display: inline-flex;
		align-items: center;
		gap: 0.35rem;
		min-height: 40px;
		padding: 0 0.8rem;
		border: 1px solid var(--border-ui);
		border-radius: 999px;
		background: var(--bg-surface);
		color: var(--text-secondary);
		white-space: nowrap;
	}
	.folder-filter button.active {
		border-color: var(--accent-green);
		color: var(--accent-green);
		background: color-mix(in srgb, var(--accent-green) 10%, var(--bg-surface));
	}


	/*
	 * The drawing where there is room for it, the app's icon badge where there
	 * is not. A 256px illustration is most of a phone screen, and the Android
	 * client shows a 56px circle in the same place.
	 */
	.empty-illustration { width: min(256px, 72vw); height: auto; aspect-ratio: 1; object-fit: contain; margin: -1rem auto 0; }
	.empty-illustration.queue { width: min(176px, 54vw); aspect-ratio: 2 / 3; }
	.empty-icon { display: none; }
	@media (max-width: 640px) {
		.empty-illustration { display: none; }
		.empty-icon { display: grid; }
		/* The app offers one way out of an empty library; import is in Settings. */
		.opml-shortcut { display: none; }
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

	.empty-actions { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; }

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
	.named-queues {
		margin-bottom: 1rem;
		padding: 1rem;
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		background: var(--bg-surface);
	}
	.smart-queues { display: grid; gap: 0.75rem; margin-bottom: 1.5rem; }
	.smart-editor {
		display: grid;
		gap: 0.75rem;
		padding: 1rem;
		border: 1px solid var(--border-ui);
		border-radius: var(--radius-lg, 12px);
		background: var(--bg-elevated);
	}
	.smart-name { display: grid; gap: 0.3rem; }
	.smart-name span, .smart-rules label > span { color: var(--text-muted); font-size: 0.78rem; }
	.smart-name input, .smart-rules select {
		padding: 0.5rem 0.6rem;
		border: 1px solid var(--border-ui);
		border-radius: var(--radius-control, 8px);
		background: var(--bg-panel);
		color: inherit;
	}
	.smart-rules {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
		gap: 0.6rem;
	}
	.smart-rules label { display: grid; gap: 0.3rem; align-content: start; }
	.smart-rules .smart-toggle {
		display: flex;
		align-items: center;
		gap: 0.45rem;
		align-self: end;
		font-size: 0.85rem;
	}
	.smart-preview { color: var(--accent-ink, var(--text-secondary)); font-size: 0.85rem; font-weight: 600; }
	.smart-editor-actions { display: flex; gap: 0.5rem; }
	.smart-editor-actions .ghost { background: transparent; }
	.named-queue-head { display: flex; align-items: end; justify-content: space-between; gap: 1rem; }
	.named-queue-head h2 { font-size: 1rem; }
	.named-queue-head p { margin-top: 0.2rem; color: var(--text-muted); font-size: 0.8rem; }
	.named-queue-save { display: flex; gap: 0.5rem; }
	.named-queue-save input {
		min-height: 44px;
		padding: 0 0.75rem;
		border: 1px solid var(--border-ui);
		border-radius: 8px;
		background: var(--bg-sunken);
		color: var(--text-primary);
	}
	.named-queue-save button,
	.named-queue-restore,
	.named-queue-delete {
		border: 1px solid var(--border-ui);
		border-radius: 8px;
		background: var(--bg-elevated);
		color: var(--text-primary);
	}
	.named-queue-save button { min-height: 44px; padding: 0 0.9rem; }
	.named-queue-save button:disabled { opacity: 0.45; }
	.named-queue-list { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.85rem; }
	.named-queue-row { display: flex; }
	.named-queue-restore {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 0.1rem;
		padding: 0.55rem 0.75rem;
		border-radius: 8px 0 0 8px;
	}
	.named-queue-restore span { color: var(--text-muted); font-size: 0.75rem; }
	.named-queue-delete { width: 44px; border-left: 0; border-radius: 0 8px 8px 0; color: var(--text-muted); }
	.named-queue-delete:hover { color: var(--color-danger); }
	.drag-handle { color: var(--text-muted); font-size: 1.3rem; cursor: grab; flex-shrink: 0; display: grid; place-items: center; }
	.drag-handle:active { cursor: grabbing; }

	.reorder-btns { display: flex; gap: 2px; flex-shrink: 0; }
	.reorder-btn {
		width: 44px;
		height: 44px;
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
		width: 44px;
		height: 44px;
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
		.named-queue-head { align-items: stretch; flex-direction: column; }
		.named-queue-save { flex-direction: column; }
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
		min-height: 44px;
		padding: 0 10px;
		background: var(--bg-sunken);
		border: 1px solid var(--border-ui);
		border-radius: var(--radius-control);
		color: var(--ink-4);
	}
	.library-filter input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 12px; }
	.library-sort { display: flex; gap: 2px; }
	.library-sort button {
		min-height: 44px;
		padding: 6px 8px;
		border: 0;
		border-radius: var(--radius-inset);
		background: transparent;
		color: var(--ink-4);
		font: 600 10px/1 var(--font-mono);

	}
	.library-sort button.active { background: var(--accent-wash); color: var(--accent-ink); }
	.collection-tabs { margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid var(--border-hair); }
	.podcast-grid { grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 16px; }
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
		flex: 0 0 44px;
		width: 44px;
		height: 44px;
		min-width: 44px;
		min-height: 44px;
		aspect-ratio: 1;
		box-sizing: border-box;
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

	@media (max-width: 1180px) { .podcast-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
	@media (max-width: 820px) {
		.lib-head { grid-template-columns: 1fr; gap: 10px; }
		.library-sort { overflow-x: auto; }
		.podcast-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
	}
	@media (max-width: 560px) {
		.library-page { padding: 16px; }
		.podcast-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
		.quiet-cover-card .cover-overlay { padding: 8px; }
		.quiet-cover-card .cover-overlay h3 { font-size: 11px; }
		.quiet-cover-card .cover-overlay p { display: none; }
		.quiet-cover-card .cover-overlay { opacity: 1; pointer-events: auto; background: linear-gradient(0deg, rgba(5,10,7,.96) 8%, rgba(5,10,7,.52) 58%, transparent 78%); }
		.segmented > button, .reorder-btn, .ep-remove { min-height: 44px; min-width: 44px; }
	}
	@media (max-width: 380px) {
		.podcast-grid { grid-template-columns: 1fr; }
	}
</style>
