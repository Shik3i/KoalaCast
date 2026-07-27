<script lang="ts">
	import { t } from '$lib/i18n';
	import { onMount } from 'svelte';
	import {
		getLocalSubscriptions,
		getCompletedEpisodeIds,
		setSubscriptionInboxMode,
		setEpisodePlayed,
		type LocalSubscription,
		type InboxMode
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { optimizeArtwork } from '$lib/artwork';
	import { reveal } from '$lib/actions/reveal';
	import { slide } from 'svelte/transition';
	import { flip } from 'svelte/animate';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import { listeningSession } from '$lib/stores/session.svelte';

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
		completed = await getCompletedEpisodeIds();

		const results = await mapWithConcurrency(subs, 6, async (sub) => {
			try {
				const res = await fetch(`/api/v1/podcasts/${sub.podcast_id}/episodes`);
				if (!res.ok) return [] as InboxEpisode[];
				const data = await res.json();
				return ((data.episodes || []) as any[]).slice(0, PER_FEED).map((ep) => ({
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
			} catch {
				return [] as InboxEpisode[];
			}
		});
		rawEpisodes = results.flat();
		isLoading = false;
	});

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
		let count = 0;
		for (const episode of feed) {
			const adjusted = (episode.duration_ms || 0) / player.playbackSpeed;
			if (!adjusted || elapsed + adjusted > listeningSession.minutes * 60_000) continue;
			await player.addToQueue({
				episode_id: episode.id,
				podcast_id: episode.podcast_id,
				title: episode.title,
				podcast_title: episode.podcast_title,
				artwork_url: episode.artwork_url || '',
				enclosure_url: episode.enclosure_url,
				duration_ms: episode.duration_ms || 0
			});
			elapsed += adjusted;
			count += 1;
		}
		toast.success(t('inbox.queuedForSession', { count, minutes: listeningSession.minutes }));
	}

	async function markAllPlayed() {
		await Promise.all(feed.map((episode) => setEpisodePlayed(epMeta(episode), true)));
		completed = new Set([...completed, ...feed.map((episode) => episode.id)]);
	}

	function groupLabel(date: Date) {
		if (!date.getTime()) return t('inbox.undated');
		const label = date.toLocaleDateString(prefs.uiLanguage, { weekday: 'long', day: '2-digit', month: 'long' });
		return date.toDateString() === new Date().toDateString() ? `${t('inbox.today')} · ${label}` : label;
	}

	async function togglePlayed(ep: InboxEpisode) {
		const played = !completed.has(ep.id);
		await setEpisodePlayed(epMeta(ep), played);
		const next = new Set(completed);
		if (played) next.add(ep.id);
		else next.delete(ep.id);
		completed = next; // when unplayedOnly is on, played rows auto-hide
	}

	// "I've caught up to here" — mark this episode plus everything older in the
	// current (newest-first) feed as played/unplayed.
	async function markThisAndOlder(ep: InboxEpisode, played: boolean) {
		openMenuId = null;
		const idx = feed.findIndex((e) => e.id === ep.id);
		if (idx < 0) return;
		const list = feed.slice(idx);
		await Promise.all(list.map((e) => setEpisodePlayed(epMeta(e), played)));
		const next = new Set(completed);
		for (const e of list) {
			if (played) next.add(e.id);
			else next.delete(e.id);
		}
		completed = next;
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
			<h2><i class="ph-fill ph-tray" aria-hidden="true"></i> {t('inbox.title')}</h2>
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
						<img src={optimizeArtwork(sub.artwork_url, 80)} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
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
					<div class="ep-row" use:reveal={{ delay: Math.min(i * 25, 250) }} out:slide={{ duration: 220 }} animate:flip={{ duration: 220 }} class:current={player.current?.episode_id === ep.id} class:played={completed.has(ep.id)}>
					<button class="ep-play" onclick={() => play(ep)} aria-label={t('inbox.playEpisode')} title={t('inbox.playEpisode')}>
						<img src={optimizeArtwork(ep.artwork_url, 120)} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')} />
						<span class="ep-play-icon"><i class="ph-fill ph-play" aria-hidden="true"></i></span>
					</button>
					<div class="ep-body">
						<a class="ep-title" href={`/episode/${ep.id}`} title={ep.title}>{ep.title}</a>
						<span class="ep-meta">
							<span class="ep-show" title={ep.podcast_title}>{ep.podcast_title}</span>
							{#if ep.pub_date}<span class="dot">•</span>{prefs.formatDate(ep.pub_date)}{/if}
							{#if ep.duration_ms}<span class="dot">•</span>{formatDuration(ep.duration_ms)}{/if}
							{#if completed.has(ep.id)}<span class="played-tag">{t('common.played')}</span>{/if}
						</span>
					</div>

					<button class="ep-mark" class:done={completed.has(ep.id)} onclick={() => togglePlayed(ep)} aria-pressed={completed.has(ep.id)} aria-label={completed.has(ep.id) ? t('common.markUnplayed') : t('common.markPlayed')} title={completed.has(ep.id) ? t('common.markUnplayed') : t('common.markPlayed')}>
						<i class="{completed.has(ep.id) ? 'ph-fill ph-check-circle' : 'ph ph-circle'}" aria-hidden="true"></i>
					</button>

					<div class="row-menu">
						<button class="ep-kebab" onclick={() => (openMenuId = openMenuId === ep.id ? null : ep.id)} aria-haspopup="menu" aria-expanded={openMenuId === ep.id} aria-label={t('common.moreActions')} title={t('common.moreActions')}>
							<i class="ph ph-dots-three-vertical" aria-hidden="true"></i>
						</button>
						{#if openMenuId === ep.id}
							<div class="menu" role="menu">
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
	.head h2 {
		font-size: 1.9rem;
		font-weight: 800;
		display: flex;
		align-items: center;
		gap: 0.55rem;
	}
	.head h2 :global(.ph-fill) { color: var(--accent-green); }
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

	.ep-mark {
		flex-shrink: 0;
		width: 40px;
		height: 40px;
		border-radius: 50%;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		font-size: 1.45rem;
		transition: transform 0.2s var(--ease-spring, ease), color 0.2s ease;
	}
	.ep-mark:hover { color: var(--accent-green); background: var(--bg-elevated); transform: scale(1.05); }
	.ep-mark.done { color: var(--accent-green); }

	.row-menu { position: relative; flex-shrink: 0; }
	.ep-kebab {
		width: 34px;
		height: 40px;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		font-size: 1.3rem;
		border-radius: 8px;
	}
	.ep-kebab:hover { color: var(--text-primary); background: var(--bg-elevated); }
	.menu-backdrop { position: fixed; inset: 0; z-index: 40; background: transparent; border: none; }
	.menu {
		position: absolute;
		top: calc(100% + 4px);
		right: 0;
		z-index: 50;
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
		opacity: 0.82;
		transition: opacity 0.2s ease;
	}
	.ep-play:hover .ep-play-icon { opacity: 1; }
	.ep-play-icon i { display: block; line-height: 1; }

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
	.head h2 { gap: 0; font: 800 26px/1 var(--font-ui); letter-spacing: -.035em; }
	.head h2 :global(.ph-fill) { display: none; }
	.sub { color: var(--ink-4); font: 600 11px/1.4 var(--font-mono); letter-spacing: .05em; text-transform: uppercase; }
	.head-actions { gap: 6px; }
	.switch { min-height: 34px; padding: 0 10px; border: 1px solid var(--border-ui); border-radius: 4px; color: var(--ink-3); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.btn-ghost { min-height: 34px; padding: 0 10px; border-color: var(--border-ui); border-radius: 4px; background: transparent; color: var(--ink-3); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
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
		letter-spacing: .07em;
		text-transform: uppercase;
	}
	.ep-row {
		display: grid;
		grid-template-columns: 56px minmax(0, 1fr) 36px 36px;
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
	.ep-play { width: 56px; height: 56px; border-radius: 5px; background: var(--bg-tile); }
	.ep-play-icon { background: rgba(5,10,7,.62); }
	.ep-title { color: var(--ink-2); font: 700 14px/1.3 var(--font-ui); }
	.ep-meta { color: var(--ink-4); font: 500 10px/1.4 var(--font-mono); letter-spacing: .03em; text-transform: uppercase; }
	.ep-show { color: var(--ink-3); }
	.ep-mark, .ep-kebab { width: 32px; height: 32px; border: 1px solid var(--border-ui); border-radius: 4px; color: var(--ink-3); }
	.empty-state { margin-top: 16px; border-radius: 8px; box-shadow: none; }
	.menu { border-color: var(--border-ui); border-radius: 5px; background: var(--bg-rail); box-shadow: none; }

	@media (max-width: 560px) {
		.inbox-page { padding: 16px; }
		.head { align-items: flex-start; }
		.head-actions { width: 100%; }
		.ep-row { grid-template-columns: 48px minmax(0,1fr) 36px; gap: 9px; min-height: 68px; }
		.ep-play { width: 48px; height: 48px; }
		.row-menu { display: block; }
		.ep-mark, .ep-kebab, .switch, .btn-ghost { min-width: 44px; min-height: 44px; }
		.day-header span { display: none; }
	}
</style>
