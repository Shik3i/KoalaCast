<script lang="ts">
	import { page } from '$app/stores';
	import {
		saveLocalSubscription,
		removeLocalSubscription,
		getLocalSubscriptions,
		getCompletedEpisodeIds,
		setEpisodePlayed
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { dominantColor } from '$lib/color';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import { slide } from 'svelte/transition';

	let podcastId = $state('');
	let podcast = $state<any>(null);
	let episodes = $state<any[]>([]);
	let isLoading = $state(true);
	let isSubscribed = $state(false);
	let showAccent = $state<string | null>(null);
	let playedIds = $state<Set<string>>(new Set());
	let collapsedTiers = $state<Set<string>>(new Set());
	let openMenuId = $state<string | null>(null);

	const DAY = 86400;
	// Recency tiers, newest first. Anything older than a year collapses by default.
	const TIERS = [
		{ key: 'week', label: 'This week', maxAgeDays: 7 },
		{ key: 'month', label: 'This month', maxAgeDays: 30 },
		{ key: 'year', label: 'Earlier this year', maxAgeDays: 365 },
		{ key: 'older', label: 'Older than a year', maxAgeDays: Infinity }
	];

	// Group episodes into recency tiers based on pub_date (unix seconds).
	const groupedEpisodes = $derived.by(() => {
		const nowSec = Date.now() / 1000;
		const buckets: Record<string, any[]> = { week: [], month: [], year: [], older: [], undated: [] };
		for (const ep of episodes) {
			if (!ep.pub_date) {
				buckets.undated.push(ep);
				continue;
			}
			const ageDays = (nowSec - ep.pub_date) / DAY;
			const tier = TIERS.find((t) => ageDays <= t.maxAgeDays) ?? TIERS[TIERS.length - 1];
			buckets[tier.key].push(ep);
		}
		const out = TIERS.map((t) => ({ ...t, episodes: buckets[t.key] })).filter(
			(g) => g.episodes.length > 0
		);
		if (buckets.undated.length > 0) {
			out.push({ key: 'undated', label: 'Undated', maxAgeDays: Infinity, episodes: buckets.undated });
		}
		return out;
	});

	const unplayedCount = $derived(episodes.filter((ep) => !playedIds.has(ep.id)).length);

	const accentVars = $derived(
		showAccent
			? `--show-accent:${showAccent};--show-accent-soft:color-mix(in srgb, ${showAccent} 20%, transparent);`
			: '--show-accent:var(--accent-green);--show-accent-soft:color-mix(in srgb, var(--accent-green) 18%, transparent);'
	);

	$effect(() => {
		podcastId = $page.params.id || '';
		if (podcastId) loadPodcastData(podcastId);
	});

	async function loadPodcastData(id: string) {
		isLoading = true;
		try {
			// Check if id is an iTunes ID or feed URL passed via query param
			const urlParams = new URLSearchParams(window.location.search);
			const feedUrlParam = urlParams.get('feed_url');

			let targetId = id;

			if (feedUrlParam) {
				const addRes = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: feedUrlParam })
				});
				if (addRes.ok) {
					const addData = await addRes.json();
					if (addData.id) targetId = addData.id;
				}
			}

			// Fetch the podcast first: a numeric iTunes ID is resolved & ingested
			// server-side and comes back with its canonical (UUID) id. Episodes are
			// stored under that id, so they must be fetched with podcast.id — not the
			// original numeric id, which would return zero rows.
			const podRes = await fetch(`/api/v1/podcasts/${targetId}`);
			if (podRes.ok) {
				podcast = await podRes.json();
				showAccent = null;
				if (podcast.artwork_url) {
					dominantColor(podcast.artwork_url).then((c) => {
						if (podcast?.artwork_url) showAccent = c;
					});
				}
				const epRes = await fetch(`/api/v1/podcasts/${podcast.id}/episodes`);
				if (epRes.ok) {
					const epData = await epRes.json();
					episodes = epData.episodes || [];
				}
				const subs = await getLocalSubscriptions();
				isSubscribed = subs.some((s) => s.podcast_id === podcast.id);
				playedIds = await getCompletedEpisodeIds();
				// Collapse the "older than a year" tier by default.
				collapsedTiers = new Set(['older']);
			}
		} catch (err) {
			console.error(err);
		} finally {
			isLoading = false;
		}
	}

	async function handleSubscribe() {
		if (!podcast) return;
		if (isSubscribed) {
			await removeLocalSubscription(podcast.id);
			isSubscribed = false;
			toast.success(`Unsubscribed from ${podcast.title}`);
		} else {
			await saveLocalSubscription({
				podcast_id: podcast.id,
				feed_url: podcast.feed_url,
				title: podcast.title,
				artwork_url: podcast.artwork_url,
				added_at: Date.now()
			});
			isSubscribed = true;
			toast.success(`Subscribed to ${podcast.title}`);
		}
	}

	function playLatest() {
		if (episodes.length > 0) playEpisode(episodes[0]);
	}

	function formatDuration(ms: number) {
		if (!ms) return '';
		const totalSec = Math.floor(ms / 1000);
		const h = Math.floor(totalSec / 3600);
		const m = Math.floor((totalSec % 3600) / 60);
		if (h > 0) return `${h}h ${m}m`;
		return `${m}m`;
	}

	function playEpisode(ep: any) {
		if (!podcast) return;
		player.play({
			episode_id: ep.id,
			podcast_id: podcast.id,
			title: ep.title,
			podcast_title: podcast.title,
			artwork_url: ep.artwork_url || podcast.artwork_url || '',
			enclosure_url: ep.enclosure_url,
			duration_ms: ep.duration_ms
		});
	}

	function epMeta(ep: any) {
		return {
			episode_id: ep.id,
			podcast_id: podcast.id,
			title: ep.title,
			podcast_title: podcast.title,
			artwork_url: ep.artwork_url || podcast.artwork_url || '',
			enclosure_url: ep.enclosure_url,
			duration_ms: ep.duration_ms
		};
	}

	async function togglePlayed(ep: any) {
		const played = !playedIds.has(ep.id);
		await setEpisodePlayed(epMeta(ep), played);
		const next = new Set(playedIds);
		if (played) next.add(ep.id);
		else next.delete(ep.id);
		playedIds = next;
	}

	// Mark a batch (a tier, or all episodes) played/unplayed at once.
	async function markManyPlayed(list: any[], played: boolean) {
		await Promise.all(list.map((ep) => setEpisodePlayed(epMeta(ep), played)));
		const next = new Set(playedIds);
		for (const ep of list) {
			if (played) next.add(ep.id);
			else next.delete(ep.id);
		}
		playedIds = next;
		toast.success(
			played
				? `Marked ${list.length} episode${list.length === 1 ? '' : 's'} as played.`
				: `Marked ${list.length} episode${list.length === 1 ? '' : 's'} as unplayed.`
		);
	}

	function toggleTier(key: string) {
		const next = new Set(collapsedTiers);
		if (next.has(key)) next.delete(key);
		else next.add(key);
		collapsedTiers = next;
	}

	// "Mark this and everything older as played/unplayed" — episodes come back
	// newest-first, so this episode plus all following ones are the older set.
	async function markThisAndOlder(ep: any, played: boolean) {
		openMenuId = null;
		const idx = episodes.findIndex((e) => e.id === ep.id);
		if (idx < 0) return;
		await markManyPlayed(episodes.slice(idx), played);
	}
</script>

{#if isLoading}
	<div class="podcast-page">
		<header class="podcast-header">
			<div class="sk-cover"><Skeleton width="100%" height="100%" radius="12px" /></div>
			<div class="meta">
				<Skeleton width="90px" height="1.4rem" radius="20px" />
				<Skeleton width="70%" height="2rem" />
				<Skeleton width="40%" height="1rem" />
				<Skeleton width="100%" height="4rem" />
				<Skeleton width="180px" height="2.6rem" radius="8px" />
			</div>
		</header>
		<div class="episode-list">
			{#each Array(4) as _}
				<div class="episode-row">
					<Skeleton width="48px" height="48px" radius="50%" />
					<div class="ep-info">
						<Skeleton width="60%" height="1.1rem" />
						<Skeleton width="100%" height="0.9rem" />
						<Skeleton width="30%" height="0.8rem" />
					</div>
				</div>
			{/each}
		</div>
	</div>
{:else if podcast}
	<div class="podcast-page" style={accentVars}>
		<!-- Podcast Cover & Meta Header -->
		<header class="podcast-header">
			<img
				src={podcast.artwork_url || '/placeholder.svg'}
				alt={podcast.title}
				class="artwork"
				onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
			/>
			<div class="meta">
				<span class="badge">Podcast Show</span>
				<h2>{podcast.title}</h2>
				<span class="author">By {podcast.author}</span>
				<p class="desc">{podcast.description}</p>

				<div class="actions">
					{#if episodes.length > 0}
						<button class="btn-play-latest" onclick={playLatest}>
							<i class="ph-fill ph-play" aria-hidden="true"></i> Play latest
						</button>
					{/if}
					<button class="btn-subscribe" class:subscribed={isSubscribed} onclick={handleSubscribe}>
						{#if isSubscribed}
							<i class="ph ph-check" aria-hidden="true"></i> Subscribed
						{:else}
							<i class="ph ph-plus" aria-hidden="true"></i> Subscribe
						{/if}
					</button>
				</div>
			</div>
		</header>

		<!-- Episode List, grouped by recency -->
		<section class="episodes-section">
			<div class="episodes-head">
				<h3>Episodes ({episodes.length})</h3>
				{#if episodes.length > 0}
					<div class="ep-head-actions">
						<span class="unplayed-pill">{unplayedCount} unplayed</span>
						{#if unplayedCount > 0}
							<button class="mark-all-btn" onclick={() => markManyPlayed(episodes, true)}>
								<i class="ph ph-checks" aria-hidden="true"></i> Mark all played
							</button>
						{:else}
							<button class="mark-all-btn" onclick={() => markManyPlayed(episodes, false)}>
								<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i> Mark all unplayed
							</button>
						{/if}
					</div>
				{/if}
			</div>

			{#if openMenuId}
				<button class="menu-backdrop" onclick={() => (openMenuId = null)} aria-label="Close menu" tabindex="-1"></button>
			{/if}

			{#each groupedEpisodes as group (group.key)}
				{@const allPlayed = group.episodes.every((e) => playedIds.has(e.id))}
				{@const open = !collapsedTiers.has(group.key)}
				<div class="tier">
					<div class="tier-head">
						<button class="tier-toggle" onclick={() => toggleTier(group.key)} aria-expanded={open}>
							<i class="ph ph-caret-right chev" class:open aria-hidden="true"></i>
							<span class="tier-label">{group.label}</span>
							<span class="tier-count">{group.episodes.length}</span>
						</button>
						<button class="tier-mark" onclick={() => markManyPlayed(group.episodes, !allPlayed)}>
							{allPlayed ? 'Mark unplayed' : 'Mark all played'}
						</button>
					</div>

					{#if open}
						<div class="episode-list" transition:slide={{ duration: 240 }}>
							{#each group.episodes as ep (ep.id)}
								<div class="episode-row" class:current={player.current?.episode_id === ep.id} class:played={playedIds.has(ep.id)}>
									<button class="btn-play" class:playing={player.current?.episode_id === ep.id} onclick={() => playEpisode(ep)} aria-label="Play episode">
										<i class="ph-fill {player.current?.episode_id === ep.id ? 'ph-waveform' : 'ph-play'}" aria-hidden="true"></i>
									</button>

									<div class="ep-info">
										<h4><a href={`/episode/${ep.id}`}>{ep.title}</a></h4>
										<p class="ep-desc">{ep.description ? ep.description.replace(/<[^>]*>?/gm, '').slice(0, 160) + '...' : ''}</p>
										<span class="ep-meta">
											{ep.pub_date ? prefs.formatDate(ep.pub_date) : 'No date'}
											{#if ep.duration_ms}
												• {formatDuration(ep.duration_ms)}
											{/if}
											{#if playedIds.has(ep.id)}<span class="played-tag">Played</span>{/if}
										</span>
									</div>

									<button class="btn-mark" class:done={playedIds.has(ep.id)} onclick={() => togglePlayed(ep)} aria-pressed={playedIds.has(ep.id)} aria-label={playedIds.has(ep.id) ? 'Mark as unplayed' : 'Mark as played'} title={playedIds.has(ep.id) ? 'Mark as unplayed' : 'Mark as played'}>
										<i class="{playedIds.has(ep.id) ? 'ph-fill ph-check-circle' : 'ph ph-circle'}" aria-hidden="true"></i>
									</button>
								<div class="row-menu">
									<button class="btn-kebab" onclick={() => (openMenuId = openMenuId === ep.id ? null : ep.id)} aria-haspopup="menu" aria-expanded={openMenuId === ep.id} aria-label="More actions">
										<i class="ph ph-dots-three-vertical" aria-hidden="true"></i>
									</button>
									{#if openMenuId === ep.id}
										<div class="menu" role="menu">
											<button role="menuitem" onclick={() => markThisAndOlder(ep, true)}>
												<i class="ph ph-arrow-line-down" aria-hidden="true"></i> Mark this &amp; older as played
											</button>
											<button role="menuitem" onclick={() => markThisAndOlder(ep, false)}>
												<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i> Mark this &amp; older as unplayed
											</button>
										</div>
									{/if}
								</div>
								</div>
							{/each}
						</div>
					{/if}
				</div>
			{/each}
		</section>
	</div>
{:else}
	<div class="error-state">
		<i class="ph ph-warning-circle" aria-hidden="true"></i>
		<p>Podcast details not found.</p>
	</div>
{/if}

<style>
	.podcast-page {
		display: flex;
		flex-direction: column;
		gap: 3rem;
	}

	.podcast-header {
		display: flex;
		gap: 2.5rem;
		background:
			radial-gradient(120% 130% at 0% 0%, var(--show-accent-soft, transparent), transparent 58%),
			var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 16px;
		padding: 2.5rem;
		backdrop-filter: blur(16px);
		transition: background 0.5s ease;
	}

	.sk-cover { width: 220px; height: 220px; flex-shrink: 0; }

	.artwork {
		width: 220px;
		height: 220px;
		border-radius: 12px;
		object-fit: cover;
		flex-shrink: 0;
		box-shadow: 0 12px 30px rgba(0, 0, 0, 0.3);
	}

	.meta {
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
		flex: 1;
	}

	.badge {
		background: var(--show-accent, var(--accent-green));
		color: white;
		padding: 0.2rem 0.6rem;
		border-radius: 20px;
		font-size: 0.75rem;
		font-weight: 700;
		text-transform: uppercase;
		width: fit-content;
	}

	.meta h2 {
		font-size: 2.2rem;
		font-weight: 800;
		line-height: 1.2;
	}

	.author {
		font-size: 1rem;
		color: var(--show-accent, var(--accent-green));
		font-weight: 700;
	}

	.desc {
		color: var(--text-secondary);
		font-size: 0.95rem;
		line-height: 1.6;
		display: -webkit-box;
		-webkit-line-clamp: 4;
		line-clamp: 4;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}

	.actions {
		margin-top: 0.75rem;
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
	}

	.btn-play-latest {
		background: var(--show-accent, var(--accent-green));
		color: #fff;
		border: none;
		padding: 0.65rem 1.4rem;
		border-radius: 8px;
		font-weight: 700;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		box-shadow: 0 8px 22px var(--show-accent-soft, transparent);
		transition: transform 0.15s ease, filter 0.2s ease;
	}
	.btn-play-latest:hover { filter: brightness(1.08); transform: translateY(-2px); }

	.btn-subscribe {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
		padding: 0.65rem 1.5rem;
		border-radius: 8px;
		font-weight: 700;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		transition: transform 0.15s ease, border-color 0.2s ease, color 0.2s ease;
	}
	.btn-subscribe:hover { border-color: var(--show-accent, var(--accent-green)); color: var(--show-accent, var(--accent-green)); transform: translateY(-2px); }
	.btn-subscribe.subscribed {
		background: color-mix(in srgb, var(--show-accent, var(--accent-green)) 14%, var(--bg-surface));
		border-color: var(--show-accent, var(--accent-green));
		color: var(--show-accent, var(--accent-green));
	}

	.episodes-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		flex-wrap: wrap;
		margin-bottom: 1.25rem;
	}
	.episodes-section h3 {
		font-size: 1.5rem;
		font-weight: 800;
	}
	.ep-head-actions { display: flex; align-items: center; gap: 0.6rem; }
	.unplayed-pill {
		font-size: 0.78rem;
		font-weight: 700;
		color: var(--text-secondary);
		background: var(--bg-elevated);
		padding: 0.3rem 0.7rem;
		border-radius: 999px;
	}
	.mark-all-btn {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		color: var(--text-secondary);
		padding: 0.45rem 0.9rem;
		border-radius: 10px;
		font-weight: 600;
		font-size: 0.85rem;
	}
	.mark-all-btn:hover { border-color: var(--show-accent, var(--accent-green)); color: var(--show-accent, var(--accent-green)); }

	/* Recency tier */
	.tier { margin-bottom: 1.25rem; }
	.tier-head {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin-bottom: 0.75rem;
	}
	.tier-toggle {
		display: inline-flex;
		align-items: center;
		gap: 0.55rem;
		background: none;
		border: none;
		color: var(--text-primary);
		font-weight: 700;
		font-size: 1rem;
		padding: 0.25rem 0;
	}
	.tier-toggle .chev { transition: transform 0.25s var(--ease-spring, ease); color: var(--text-muted); font-size: 1.1rem; }
	.tier-toggle .chev.open { transform: rotate(90deg); }
	.tier-count {
		font-size: 0.72rem;
		font-weight: 800;
		background: var(--bg-elevated);
		color: var(--text-secondary);
		padding: 0.05rem 0.5rem;
		border-radius: 999px;
	}
	.tier-mark {
		margin-left: auto;
		background: none;
		border: none;
		color: var(--text-muted);
		font-size: 0.8rem;
		font-weight: 600;
		padding: 0.25rem 0.4rem;
		border-radius: 6px;
	}
	.tier-mark:hover { color: var(--show-accent, var(--accent-green)); background: var(--bg-elevated); }

	.episode-list {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.episode-row {
		display: flex;
		align-items: center;
		gap: 1.25rem;
		padding: 1.25rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		transition: transform 0.2s ease;
	}

	.episode-row:hover {
		transform: translateX(4px);
		border-color: var(--show-accent, var(--accent-green));
	}

	.btn-play {
		width: 48px;
		height: 48px;
		border-radius: 50%;
		background: var(--show-accent, var(--accent-green));
		color: white;
		border: none;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.4rem;
		flex-shrink: 0;
		transition: transform 0.2s var(--ease-spring, cubic-bezier(0.16, 1, 0.3, 1)), filter 0.2s ease;
	}
	.btn-play:hover { transform: scale(1.1); filter: brightness(1.08); }
	.btn-play.playing { animation: pulse-ring 1.8s ease-in-out infinite; }

	.episode-row.current { border-color: var(--show-accent, var(--accent-green)); background: color-mix(in srgb, var(--show-accent, var(--accent-green)) 8%, var(--bg-surface)); }

	@keyframes pulse-ring {
		0%, 100% { box-shadow: 0 0 0 0 var(--show-accent-soft, color-mix(in srgb, var(--accent-green) 45%, transparent)); }
		50% { box-shadow: 0 0 0 8px transparent; }
	}

	.ep-info {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
		flex: 1;
	}

	.ep-info h4 {
		font-size: 1.1rem;
		font-weight: 700;
	}

	.ep-desc {
		font-size: 0.88rem;
		color: var(--text-secondary);
	}

	.ep-meta {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		flex-wrap: wrap;
		font-size: 0.8rem;
		color: var(--text-muted);
		font-weight: 600;
	}
	.played-tag {
		background: color-mix(in srgb, var(--show-accent, var(--accent-green)) 16%, transparent);
		color: var(--show-accent, var(--accent-green));
		padding: 0.05rem 0.45rem;
		border-radius: 999px;
		font-size: 0.7rem;
		font-weight: 700;
	}

	/* Dim played episodes so unplayed ones stand out. */
	.episode-row.played { opacity: 0.6; }
	.episode-row.played:hover { opacity: 1; }

	.btn-mark {
		flex-shrink: 0;
		width: 40px;
		height: 40px;
		border-radius: 50%;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		font-size: 1.5rem;
		transition: transform 0.2s var(--ease-spring, ease), color 0.2s ease;
	}
	.btn-mark:hover { color: var(--show-accent, var(--accent-green)); background: var(--bg-elevated); transform: scale(1.05); }
	.btn-mark.done { color: var(--show-accent, var(--accent-green)); }

	.row-menu { position: relative; flex-shrink: 0; }
	.btn-kebab {
		width: 36px;
		height: 40px;
		border: none;
		background: transparent;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		font-size: 1.35rem;
		border-radius: 8px;
	}
	.btn-kebab:hover { color: var(--text-primary); background: var(--bg-elevated); }

	.menu-backdrop {
		position: fixed;
		inset: 0;
		z-index: 40;
		background: transparent;
		border: none;
		cursor: default;
	}
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
		animation: menu-in 0.16s var(--ease-out, ease);
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
	.menu button:hover :global(.ph) { color: var(--show-accent, var(--accent-green)); }

	@keyframes menu-in {
		from { opacity: 0; transform: translateY(-4px); }
		to { opacity: 1; transform: translateY(0); }
	}

	.error-state {
		padding: 5rem 2rem;
		text-align: center;
		color: var(--text-muted);
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1rem;
	}
</style>
