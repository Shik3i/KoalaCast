<script lang="ts">
	import { t } from '$lib/i18n';
	import { onMount } from 'svelte';
	import {
		getLocalSubscriptions,
		getAllLocalPlaybackStates,
		setSubscriptionInboxMode,
		setEpisodePlayed,
		setEpisodesPlayed,
		type LocalSubscription,
		type LocalPlaybackState,
		type InboxMode
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { optimizeArtwork, SUBSCRIPTION_ARTWORK_SIZE } from '$lib/artwork';
	import { podcastHref } from '$lib/podcast-link';
	import { reveal } from '$lib/actions/reveal';
	import { slide } from 'svelte/transition';
	import { flip } from 'svelte/animate';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import EpisodeProgressButton from '$lib/components/EpisodeProgressButton.svelte';
	import { listeningSession } from '$lib/stores/session.svelte';
	import {
		cacheContent,
		CONTENT_TTL,
		readCachedContent,
		revalidateOnce
	} from '$lib/cache/content';
	import { getPodcastPlaybackSettings } from '$lib/stores/podcast-settings';
	import { notifyNewPodcastEpisodes } from '$lib/notifications/browser';

	interface InboxEpisode {
		id: string;
		podcast_id: string;
		podcast_title: string;
		title: string;
		description?: string;
		pub_date?: number; // unix seconds
		duration_ms?: number;
		enclosure_url: string;
		artwork_url?: string;
	}

	// Per feed we only pull the most recent slice; the Inbox is a "what's new"
	// view, not a full archive.
	const PER_FEED = 15;

	let subscriptions = $state<LocalSubscription[]>([]);
	let modes = $state<Record<string, InboxMode>>({});
	let rawEpisodes = $state<InboxEpisode[]>([]);
	let completed = $state<Set<string>>(new Set());
	let playbackStates = $state<Record<string, LocalPlaybackState>>({});
	// The Inbox is a "what's new" view, so played episodes are hidden by default;
	// the toggle lets you reveal them again.
	let unplayedOnly = $state(true);
	let showSettings = $state(false);
	let isLoading = $state(true);
	let openMenuId = $state<string | null>(null);

	// Run an async mapper over items with a bounded number of in-flight tasks, so a
	// user with many subscriptions doesn't fire dozens of simultaneous requests.
	async function mapWithConcurrency<T, R>(
		items: T[],
		limit: number,
		fn: (item: T) => Promise<R>
	): Promise<R[]> {
		const results = new Array<R>(items.length);
		let cursor = 0;
		const worker = async () => {
			while (cursor < items.length) {
				const idx = cursor++;
				results[idx] = await fn(items[idx]);
			}
		};
		await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
		return results;
	}

	onMount(async () => {
		listeningSession.load();
		const subs = await getLocalSubscriptions();
		subscriptions = subs;
		modes = Object.fromEntries(subs.map((s) => [s.podcast_id, s.inbox_mode ?? 'all']));
		const states = await getAllLocalPlaybackStates();
		playbackStates = Object.fromEntries(states.map((state) => [state.episode_id, state]));
		completed = new Set(states.filter((state) => state.completed).map((state) => state.episode_id));

		const cached = await Promise.all(
			subs.map(async (sub) => ({
				sub,
				entry: await readCachedContent<InboxEpisode[]>(
					`inbox:${sub.podcast_id}`,
					CONTENT_TTL.inbox
				)
			}))
		);
		const cachedEpisodes = cached.flatMap(({ entry }) => entry?.value ?? []);
		if (cached.some(({ entry }) => entry !== null) || subs.length === 0) {
			rawEpisodes = cachedEpisodes;
			isLoading = false;
		}

		const results = await mapWithConcurrency(cached, 6, async ({ sub, entry }) => {
			if (entry?.fresh) return entry.value;
			const refreshed = await revalidateOnce(`inbox:${sub.podcast_id}`, async () => {
				const newestKnown = Math.max(0, ...(entry?.value ?? []).map((episode) => episode.pub_date ?? 0));
				const params = new URLSearchParams();
				if (newestKnown > 0) params.set('since', String(newestKnown));
				const suffix = params.size ? `?${params}` : '';
				const res = await fetch(`/api/v1/podcasts/${sub.podcast_id}/episodes${suffix}`, {
					cache: 'no-cache'
				});
				if (!res.ok) throw new Error(`Inbox refresh failed: ${res.status}`);
				const data = await res.json();
				const episodes = ((data.episodes || []) as any[]).slice(0, PER_FEED).map((ep) => ({
					id: ep.id,
					podcast_id: sub.podcast_id,
					podcast_title: sub.title,
					title: ep.title,
					description: ep.description,
					pub_date: ep.pub_date,
					duration_ms: ep.duration_ms,
					enclosure_url: ep.enclosure_url,
					artwork_url: ep.artwork_url || sub.artwork_url
				})) as InboxEpisode[];
				if (entry && getPodcastPlaybackSettings(sub.podcast_id).notifyNewEpisodes) {
					const knownIds = new Set(entry.value.map((episode) => episode.id));
					const newEpisodes = episodes.filter((episode) => !knownIds.has(episode.id));
					await notifyNewPodcastEpisodes(sub.podcast_id, sub.title, newEpisodes);
				}
				const merged = mergeInboxEpisodes(episodes, entry?.value ?? []);
				await cacheContent(`inbox:${sub.podcast_id}`, merged);
				return merged;
			});
			return refreshed ?? entry?.value ?? [];
		});
		rawEpisodes = results.flat();
		isLoading = false;
	});

	function mergeInboxEpisodes(
		newEpisodes: InboxEpisode[],
		knownEpisodes: InboxEpisode[]
	): InboxEpisode[] {
		const byId = new Map(knownEpisodes.map((episode) => [episode.id, episode]));
		for (const episode of newEpisodes) byId.set(episode.id, episode);
		return [...byId.values()]
			.sort((a, b) => (b.pub_date ?? 0) - (a.pub_date ?? 0))
			.slice(0, PER_FEED);
	}

	// Apply per-podcast mode ('latest' keeps only the newest episode of that show),
	// the unplayed filter, then sort the whole feed newest-first.
	const feed = $derived.by(() => {
		const byPod = new Map<string, InboxEpisode[]>();
		for (const ep of rawEpisodes) {
			const list = byPod.get(ep.podcast_id) ?? [];
			list.push(ep);
			byPod.set(ep.podcast_id, list);
		}
		let out: InboxEpisode[] = [];
		for (const [pid, eps] of byPod) {
			const sorted = [...eps].sort((a, b) => (b.pub_date ?? 0) - (a.pub_date ?? 0));
			out.push(...((modes[pid] ?? 'all') === 'latest' ? sorted.slice(0, 1) : sorted));
		}
		if (unplayedOnly) out = out.filter((ep) => !completed.has(ep.id));
		return out.sort((a, b) => (b.pub_date ?? 0) - (a.pub_date ?? 0));
	});
	const groupedFeed = $derived.by(() => {
		const groups = new Map<string, { date: Date; episodes: InboxEpisode[] }>();
		for (const episode of feed) {
			const date = new Date((episode.pub_date || 0) * 1000);
			const key = episode.pub_date ? `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}` : 'undated';
			const group = groups.get(key) ?? { date, episodes: [] };
			group.episodes.push(episode);
			groups.set(key, group);
		}
		return [...groups.values()];
	});

	async function setMode(podcast_id: string, mode: InboxMode) {
		modes = { ...modes, [podcast_id]: mode };
		await setSubscriptionInboxMode(podcast_id, mode);
	}

	function showHref(podcastId: string): string {
		const subscription = subscriptions.find((item) => item.podcast_id === podcastId);
		return subscription
			? podcastHref(subscription)
			: `/podcast/${encodeURIComponent(podcastId)}`;
	}

	function epMeta(ep: InboxEpisode) {
		return {
			episode_id: ep.id,
			podcast_id: ep.podcast_id,
			title: ep.title,
			podcast_title: ep.podcast_title,
			artwork_url: ep.artwork_url || '',
			enclosure_url: ep.enclosure_url,
			duration_ms: ep.duration_ms
		};
	}

	async function queueAllThatFit() {
		if (listeningSession.minutes === null) return;
		let elapsed = 0;
		const tracks = [];
		for (const episode of feed) {
			const adjusted = (episode.duration_ms || 0) / player.playbackSpeed;
			if (!adjusted || elapsed + adjusted > listeningSession.minutes * 60_000) continue;
			tracks.push({
				episode_id: episode.id,
				podcast_id: episode.podcast_id,
				title: episode.title,
				podcast_title: episode.podcast_title,
				artwork_url: episode.artwork_url || '',
				enclosure_url: episode.enclosure_url,
				duration_ms: episode.duration_ms || 0
			});
			elapsed += adjusted;
		}
		await player.addManyToQueue(tracks);
		const count = tracks.length;
		toast.success(t('inbox.queuedForSession', { count, minutes: listeningSession.minutes }));
	}

	async function markAllPlayed() {
		await setEpisodesPlayed(feed.map(epMeta), true);
		completed = new Set([...completed, ...feed.map((episode) => episode.id)]);
		playbackStates = Object.fromEntries(
			Object.entries(playbackStates).map(([id, state]) => [
				id,
				feed.some((episode) => episode.id === id)
					? { ...state, completed: true, progress_percent: 100 }
					: state
			])
		);
	}

	function groupLabel(date: Date) {
		if (!date.getTime()) return t('inbox.undated');
		const label = date.toLocaleDateString(prefs.uiLanguage, { weekday: 'long', day: '2-digit', month: 'long' });
		return date.toDateString() === new Date().toDateString() ? `${t('inbox.today')} · ${label}` : label;
	}

	async function togglePlayed(ep: InboxEpisode) {
		const played = !completed.has(ep.id);
		openMenuId = null;
		await setEpisodePlayed(epMeta(ep), played);
		const next = new Set(completed);
		if (played) next.add(ep.id);
		else next.delete(ep.id);
		completed = next; // when unplayedOnly is on, played rows auto-hide
		const existing = playbackStates[ep.id];
		playbackStates = {
			...playbackStates,
			[ep.id]: {
				...existing,
				...epMeta(ep),
				position_ms: played ? existing?.position_ms ?? 0 : 0,
				completed: played,
				progress_percent: played ? 100 : 0,
				last_played_at: Date.now()
			}
		};
	}

	// "I've caught up to here" — mark this episode plus everything older in the
	// current (newest-first) feed as played/unplayed.
	async function markThisAndOlder(ep: InboxEpisode, played: boolean) {
		openMenuId = null;
		const idx = feed.findIndex((e) => e.id === ep.id);
		if (idx < 0) return;
		const list = feed.slice(idx);
		await setEpisodesPlayed(list.map(epMeta), played);
		const next = new Set(completed);
		for (const e of list) {
			if (played) next.add(e.id);
			else next.delete(e.id);
		}
		completed = next;
		playbackStates = {
			...playbackStates,
			...Object.fromEntries(
				list.map((episode) => {
					const existing = playbackStates[episode.id];
					return [
						episode.id,
						{
							...existing,
							...epMeta(episode),
							position_ms: played ? existing?.position_ms ?? 0 : 0,
							completed: played,
							progress_percent: played ? 100 : 0,
							last_played_at: Date.now()
						}
					];
				})
			)
		};
		toast.success(t(played ? 'inbox.markedPlayed' : 'inbox.markedUnplayed', { count: list.length }));
	}

	function play(ep: InboxEpisode) {
		player.play({
			episode_id: ep.id,
			podcast_id: ep.podcast_id,
			title: ep.title,
			podcast_title: ep.podcast_title,
			artwork_url: ep.artwork_url || '',
			enclosure_url: ep.enclosure_url,
			duration_ms: ep.duration_ms || 0
		});
	}

	function episodeProgress(ep: InboxEpisode) {
		if (completed.has(ep.id)) return 100;
		if (player.current?.episode_id === ep.id) {
			const duration = player.durationMs || ep.duration_ms || 0;
			if (duration > 0) return Math.min(100, (player.positionMs / duration) * 100);
		}
		return playbackStates[ep.id]?.progress_percent ?? 0;
	}

	function playLabel(ep: InboxEpisode) {
		const progress = Math.round(episodeProgress(ep));
		return progress > 0
			? `${t('inbox.playEpisode')} · ${progress}%`
			: t('inbox.playEpisode');
	}

	function toggleMenu(id: string, trigger: HTMLButtonElement) {
		const opening = openMenuId !== id;
		openMenuId = opening ? id : null;
		if (opening) requestAnimationFrame(() => trigger.parentElement?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus());
	}

	function handleMenuKeydown(event: KeyboardEvent) {
		const menu = event.currentTarget as HTMLElement;
		const items = [...menu.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')];
		const index = items.indexOf(document.activeElement as HTMLButtonElement);
		if (event.key === 'Escape') {
			event.preventDefault();
			event.stopPropagation();
			const trigger = menu.parentElement?.querySelector<HTMLButtonElement>('[aria-haspopup="menu"]');
			openMenuId = null;
			requestAnimationFrame(() => trigger?.focus());
		} else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
			event.preventDefault();
			const direction = event.key === 'ArrowDown' ? 1 : -1;
			items[(index + direction + items.length) % items.length]?.focus();
		} else if (event.key === 'Home' || event.key === 'End') {
			event.preventDefault();
			items[event.key === 'Home' ? 0 : items.length - 1]?.focus();
		}
	}

	function formatDuration(ms?: number) {
		if (!ms) return '';
		const total = Math.floor(ms / 1000);
		const h = Math.floor(total / 3600);
		const m = Math.floor((total % 3600) / 60);
		return h > 0 ? `${h}h ${m}m` : `${m}m`;
	}
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && (openMenuId = null)} />

<div class="inbox-page">
	<div class="head">
		<div>
			<h1><i class="ph-fill ph-tray" aria-hidden="true"></i> {t('inbox.title')}</h1>
			<p class="sub">{t('inbox.subtitle')}</p>
		</div>
		{#if subscriptions.length > 0}
			<div class="head-actions">
				{#if listeningSession.minutes !== null}
					<button class="btn-ghost" onclick={queueAllThatFit}><i class="ph ph-list-plus"></i> {t('inbox.queueFits')}</button>
				{/if}
				<button class="btn-ghost" onclick={markAllPlayed}><i class="ph ph-checks"></i> {t('podcast.markAllPlayed')}</button>
				<label class="switch">
					<input type="checkbox" bind:checked={unplayedOnly} />
					<span>{t('inbox.unplayedOnly')}</span>
				</label>
				<button class="btn-ghost" class:active={showSettings} onclick={() => (showSettings = !showSettings)}>
					<i class="ph ph-sliders-horizontal" aria-hidden="true"></i> {t('inbox.showSettings')}
				</button>
			</div>
		{/if}
	</div>

	{#if showSettings}
		<section class="settings-panel">
			<p class="panel-hint">
				{@html t('inbox.panelHint')}
			</p>
			<div class="settings-list">
				{#each subscriptions as sub (sub.podcast_id)}
					<div class="settings-row">
						<img src={optimizeArtwork(sub.artwork_url, SUBSCRIPTION_ARTWORK_SIZE)} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						<span class="s-title">{sub.title}</span>
						<div class="seg">
							<button class:active={(modes[sub.podcast_id] ?? 'all') === 'all'} onclick={() => setMode(sub.podcast_id, 'all')}>{t('inbox.modeAll')}</button>
							<button class:active={modes[sub.podcast_id] === 'latest'} onclick={() => setMode(sub.podcast_id, 'latest')}>{t('inbox.modeLatest')}</button>
						</div>
					</div>
				{/each}
			</div>
		</section>
	{/if}

	{#if isLoading}
		<div class="episode-list">
			{#each Array(6) as _}
				<div class="ep-row">
					<Skeleton width="56px" height="56px" radius="10px" />
					<div class="ep-body">
						<Skeleton width="70%" height="1.1rem" />
						<Skeleton width="40%" height="0.85rem" />
					</div>
				</div>
			{/each}
		</div>
	{:else if subscriptions.length === 0}
		<div class="empty-state">
			<i class="ph ph-tray" aria-hidden="true"></i>
			<p>{t('inbox.emptyNoSubscriptions')}</p>
			<a href="/search" class="btn">{t('common.discoverPodcasts')}</a>
		</div>
	{:else if feed.length === 0}
		<div class="empty-state">
			<i class="ph ph-check-circle" aria-hidden="true"></i>
			<p>{unplayedOnly ? t('inbox.emptyCaughtUp') : t('inbox.emptyNoRecent')}</p>
		</div>
	{:else}
		{#if openMenuId}
			<button class="menu-backdrop" onclick={() => (openMenuId = null)} aria-label={t('common.closeMenu')} tabindex="-1"></button>
		{/if}
		<div class="episode-list">
			{#each groupedFeed as group}
				<header class="day-header">
					<strong>{groupLabel(group.date)}</strong>
					<span>{t('inbox.episodeCount', { count: group.episodes.length })} · {formatDuration(group.episodes.reduce((sum, episode) => sum + (episode.duration_ms || 0), 0))}</span>
				</header>
				{#each group.episodes as ep, i (ep.id)}
					<div class="ep-row" use:reveal={{ delay: Math.min(i * 25, 250) }} out:slide={{ duration: 220 }} animate:flip={{ duration: 220 }} class:current={player.current?.episode_id === ep.id} class:played={completed.has(ep.id)} class:menu-open={openMenuId === ep.id}>
					<a class="ep-art" href={`/episode/${ep.id}`} aria-label={ep.title} title={ep.title}>
						<img src={optimizeArtwork(ep.artwork_url, SUBSCRIPTION_ARTWORK_SIZE)} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
					</a>
					<div class="ep-body">
						<a class="ep-title" href={`/episode/${ep.id}`} title={ep.title}>{ep.title}</a>
						<span class="ep-meta">
							<a class="ep-show" href={showHref(ep.podcast_id)} title={ep.podcast_title}>{ep.podcast_title}</a>
							{#if ep.pub_date}<span class="dot">•</span>{prefs.formatDate(ep.pub_date)}{/if}
							{#if ep.duration_ms}<span class="dot">•</span>{formatDuration(ep.duration_ms)}{/if}
							{#if completed.has(ep.id)}<span class="played-tag">{t('common.played')}</span>{/if}
						</span>
					</div>

					<EpisodeProgressButton
						progress={episodeProgress(ep)}
						current={player.current?.episode_id === ep.id}
						label={playLabel(ep)}
						onclick={() => play(ep)}
					/>

					<div class="row-menu">
						<button class="ep-kebab" onclick={(event) => toggleMenu(ep.id, event.currentTarget)} aria-haspopup="menu" aria-expanded={openMenuId === ep.id} aria-label={t('common.moreActions')} title={t('common.moreActions')}>
							<i class="ph ph-dots-three-vertical" aria-hidden="true"></i>
						</button>
						{#if openMenuId === ep.id}
							<div class="menu" role="menu" tabindex="-1" onkeydown={handleMenuKeydown}>
								<button role="menuitem" onclick={() => togglePlayed(ep)}>
									<i class="{completed.has(ep.id) ? 'ph ph-arrow-counter-clockwise' : 'ph ph-check-circle'}" aria-hidden="true"></i>
									{completed.has(ep.id) ? t('common.markUnplayed') : t('common.markPlayed')}
								</button>
								<button role="menuitem" onclick={() => markThisAndOlder(ep, true)}>
									<i class="ph ph-arrow-line-down" aria-hidden="true"></i> {t('common.markOlderPlayed')}
								</button>
								<button role="menuitem" onclick={() => markThisAndOlder(ep, false)}>
									<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i> {t('common.markOlderUnplayed')}
								</button>
							</div>
						{/if}
					</div>
					</div>
				{/each}
			{/each}
		</div>
	{/if}
</div>

<style>
	.inbox-page {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 1rem;
		flex-wrap: wrap;
	}
	.head h1 {
		font-size: 1.9rem;
		font-weight: 800;
		display: flex;
		align-items: center;
		gap: 0.55rem;
	}
	.head h1 :global(.ph-fill) { color: var(--accent-green); }
	.sub { color: var(--text-muted); font-size: 0.95rem; margin-top: 0.25rem; }

	.head-actions { display: flex; align-items: center; gap: 0.75rem; }
	.switch { display: inline-flex; align-items: center; gap: 0.45rem; font-size: 0.88rem; font-weight: 600; color: var(--text-secondary); cursor: pointer; }
	.switch input { width: 16px; height: 16px; accent-color: var(--accent-green); }

	.btn-ghost {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		color: var(--text-secondary);
		padding: 0.5rem 0.9rem;
		border-radius: 10px;
		font-weight: 600;
		font-size: 0.88rem;
	}
	.btn-ghost.active, .btn-ghost:hover { border-color: var(--accent-green); color: var(--accent-green); }

	.settings-panel {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		padding: 1.25rem;
	}
	.panel-hint { color: var(--text-secondary); font-size: 0.9rem; margin-bottom: 1rem; }
	.settings-list { display: flex; flex-direction: column; gap: 0.6rem; }
	.settings-row { display: flex; align-items: center; gap: 0.75rem; }
	.settings-row img { width: 36px; height: 36px; border-radius: 8px; object-fit: cover; flex-shrink: 0; }
	.s-title { flex: 1; min-width: 0; font-weight: 600; font-size: 0.9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

	.seg { display: flex; background: var(--bg-elevated); border-radius: 8px; padding: 3px; flex-shrink: 0; }
	.seg button {
		background: transparent;
		border: none;
		color: var(--text-secondary);
		font-size: 0.78rem;
		font-weight: 700;
		padding: 0.3rem 0.65rem;
		border-radius: 6px;
		white-space: nowrap;
	}
	.seg button.active { background: var(--accent-green); color: var(--accent-button-text); }

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
	.ep-row.current { border-color: var(--accent-green); background: color-mix(in srgb, var(--accent-green) 8%, var(--bg-surface)); }
	.ep-row.played { opacity: 0.6; }
	.ep-row.played:hover { opacity: 1; }

	.row-menu { position: relative; flex-shrink: 0; }
	.ep-kebab {
		width: 44px;
		height: 44px;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		font-size: 1.3rem;
		border-radius: 50%;
	}
	.ep-kebab:hover { color: var(--text-primary); background: var(--bg-elevated); }
	.menu-backdrop { position: fixed; inset: 0; z-index: 160; background: transparent; border: none; }
	.ep-row.menu-open { position: relative; z-index: 170; opacity: 1; }
	.menu {
		position: absolute;
		top: calc(100% + 4px);
		right: 0;
		z-index: 180;
		min-width: 250px;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		box-shadow: var(--shadow-lg);
		padding: 0.35rem;
		display: flex;
		flex-direction: column;
	}
	.menu button {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		width: 100%;
		background: none;
		border: none;
		text-align: left;
		color: var(--text-primary);
		font-size: 0.88rem;
		font-weight: 500;
		padding: 0.6rem 0.7rem;
		border-radius: 8px;
	}
	.menu button :global(.ph) { font-size: 1.1rem; color: var(--text-muted); }
	.menu button:hover { background: var(--bg-elevated); }
	.menu button:hover :global(.ph) { color: var(--accent-green); }

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
	.ep-meta {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		font-size: 0.8rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.ep-show { font-weight: 600; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; }
	.ep-show:hover { color: var(--accent-green); text-decoration: underline; }
	.dot { opacity: 0.5; }
	.played-tag {
		background: color-mix(in srgb, var(--accent-green) 16%, transparent);
		color: var(--accent-green);
		padding: 0.05rem 0.45rem;
		border-radius: 999px;
		font-size: 0.7rem;
		font-weight: 700;
	}

	.empty-state {
		text-align: center;
		padding: 3.5rem 2rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1rem;
		color: var(--text-muted);
	}
	.empty-state :global(.ph) { font-size: 2rem; }
	.btn {
		display: inline-block;
		background: var(--accent-green);
		color: var(--accent-button-text);
		padding: 0.6rem 1.4rem;
		border-radius: 10px;
		font-weight: 700;
	}
	.btn:hover { text-decoration: none; background: var(--accent-green-hover); }

	@media (max-width: 640px) {
		.settings-row .s-title { font-size: 0.82rem; }
	}

	/* Quiet Edition 4b */
	.inbox-page { gap: 0; padding: 20px 22px 32px; }
	.head { align-items: center; padding-bottom: 16px; border-bottom: 1px solid var(--border-hair); }
	.head h1 { gap: 0; font: 800 26px/1 var(--font-ui); letter-spacing: -.035em; }
	.head h1 :global(.ph-fill) { display: none; }
	.sub { color: var(--ink-4); font: 600 11px/1.4 var(--font-mono); letter-spacing: .01em; }
	.head-actions { gap: 6px; }
	.switch { min-height: 34px; padding: 0 10px; border: 1px solid var(--border-ui); border-radius: 4px; color: var(--ink-3); font: 600 10px/1 var(--font-mono); }
	.btn-ghost { min-height: 34px; padding: 0 10px; border-color: var(--border-ui); border-radius: 4px; background: transparent; color: var(--ink-3); font: 600 10px/1 var(--font-mono); }
	.settings-panel { margin: 14px 0; border: 1px solid var(--border-hair); border-radius: 6px; background: var(--bg-sunken); box-shadow: none; }
	.episode-list { gap: 0; }
	.day-header {
		position: sticky;
		top: 0;
		z-index: 4;
		display: flex;
		justify-content: space-between;
		gap: 12px;
		padding: 9px 7px;
		background: var(--bg-sunken);
		border-bottom: 1px solid var(--border-hair);
		color: var(--ink-4);
		font: 600 10px/1 var(--font-mono);
		letter-spacing: .01em;

	}
	.ep-row {
		display: grid;
		grid-template-columns: 56px minmax(0, 1fr) 44px 44px;
		gap: 12px;
		align-items: center;
		min-height: 76px;
		padding: 10px 5px;
		border: 0;
		border-bottom: 1px solid var(--border-row);
		border-radius: 0;
		background: transparent;
		box-shadow: none;
	}
	.ep-row:hover { transform: none; border-color: var(--border-row); background: var(--bg-sunken); }
	.ep-row.current { border-color: var(--border-row); background: linear-gradient(90deg, var(--accent-wash), transparent); }
	.ep-art { width: 56px; height: 56px; border-radius: 5px; background: var(--bg-tile); }
	.ep-title { color: var(--ink-2); font: 700 14px/1.3 var(--font-ui); }
	.ep-meta { color: var(--ink-4); font: 500 10px/1.4 var(--font-mono); letter-spacing: .01em; }
	.ep-show { color: var(--ink-3); }
	.ep-kebab { width: 44px; height: 44px; border: 1px solid var(--border-ui); border-radius: 50%; color: var(--ink-3); }
	.empty-state { margin-top: 16px; border-radius: 8px; box-shadow: none; }
	.menu { border-color: var(--border-ui); border-radius: 5px; background: var(--bg-rail); box-shadow: none; }

	@media (max-width: 560px) {
		.inbox-page { padding: 16px; }
		.head { align-items: flex-start; }
		.head-actions { width: 100%; }
		.ep-row { grid-template-columns: 48px minmax(0,1fr) 44px 44px; gap: 9px; min-height: 68px; }
		.ep-art { width: 48px; height: 48px; }
		.row-menu { display: block; }
		.ep-kebab, .switch, .btn-ghost { min-width: 44px; min-height: 44px; }
		.day-header span { display: none; }
	}
</style>
