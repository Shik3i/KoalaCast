<script lang="ts">
	import { t, type MessageKey } from '$lib/i18n';
	import { page } from '$app/stores';
	import {
		saveLocalSubscription,
		removeLocalSubscription,
		getLocalSubscriptions,
		getCompletedEpisodeIds,
		getAllLocalPlaybackStates,
		getLocalListeningSessions,
		setEpisodePlayed
	} from '$lib/idb/db';
	import { player } from '$lib/stores/player.svelte';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { dominantColor } from '$lib/color';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import { slide } from 'svelte/transition';
	import { optimizeArtwork } from '$lib/artwork';
	import {
		getPodcastPlaybackSettings,
		savePodcastPlaybackSettings,
		type PodcastPlaybackSettings
	} from '$lib/stores/podcast-settings';

	let podcastId = $state('');
	let podcast = $state<any>(null);
	let episodes = $state<any[]>([]);
	let isLoading = $state(true);
	let isSubscribed = $state(false);
	let showAccent = $state<string | null>(null);
	let playedIds = $state<Set<string>>(new Set());
	let collapsedTiers = $state<Set<string>>(new Set());
	let openMenuId = $state<string | null>(null);
	let searchQuery = $state('');
	let filterUnplayedOnly = $state(false);
	let showStats = $state({ listenedMs: 0, finished: 0, episodes: 0, averageSpeed: 1 });
	let showSettings = $state<PodcastPlaybackSettings>(getPodcastPlaybackSettings(''));

	const filteredEpisodes = $derived.by(() => {
		let list = episodes;
		if (filterUnplayedOnly) {
			list = list.filter((ep) => !playedIds.has(ep.id));
		}
		const q = searchQuery.trim().toLowerCase();
		if (q) {
			list = list.filter(
				(ep) =>
					(ep.title && ep.title.toLowerCase().includes(q)) ||
					(ep.description && ep.description.toLowerCase().includes(q)) ||
					(ep.episode_number && String(ep.episode_number).includes(q))
			);
		}
		return list;
	});

	const DAY = 86400;
	// Recency tiers, newest first. Anything older than a year collapses by default.
	const TIERS = [
		{ key: 'week', labelKey: 'podcast.tierWeek', maxAgeDays: 7 },
		{ key: 'month', labelKey: 'podcast.tierMonth', maxAgeDays: 30 },
		{ key: 'year', labelKey: 'podcast.tierYear', maxAgeDays: 365 },
		{ key: 'older', labelKey: 'podcast.tierOlder', maxAgeDays: Infinity }
	];

	// Group episodes into recency tiers based on pub_date (unix seconds).
	const groupedEpisodes = $derived.by(() => {
		const nowSec = Date.now() / 1000;
		const buckets: Record<string, any[]> = { week: [], month: [], year: [], older: [], undated: [] };
		for (const ep of filteredEpisodes) {
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
			out.push({ key: 'undated', labelKey: 'podcast.undated', maxAgeDays: Infinity, episodes: buckets.undated });
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

	// Monotonic id so a slow load for a previous podcast can't overwrite the one the
	// user navigated to (this $effect re-runs on every route param change).
	let loadReqId = 0;

	async function loadPodcastData(id: string) {
		const reqId = ++loadReqId;
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
				const podData = await podRes.json();
				if (reqId !== loadReqId) return; // superseded by a newer navigation
				podcast = podData;
				showAccent = null;
				if (podcast.artwork_url) {
					dominantColor(podcast.artwork_url).then((c) => {
						if (reqId === loadReqId && podcast?.artwork_url) showAccent = c;
					});
				}
				const epRes = await fetch(`/api/v1/podcasts/${podcast.id}/episodes`);
				if (epRes.ok) {
					const epData = await epRes.json();
					if (reqId !== loadReqId) return;
					episodes = epData.episodes || [];
				}
				const subs = await getLocalSubscriptions();
				if (reqId !== loadReqId) return;
				isSubscribed = subs.some((s) => s.podcast_id === podcast.id);
				playedIds = await getCompletedEpisodeIds();
				await loadShowControls(podcast.id);
				const newest = episodes.find((episode) => episode.enclosure_url && !playedIds.has(episode.id));
				if (showSettings.autoQueueNew && newest) {
					await player.addToQueue({
						episode_id: newest.id,
						podcast_id: podcast.id,
						title: newest.title,
						podcast_title: podcast.title,
						artwork_url: newest.artwork_url || podcast.artwork_url || '',
						enclosure_url: newest.enclosure_url,
						duration_ms: newest.duration_ms || 0,
						categories: podcast.categories || (podcast.category ? [podcast.category] : [])
					});
				}
				// Collapse the "older than a year" tier by default.
				collapsedTiers = new Set(['older']);
			}
		} catch (err) {
			console.error(err);
		} finally {
			if (reqId === loadReqId) isLoading = false;
		}
	}

	async function loadShowControls(id: string) {
		showSettings = getPodcastPlaybackSettings(id);
		const [sessions, states] = await Promise.all([
			getLocalListeningSessions(),
			getAllLocalPlaybackStates()
		]);
		const showSessions = sessions.filter((session) => session.podcast_id === id);
		const showStates = states.filter((state) => state.podcast_id === id);
		const wallMs = showSessions.reduce((sum, session) => sum + session.wall_clock_ms, 0);
		const weighted = showSessions.reduce((sum, session) => sum + session.speed_weighted_ms, 0);
		showStats = {
			listenedMs: wallMs,
			finished: showStates.filter((state) => state.completed).length,
			episodes: showStates.length,
			averageSpeed: wallMs ? weighted / wallMs : 1
		};
	}

	function updateShowSetting(patch: Partial<PodcastPlaybackSettings>) {
		if (!podcast?.id) return;
		showSettings = { ...showSettings, ...patch };
		savePodcastPlaybackSettings(podcast.id, showSettings);
	}

	async function handleSubscribe() {
		if (!podcast) return;
		if (isSubscribed) {
			await removeLocalSubscription(podcast.id);
			isSubscribed = false;
			toast.success(t('toast.unsubscribedFrom', { title: podcast.title }));
		} else {
			await saveLocalSubscription({
				podcast_id: podcast.id,
				feed_url: podcast.feed_url,
				title: podcast.title,
				artwork_url: podcast.artwork_url,
				added_at: Date.now()
			});
			isSubscribed = true;
			toast.success(t('toast.subscribed', { title: podcast.title }));
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

	// Plain-text episode blurb; only ellipsize when actually truncated.
	function epDescription(ep: any): string {
		if (!ep.description) return '';
		const text = ep.description.replace(/<[^>]*>?/gm, '').trim();
		return text.length > 160 ? text.slice(0, 160).trimEnd() + '…' : text;
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
			duration_ms: ep.duration_ms,
			categories: podcast.categories || (podcast.category ? [podcast.category] : [])
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
			duration_ms: ep.duration_ms,
			categories: podcast.categories || (podcast.category ? [podcast.category] : [])
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
		toast.success(t(played ? 'inbox.markedPlayed' : 'inbox.markedUnplayed', { count: list.length }));
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

<svelte:window onkeydown={(e) => e.key === 'Escape' && (openMenuId = null)} />

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
				src={optimizeArtwork(podcast.artwork_url, 300)}
				alt={podcast.title}
				class="artwork"
				loading="eager"
				fetchpriority="high"
				decoding="async"
				onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
			/>
			<div class="meta">
				<span class="badge">{t('podcast.episodeCount', { count: episodes.length })}</span>
				<h2>{podcast.title}</h2>
				<span class="author">{t('podcast.byAuthor', { author: podcast.author })}</span>
				<p class="desc">{podcast.description}</p>

				<div class="actions">
					{#if episodes.length > 0}
						<button class="btn-play-latest" onclick={playLatest}>
							<i class="ph-fill ph-play" aria-hidden="true"></i> {t('podcast.playLatest')}
						</button>
					{/if}
					<button class="btn-subscribe" class:subscribed={isSubscribed} onclick={handleSubscribe}>
						{#if isSubscribed}
							<i class="ph ph-check" aria-hidden="true"></i> {t('common.subscribed')}
						{:else}
							<i class="ph ph-plus" aria-hidden="true"></i> {t('common.subscribe')}
						{/if}
					</button>
					{#if podcast.funding_url}
						<a class="btn-funding" href={podcast.funding_url} target="_blank" rel="noopener noreferrer">
							<i class="ph-fill ph-heart" aria-hidden="true"></i> {podcast.funding_text || t('podcast.supportShow')}
						</a>
					{/if}
					{#if podcast.value_recipient}
						<span class="v4v-badge" title={`Lightning Keysend: ${podcast.value_recipient}`}>
							<i class="ph-fill ph-lightning" aria-hidden="true"></i> Value4Value
						</span>
					{/if}
					{#if podcast.is_live}
						<a class="live-badge" href={podcast.live_url || '#'} target="_blank" rel="noopener noreferrer">
							<i class="ph-fill ph-broadcast" aria-hidden="true"></i> {t('podcast.liveNow')}
						</a>
					{/if}
				</div>
			</div>
		</header>

		<section class="show-controls" aria-labelledby="show-controls-title">
			<div class="show-history">
				<p class="control-eyebrow">{t('quiet.show.history')}</p>
				<strong>{formatDuration(showStats.listenedMs) || '0m'}</strong>
				<span>{t('quiet.show.summary', {
					finished: showStats.finished,
					episodes: showStats.episodes,
					speed: showStats.averageSpeed.toFixed(2)
				})}</span>
			</div>
			<div class="show-settings">
				<p class="control-eyebrow" id="show-controls-title">{t('quiet.show.settings')}</p>
				<label>
					<span>{t('quiet.show.skipIntro')}</span>
					<input type="number" min="0" max="600" value={showSettings.skipIntroSeconds} onchange={(event) => updateShowSetting({ skipIntroSeconds: Number(event.currentTarget.value) })} />
					<small>{t('quiet.show.seconds')}</small>
				</label>
				<label>
					<span>{t('quiet.show.skipOutro')}</span>
					<input type="number" min="0" max="600" value={showSettings.skipOutroSeconds} onchange={(event) => updateShowSetting({ skipOutroSeconds: Number(event.currentTarget.value) })} />
					<small>{t('quiet.show.seconds')}</small>
				</label>
				<label>
					<span>{t('quiet.show.speed')}</span>
					<select value={showSettings.speed ?? ''} onchange={(event) => updateShowSetting({ speed: event.currentTarget.value ? Number(event.currentTarget.value) : null })}>
						<option value="">{t('quiet.show.globalSpeed')}</option>
						{#each [1, 1.1, 1.2, 1.25, 1.3, 1.5, 1.75, 2] as speed}<option value={speed}>{speed}×</option>{/each}
					</select>
				</label>
				<label class="auto-queue">
					<input type="checkbox" checked={showSettings.autoQueueNew} onchange={(event) => updateShowSetting({ autoQueueNew: event.currentTarget.checked })} />
					<span>{t('quiet.show.autoQueue')}</span>
				</label>
			</div>
		</section>

		<!-- Episode List, grouped by recency -->
		<section class="episodes-section">
			<div class="episodes-head">
				<h3>{filteredEpisodes.length === episodes.length ? t('podcast.episodesHeading', { count: episodes.length }) : t('podcast.episodesFilteredHeading', { shown: filteredEpisodes.length, total: episodes.length })}</h3>
				{#if episodes.length > 0}
					<div class="ep-head-actions">
						<span class="unplayed-pill">{t('podcast.unplayedCount', { count: unplayedCount })}</span>
						{#if unplayedCount > 0}
							<button class="mark-all-btn" onclick={() => markManyPlayed(episodes, true)}>
								<i class="ph ph-checks" aria-hidden="true"></i> {t('podcast.markAllPlayed')}
							</button>
						{:else}
							<button class="mark-all-btn" onclick={() => markManyPlayed(episodes, false)}>
								<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i> {t('podcast.markAllUnplayed')}
							</button>
						{/if}
					</div>
				{/if}
			</div>

			{#if episodes.length > 0}
				<div class="episodes-filter-bar">
					<div class="ep-search-input">
						<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
						<input
							type="text"
							placeholder={t('podcast.searchEpisodes')}
							bind:value={searchQuery}
							aria-label={t('podcast.filterEpisodes')}
						/>
						{#if searchQuery}
							<button class="clear-btn" onclick={() => (searchQuery = '')} aria-label={t('common.clearSearch')} title={t('common.clearSearch')}>
								<i class="ph ph-x" aria-hidden="true"></i>
							</button>
						{/if}
					</div>
					<button
						class="filter-pill"
						class:active={filterUnplayedOnly}
						onclick={() => (filterUnplayedOnly = !filterUnplayedOnly)}
					>
						<i class="ph {filterUnplayedOnly ? 'ph-check-circle-fill' : 'ph-circle'}" aria-hidden="true"></i>
						{t('podcast.unplayedFilter', { count: unplayedCount })}
					</button>
				</div>
			{/if}

			{#if filteredEpisodes.length === 0 && episodes.length > 0}
				<div class="no-episodes-found">
					<i class="ph ph-magnifying-glass lead-icon" aria-hidden="true"></i>
					<p>{t('podcast.noEpisodesMatch')}</p>
					<button class="btn-reset-filter" onclick={() => { searchQuery = ''; filterUnplayedOnly = false; }}>
						{t('podcast.clearSearchFilter')}
					</button>
				</div>
			{/if}

			{#if openMenuId}
				<button class="menu-backdrop" onclick={() => (openMenuId = null)} aria-label={t('common.closeMenu')} tabindex="-1"></button>
			{/if}

			{#each groupedEpisodes as group (group.key)}
				{@const allPlayed = group.episodes.every((e) => playedIds.has(e.id))}
				{@const open = !collapsedTiers.has(group.key)}
				<div class="tier">
					<div class="tier-head">
						<button class="tier-toggle" onclick={() => toggleTier(group.key)} aria-expanded={open}>
							<i class="ph ph-caret-right chev" class:open aria-hidden="true"></i>
							<span class="tier-label">{t(group.labelKey as MessageKey)}</span>
							<span class="tier-count">{group.episodes.length}</span>
						</button>
						<button class="tier-mark" onclick={() => markManyPlayed(group.episodes, !allPlayed)}>
							{allPlayed ? t('podcast.markUnplayed') : t('podcast.markAllPlayed')}
						</button>
					</div>

					{#if open}
						<div class="episode-list" transition:slide={{ duration: 240 }}>
							{#each group.episodes as ep (ep.id)}
								<div class="episode-row" class:current={player.current?.episode_id === ep.id} class:played={playedIds.has(ep.id)}>
									<button class="btn-play" class:playing={player.current?.episode_id === ep.id} onclick={() => playEpisode(ep)} aria-label={t('podcast.playEpisode')} title={t('podcast.playEpisode')}>
										<i class="ph-fill {player.current?.episode_id === ep.id ? 'ph-waveform' : 'ph-play'}" aria-hidden="true"></i>
									</button>

									<div class="ep-info">
										<h4><a href={`/episode/${ep.id}`} title={ep.title}>{ep.title}</a></h4>
										<p class="ep-desc">{epDescription(ep)}</p>
										<span class="ep-meta">
											{ep.pub_date ? prefs.formatDate(ep.pub_date) : t('podcast.noDate')}
											{#if ep.duration_ms}
												• {formatDuration(ep.duration_ms)}
											{/if}
											{#if playedIds.has(ep.id)}<span class="played-tag">{t('podcast.played')}</span>{/if}
										</span>
									</div>

									<button class="btn-mark" class:done={playedIds.has(ep.id)} onclick={() => togglePlayed(ep)} aria-pressed={playedIds.has(ep.id)} aria-label={playedIds.has(ep.id) ? t('common.markUnplayed') : t('common.markPlayed')} title={playedIds.has(ep.id) ? t('common.markUnplayed') : t('common.markPlayed')}>
										<i class="{playedIds.has(ep.id) ? 'ph-fill ph-check-circle' : 'ph ph-circle'}" aria-hidden="true"></i>
									</button>
								<div class="row-menu">
									<button class="btn-kebab" onclick={() => (openMenuId = openMenuId === ep.id ? null : ep.id)} aria-haspopup="menu" aria-expanded={openMenuId === ep.id} aria-label={t('podcast.moreActions')} title={t('podcast.moreActions')}>
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
						</div>
					{/if}
				</div>
			{/each}
		</section>
	</div>
{:else}
	<div class="error-state">
		<i class="ph ph-warning-circle" aria-hidden="true"></i>
		<p>{t('podcast.notFound')}</p>
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

	.show-controls {
		display: grid;
		grid-template-columns: minmax(180px, .55fr) minmax(420px, 1.45fr);
		gap: 0;
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		background: var(--bg-surface);
		overflow: hidden;
	}
	.show-history, .show-settings { padding: 1rem 1.25rem; }
	.show-history {
		display: flex;
		flex-direction: column;
		gap: .35rem;
		border-right: 1px solid var(--border-subtle);
	}
	.control-eyebrow {
		color: var(--text-muted);
		font: 700 .68rem/1.3 var(--font-mono);
		letter-spacing: .1em;
		text-transform: uppercase;
	}
	.show-history strong { font: 800 1.8rem/1 var(--font-ui); letter-spacing: -.04em; }
	.show-history span { color: var(--text-secondary); font-size: .78rem; }
	.show-settings {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: .75rem;
		align-items: end;
	}
	.show-settings .control-eyebrow { grid-column: 1 / -1; }
	.show-settings label {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto auto;
		align-items: center;
		gap: .35rem;
		color: var(--text-secondary);
		font-size: .78rem;
	}
	.show-settings input[type='number'], .show-settings select {
		width: 62px;
		height: 32px;
		padding: 0 .45rem;
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		background: var(--bg-elevated);
		color: var(--text-primary);
	}
	.show-settings select { width: 88px; }
	.show-settings small { color: var(--text-muted); }
	.show-settings .auto-queue {
		display: flex;
		align-items: center;
		align-self: center;
	}
	.show-settings .auto-queue input { accent-color: var(--show-accent, var(--accent-green)); }

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

	.btn-funding {
		background: color-mix(in srgb, #ff4757 15%, var(--bg-surface));
		color: #ff4757;
		border: 1px solid color-mix(in srgb, #ff4757 40%, transparent);
		padding: 0.65rem 1.2rem;
		border-radius: 8px;
		font-weight: 700;
		font-size: 0.95rem;
		display: inline-flex;
		align-items: center;
		gap: 0.45rem;
		text-decoration: none;
		transition: transform 0.15s ease, filter 0.2s ease;
	}
	.btn-funding:hover { filter: brightness(1.1); transform: translateY(-2px); text-decoration: none; }

	.v4v-badge {
		background: color-mix(in srgb, #ffa500 15%, var(--bg-surface));
		color: #ffa500;
		border: 1px solid color-mix(in srgb, #ffa500 40%, transparent);
		padding: 0.65rem 1rem;
		border-radius: 8px;
		font-weight: 700;
		font-size: 0.9rem;
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
	}

	.live-badge {
		background: #ff0055;
		color: #fff;
		padding: 0.65rem 1.2rem;
		border-radius: 8px;
		font-weight: 800;
		font-size: 0.9rem;
		display: inline-flex;
		align-items: center;
		gap: 0.45rem;
		text-decoration: none;
		animation: pulse-live 1.8s ease-in-out infinite;
	}
	@keyframes pulse-live {
		0%, 100% { box-shadow: 0 0 0 0 rgba(255, 0, 85, 0.4); }
		50% { box-shadow: 0 0 0 8px transparent; }
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
	.btn-play i { display: block; font-size: 1.35rem; line-height: 1; }
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

	/* In-Page Episode Search & Filter Bar */
	.episodes-filter-bar {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		margin-bottom: 1.25rem;
		flex-wrap: wrap;
	}
	.ep-search-input {
		flex: 1;
		min-width: 220px;
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		padding: 0.4rem 0.8rem;
		transition: border-color 0.2s ease, box-shadow 0.2s ease;
	}
	.ep-search-input:focus-within {
		border-color: var(--show-accent, var(--accent-green));
		box-shadow: 0 0 0 3px color-mix(in srgb, var(--show-accent, var(--accent-green)) 20%, transparent);
	}
	.ep-search-input input {
		flex: 1;
		border: none;
		background: none;
		outline: none;
		color: var(--text-primary);
		font-size: 0.9rem;
	}
	.ep-search-input .clear-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		font-size: 1rem;
		display: grid;
		place-items: center;
		padding: 0.2rem;
		border-radius: 50%;
	}
	.ep-search-input .clear-btn:hover { color: var(--text-primary); background: var(--bg-elevated); }

	.filter-pill {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		padding: 0.45rem 0.85rem;
		border-radius: 999px;
		border: 1px solid var(--border-subtle);
		background: var(--bg-surface);
		color: var(--text-secondary);
		font-size: 0.85rem;
		font-weight: 600;
		transition: all 0.2s ease;
	}
	.filter-pill:hover { border-color: var(--show-accent, var(--accent-green)); color: var(--text-primary); }
	.filter-pill.active {
		background: var(--show-accent-soft, color-mix(in srgb, var(--accent-green) 18%, transparent));
		border-color: var(--show-accent, var(--accent-green));
		color: var(--show-accent, var(--accent-green));
	}

	.no-episodes-found {
		padding: 3rem 1.5rem;
		text-align: center;
		background: var(--bg-surface);
		border: 1px dashed var(--border-subtle);
		border-radius: 12px;
		margin: 1rem 0 2rem;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.75rem;
	}
	.no-episodes-found .lead-icon { font-size: 2.2rem; color: var(--text-muted); }
	.btn-reset-filter {
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		color: var(--text-primary);
		padding: 0.45rem 1rem;
		border-radius: 8px;
		font-size: 0.85rem;
		font-weight: 600;
	}
	.btn-reset-filter:hover { border-color: var(--show-accent, var(--accent-green)); color: var(--show-accent, var(--accent-green)); }

	/* Quiet Edition 4b show screen */
	.podcast-page { gap: 0; }
	.podcast-header {
		gap: 22px;
		padding: 24px 22px;
		border: 0;
		border-bottom: 1px solid var(--border-hair);
		border-radius: 0;
		background: radial-gradient(85% 150% at 0 0, var(--show-accent-soft, var(--accent-wash)), transparent 62%), var(--bg-panel);
		backdrop-filter: none;
	}
	.sk-cover, .artwork { width: 150px; height: 150px; border-radius: 6px; box-shadow: none; background: var(--bg-tile); }
	.meta { gap: 7px; justify-content: center; min-width: 0; }
	.badge { width: fit-content; padding: 5px 7px; border-radius: 3px; background: var(--accent-fill); color: var(--accent-on); font: 600 10px/1 var(--font-mono); letter-spacing: .06em; text-transform: uppercase; }
	.meta h2 { font: 700 clamp(30px,4vw,40px)/.95 var(--font-display); font-stretch: condensed; letter-spacing: -.045em; text-transform: uppercase; }
	.author { color: var(--ink-3); font: 600 11px/1.3 var(--font-sans); }
	.desc { max-width: 70ch; color: var(--ink-3); font-size: 12px; line-height: 1.45; }
	.actions { gap: 6px; margin-top: 4px; }
	.btn-play-latest, .btn-subscribe, .btn-funding {
		min-height: 34px;
		padding: 0 10px;
		border-radius: 5px;
		box-shadow: none;
		font-size: 10px;
	}
	.btn-play-latest { background: var(--accent-fill); color: var(--accent-on); }
	.btn-subscribe { border-color: var(--border-ui); color: var(--ink-3); }
	.show-controls {
		border-width: 0 0 1px;
		border-radius: 0;
		background: var(--bg-panel);
	}
	.episodes-section { padding: 18px 22px 30px; }
	.episodes-head { padding-bottom: 11px; border-bottom: 1px solid var(--border-hair); }
	.episodes-section h3 { font: 800 17px/1 var(--font-ui); }
	.unplayed-pill, .mark-all-btn { min-height: 32px; border-radius: 4px; font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.mark-all-btn { border-color: var(--border-ui); background: transparent; }
	.episodes-filter-bar { padding: 10px 0; border-bottom: 1px solid var(--border-hair); }
	.ep-search-input { border-color: var(--border-ui); border-radius: 5px; background: var(--bg-sunken); }
	.filter-pill { min-height: 32px; border-color: var(--border-ui); border-radius: 4px; background: transparent; font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.tier { margin: 0; }
	.tier-head { min-height: 38px; padding: 0 5px; background: var(--bg-sunken); border-bottom: 1px solid var(--border-hair); }
	.tier-toggle, .tier-mark { min-height: 32px; color: var(--ink-4); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.episode-list { gap: 0; }
	.episode-row {
		min-height: 76px;
		padding: 10px 5px;
		border: 0;
		border-bottom: 1px solid var(--border-row);
		border-radius: 0;
		background: transparent;
		box-shadow: none;
	}
	.episode-row:hover { transform: none; border-color: var(--border-row); background: var(--bg-sunken); }
	.episode-row.current { border-color: var(--border-row); background: linear-gradient(90deg, var(--accent-wash), transparent); }
	.btn-play { width: 34px; height: 34px; background: var(--accent-fill); color: var(--accent-on); box-shadow: none; }
	.ep-info h4 { font: 700 14px/1.3 var(--font-ui); }
	.ep-desc { color: var(--ink-3); font-size: 10px; }
	.ep-meta { color: var(--ink-4); font: 500 10px/1.4 var(--font-mono); text-transform: uppercase; }
	.btn-mark, .btn-kebab { width: 32px; height: 32px; border: 1px solid var(--border-ui); border-radius: 4px; }
	.menu { border-color: var(--border-ui); border-radius: 5px; background: var(--bg-rail); box-shadow: none; }

	@media (max-width: 900px) {
		.show-controls { grid-template-columns: 1fr; }
		.show-history { border-right: 0; border-bottom: 1px solid var(--border-subtle); }
		.show-settings { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	}

	@media (max-width: 620px) {
		.podcast-header { align-items: flex-start; flex-direction: column; padding: 16px; }
		.sk-cover, .artwork { width: 100%; height: auto; aspect-ratio: 1; }
		.meta h2 { font-size: 32px; }
		.show-history, .show-settings { padding: 14px 16px; }
		.show-settings { grid-template-columns: 1fr; }
		.episodes-section { padding: 16px; }
		.episode-row { gap: 9px; }
		.ep-desc { display: none; }
	}
</style>
