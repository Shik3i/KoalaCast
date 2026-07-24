<script lang="ts">
	import { onMount } from 'svelte';
	import {
		getLocalSubscriptions,
		getCompletedEpisodeIds,
		setSubscriptionInboxMode,
		type LocalSubscription,
		type InboxMode
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { reveal } from '$lib/actions/reveal';
	import Skeleton from '$lib/components/Skeleton.svelte';

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
	let unplayedOnly = $state(false);
	let showSettings = $state(false);
	let isLoading = $state(true);

	onMount(async () => {
		const subs = await getLocalSubscriptions();
		subscriptions = subs;
		modes = Object.fromEntries(subs.map((s) => [s.podcast_id, s.inbox_mode ?? 'all']));
		completed = await getCompletedEpisodeIds();

		const results = await Promise.all(
			subs.map(async (sub) => {
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
			})
		);
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

	async function setMode(podcast_id: string, mode: InboxMode) {
		modes = { ...modes, [podcast_id]: mode };
		await setSubscriptionInboxMode(podcast_id, mode);
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

	function formatDate(sec?: number) {
		if (!sec) return '';
		return new Date(sec * 1000).toLocaleDateString(undefined, {
			month: 'short',
			day: 'numeric'
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

<div class="inbox-page">
	<div class="head">
		<div>
			<h2><i class="ph-fill ph-tray" aria-hidden="true"></i> New</h2>
			<p class="sub">Latest episodes from the shows you follow.</p>
		</div>
		{#if subscriptions.length > 0}
			<div class="head-actions">
				<label class="switch">
					<input type="checkbox" bind:checked={unplayedOnly} />
					<span>Unplayed only</span>
				</label>
				<button class="btn-ghost" class:active={showSettings} onclick={() => (showSettings = !showSettings)}>
					<i class="ph ph-sliders-horizontal" aria-hidden="true"></i> Per-show
				</button>
			</div>
		{/if}
	</div>

	{#if showSettings}
		<section class="settings-panel">
			<p class="panel-hint">
				Choose how much each show contributes. Pick <strong>Only newest</strong> for hourly
				news shows so they don't flood your feed.
			</p>
			<div class="settings-list">
				{#each subscriptions as sub (sub.podcast_id)}
					<div class="settings-row">
						<img src={sub.artwork_url || '/placeholder.svg'} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
						<span class="s-title">{sub.title}</span>
						<div class="seg">
							<button class:active={(modes[sub.podcast_id] ?? 'all') === 'all'} onclick={() => setMode(sub.podcast_id, 'all')}>All</button>
							<button class:active={modes[sub.podcast_id] === 'latest'} onclick={() => setMode(sub.podcast_id, 'latest')}>Only newest</button>
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
			<p>Subscribe to some shows and their newest episodes land here.</p>
			<a href="/search" class="btn">Discover Podcasts</a>
		</div>
	{:else if feed.length === 0}
		<div class="empty-state">
			<i class="ph ph-check-circle" aria-hidden="true"></i>
			<p>{unplayedOnly ? "You're all caught up — nothing unplayed." : 'No recent episodes found.'}</p>
		</div>
	{:else}
		<div class="episode-list">
			{#each feed as ep, i (ep.id)}
				<div class="ep-row" use:reveal={{ delay: Math.min(i * 25, 250) }} class:current={player.current?.episode_id === ep.id}>
					<button class="ep-play" onclick={() => play(ep)} aria-label="Play episode">
						<img src={ep.artwork_url || '/placeholder.svg'} alt="" onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
						<span class="ep-play-icon"><i class="ph-fill ph-play" aria-hidden="true"></i></span>
					</button>
					<div class="ep-body">
						<a class="ep-title" href={`/episode/${ep.id}`}>{ep.title}</a>
						<span class="ep-meta">
							<span class="ep-show">{ep.podcast_title}</span>
							<span class="dot">•</span>{formatDate(ep.pub_date)}
							{#if ep.duration_ms}<span class="dot">•</span>{formatDuration(ep.duration_ms)}{/if}
							{#if completed.has(ep.id)}<span class="played-tag">Played</span>{/if}
						</span>
					</div>
				</div>
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
	.seg button.active { background: var(--accent-green); color: #fff; }

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
		background: var(--bg-elevated);
		color: var(--text-secondary);
		padding: 0.05rem 0.4rem;
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
		color: #fff;
		padding: 0.6rem 1.4rem;
		border-radius: 10px;
		font-weight: 700;
	}
	.btn:hover { text-decoration: none; background: var(--accent-green-hover); }

	@media (max-width: 640px) {
		.settings-row .s-title { font-size: 0.82rem; }
	}
</style>
