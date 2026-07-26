<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { FEATURED_PODCASTS } from '$lib/data/featured';
	import { saveLocalSubscription, getLocalSubscriptions, addLocalFavorite } from '$lib/idb/db';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import Onboarding from '$lib/components/Onboarding.svelte';
	import Skeleton from '$lib/components/Skeleton.svelte';
	import { GENRES, genreLabel } from '$lib/genres';
	import { optimizeArtwork } from '$lib/artwork';
	import { detectBrowserLanguages, regionForLanguage } from '$lib/data/languages';
	import { t } from '$lib/i18n';
	import { listeningSession, type SessionMinutes } from '$lib/stores/session.svelte';
	import { player, type CurrentTrack } from '$lib/stores/player.svelte';
	import {
		arrangeDiscover,
		formatEpisodeMinutes,
		type DiscoverMood,
		type DiscoverSort
	} from '$lib/discover/home';

	interface PodcastItem {
		id: string;
		title: string;
		author: string;
		feed_url: string;
		artwork_url: string;
		category?: string;
		categories?: string[];
		description?: string;
		latestDurationMs?: number;
		latestPublishedAt?: number;
		sourceRank?: number;
	}

	interface LatestTrack extends CurrentTrack {
		pub_date?: number;
	}

	const PAGE_SIZE = 60;
	const moods = [
		{ id: 'calm' as const, labelKey: 'quiet.discover.moodCalm' as const, effectKey: 'quiet.discover.moodEffectCalm' as const, icon: 'ph-waves' },
		{ id: 'curious' as const, labelKey: 'quiet.discover.moodCurious' as const, effectKey: 'quiet.discover.moodEffectCurious' as const, icon: 'ph-lightbulb' },
		{ id: 'company' as const, labelKey: 'quiet.discover.moodCompany' as const, effectKey: 'quiet.discover.moodEffectCompany' as const, icon: 'ph-users-three' },
		{ id: 'focus' as const, labelKey: 'quiet.discover.moodFocus' as const, effectKey: 'quiet.discover.moodEffectFocus' as const, icon: 'ph-crosshair' }
	];

	let mounted = $state(false);
	let podcasts = $state<PodcastItem[]>([]);
	let subscribedIds = $state<string[]>([]);
	let subscribedFeeds = $state<string[]>([]);
	let searchQuery = $state('');
	let selectedCategory = $state('All');
	let selectedMood = $state<DiscoverMood>('calm');
	let sort = $state<DiscoverSort>('momentum');
	let fitsSession = $state(false);
	let isHydratingMetadata = $state(false);
	let isLoading = $state(true);
	let isLoadingMore = $state(false);
	let limit = $state(PAGE_SIZE);
	let visibleChartCount = $state(12);
	let reachedEnd = $state(false);
	let searchInput: HTMLInputElement;
	let requestId = 0;
	const metadataRequests = new Map<string, Promise<void>>();

	const categories = ['All', ...GENRES.map((genre) => genre.name)];
	const filtered = $derived(
		podcasts.filter((pod) => {
			if (prefs.isHidden(pod.categories)) return false;
			return true;
		})
	);
	const arranged = $derived(
		arrangeDiscover(filtered, {
			mood: selectedMood,
			sort,
			sessionMinutes: listeningSession.minutes,
			fitsSession
		}) as PodcastItem[]
	);
	const spotlight = $derived(arranged[0] ?? null);
	const picks = $derived(arranged.slice(1, 4));
	const chart = $derived(arranged.slice(4, 4 + visibleChartCount));
	const hasMoreResults = $derived(chart.length < Math.max(0, arranged.length - 4) || !reachedEnd);
	const selectedMoodLabel = $derived(t(moods.find((mood) => mood.id === selectedMood)?.labelKey ?? 'quiet.discover.moodCalm'));

	onMount(async () => {
		mounted = true;
		// Render the stable editorial fallback before the first asynchronous read.
		// This keeps the hero and mood grid in their final positions from frame one.
		podcasts = FEATURED_PODCASTS;
		isLoading = false;
		listeningSession.load();
		const subscriptions = await getLocalSubscriptions();
		subscribedIds = subscriptions.map((sub) => sub.podcast_id);
		subscribedFeeds = subscriptions.map((sub) => sub.feed_url).filter(Boolean);
		await loadDiscover(true);
	});

	async function loadDiscover(background = false) {
		const id = ++requestId;
		if (!background) isLoading = true;
		const languages = prefs.languages.length ? prefs.languages : detectBrowserLanguages();
		try {
			const responses = await Promise.allSettled(
				languages.map(async (language) => {
					const controller = new AbortController();
					const timeout = background ? window.setTimeout(() => controller.abort(), 2500) : 0;
					const params = new URLSearchParams({
						limit: String(limit),
						region: regionForLanguage(language),
						languages: language
					});
					if (selectedCategory !== 'All') params.set('category', selectedCategory);
					try {
						const response = await fetch(`/api/v1/podcasts/discover?${params}`, { signal: controller.signal });
						return response.ok ? response.json() : { results: [] };
					} finally {
						if (timeout) window.clearTimeout(timeout);
					}
				})
			);
			if (id !== requestId) return;
			const lists = responses
				.filter((result): result is PromiseFulfilledResult<any> => result.status === 'fulfilled')
				.map((result) => result.value?.results ?? []);
			const merged: PodcastItem[] = [];
			const seen = new Set<string>();
			const longest = Math.max(0, ...lists.map((list) => list.length));
			for (let index = 0; index < longest; index += 1) {
				for (const list of lists) {
					const item = list[index];
					const key = item?.feed_url || item?.id;
					if (item && key && !seen.has(key)) {
						seen.add(key);
						merged.push(item);
					}
				}
			}
			const safeFallback = languages.includes('en') ? FEATURED_PODCASTS : [];
			podcasts = (merged.length ? merged : safeFallback).map((podcast, index) => ({
				...podcast,
				sourceRank: index
			}));
			reachedEnd = merged.length < limit;
		} catch {
			podcasts = languages.includes('en') ? FEATURED_PODCASTS : [];
			reachedEnd = true;
		} finally {
			if (id === requestId) {
				isLoading = false;
				isLoadingMore = false;
			}
		}
	}

	async function selectCategory(category: string) {
		if (category === selectedCategory) return;
		selectedCategory = category;
		limit = PAGE_SIZE;
		visibleChartCount = 12;
		await loadDiscover();
	}

	async function loadMore() {
		if (isLoadingMore) return;
		visibleChartCount += 12;
		if (visibleChartCount <= Math.max(0, arranged.length - 4) || reachedEnd) {
			if (fitsSession || sort === 'length' || sort === 'newest') await hydrateVisibleMetadata();
			return;
		}
		isLoadingMore = true;
		limit += PAGE_SIZE;
		await loadDiscover();
		if (fitsSession || sort === 'length' || sort === 'newest') await hydrateVisibleMetadata();
	}

	function isSubscribed(podcast: PodcastItem) {
		return subscribedIds.includes(podcast.id) || (!!podcast.feed_url && subscribedFeeds.includes(podcast.feed_url));
	}

	async function resolvePodcastId(podcast: PodcastItem): Promise<string | null> {
		if (podcast.feed_url) {
			try {
				const response = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: podcast.feed_url })
				});
				if (response.ok) {
					const resolved = await response.json();
					if (resolved.id) return resolved.id as string;
				}
				return null;
			} catch {
				return null;
			}
		}
		return podcast.id;
	}

	async function openPodcast(podcast: PodcastItem) {
		const id = await resolvePodcastId(podcast);
		if (!id) {
			toast.error(t('discover.openError'));
			return;
		}
		goto(`/podcast/${id}?feed_url=${encodeURIComponent(podcast.feed_url || '')}`);
	}

	function openGlobalSearch() {
		const query = searchQuery.trim();
		goto(query ? `/search?q=${encodeURIComponent(query)}` : '/search');
	}

	async function latestTrack(podcast: PodcastItem): Promise<LatestTrack | null> {
		const id = await resolvePodcastId(podcast);
		if (!id) return null;
		try {
			const response = await fetch(`/api/v1/podcasts/${id}/episodes`);
			if (!response.ok) return null;
			const data = await response.json();
			const episode = (data.episodes || []).find((item: any) => item.enclosure_url);
			if (!episode) return null;
			return {
				episode_id: episode.id,
				podcast_id: id,
				title: episode.title,
				podcast_title: podcast.title,
				artwork_url: episode.artwork_url || podcast.artwork_url || '',
				enclosure_url: episode.enclosure_url,
				duration_ms: episode.duration_ms || 0,
				categories: podcast.categories || (podcast.category ? [podcast.category] : []),
				pub_date: episode.pub_date || 0
			};
		} catch {
			return null;
		}
	}

	function podcastKey(podcast: PodcastItem) {
		return podcast.feed_url || podcast.id;
	}

	async function hydrateMetadata(podcast: PodcastItem) {
		if (podcast.latestDurationMs !== undefined || podcast.latestPublishedAt !== undefined) return;
		const key = podcastKey(podcast);
		const existing = metadataRequests.get(key);
		if (existing) return existing;
		const request = (async () => {
			const track = await latestTrack(podcast);
			if (!track) return;
			podcasts = podcasts.map((item) =>
				podcastKey(item) === key
					? {
							...item,
							latestDurationMs: track.duration_ms || 0,
							latestPublishedAt: track.pub_date ? track.pub_date * 1000 : 0
						}
					: item
			);
		})();
		metadataRequests.set(key, request);
		try {
			return await request;
		} finally {
			metadataRequests.delete(key);
		}
	}

	async function hydrateVisibleMetadata() {
		if (isHydratingMetadata) return;
		isHydratingMetadata = true;
		try {
			await Promise.all(filtered.slice(0, 4 + visibleChartCount).map(hydrateMetadata));
		} finally {
			isHydratingMetadata = false;
		}
	}

	async function changeSort(next: DiscoverSort) {
		if (next === 'length' || next === 'newest') await hydrateVisibleMetadata();
		sort = next;
	}

	function toggleUILanguage() {
		prefs.setUILanguage(prefs.uiLanguage === 'en' ? 'de' : 'en');
	}

	async function setSessionMinutes(minutes: SessionMinutes | null) {
		listeningSession.set(minutes);
		fitsSession = minutes !== null;
		if (minutes !== null) await hydrateVisibleMetadata();
	}

	function plainSummary(value: string | undefined, author: string) {
		if (!value) return t('quiet.discover.fallbackDescription', { author });
		return value
			.replace(/<[^>]*>/g, ' ')
			.replace(/&nbsp;/gi, ' ')
			.replace(/&amp;/gi, '&')
			.replace(/&quot;/gi, '"')
			.replace(/&#(?:39|x27);/gi, "'")
			.replace(/\s+/g, ' ')
			.trim();
	}

	async function playLatest(podcast: PodcastItem) {
		const track = await latestTrack(podcast);
		if (track) player.play(track);
		else openPodcast(podcast);
	}

	async function queueLatest(podcast: PodcastItem) {
		const track = await latestTrack(podcast);
		if (!track) return openPodcast(podcast);
		await player.addToQueue(track);
		toast.success(t('toast.addedToQueue'));
	}

	async function saveLatest(podcast: PodcastItem) {
		const track = await latestTrack(podcast);
		if (!track) return subscribe(new MouseEvent('click'), podcast);
		await addLocalFavorite({ ...track, added_at: Date.now() });
		toast.success(t('toast.addedToFavorites'));
	}

	function handleDiscoverKeys(event: KeyboardEvent) {
		if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
			event.preventDefault();
			searchInput?.focus();
			return;
		}
		if (['INPUT', 'TEXTAREA', 'SELECT'].includes((event.target as HTMLElement)?.tagName) || !spotlight) return;
		if (event.key.toLowerCase() === 'p') playLatest(spotlight);
		else if (event.key.toLowerCase() === 'q') queueLatest(spotlight);
		else if (event.key.toLowerCase() === 's') saveLatest(spotlight);
	}

	async function subscribe(event: MouseEvent, podcast: PodcastItem) {
		event.stopPropagation();
		let id = podcast.id;
		try {
			if (podcast.feed_url) {
				const response = await fetch('/api/v1/podcasts/feed', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ feed_url: podcast.feed_url })
				});
				const resolved = await response.json();
				if (resolved.id) id = resolved.id;
			}
			await saveLocalSubscription({
				podcast_id: id,
				feed_url: podcast.feed_url || '',
				title: podcast.title,
				artwork_url: podcast.artwork_url,
				added_at: Date.now()
			});
			subscribedIds = [...subscribedIds, id];
			if (podcast.feed_url) subscribedFeeds = [...subscribedFeeds, podcast.feed_url];
			toast.success(t('discover.subscribeSuccess', { title: podcast.title }));
		} catch {
			toast.error(t('discover.subscribeError'));
		}
	}
</script>

{#if mounted && !prefs.onboarded}<Onboarding />{/if}
<svelte:window onkeydown={handleDiscoverKeys} />

<div class="discover-page">
	{#if !spotlight}<h1 class="sr-only">{t('quiet.discover.pageTitle')}</h1>{/if}
	<header class="discover-topbar">
		<div class="mobile-title">
			<strong>{t('quiet.nav.discover')}</strong>
			<span>{new Intl.DateTimeFormat(prefs.uiLanguage, { weekday: 'short', day: '2-digit', month: 'short' }).format(new Date()).toUpperCase()}</span>
		</div>
		<label class="global-search">
			<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
			<input bind:this={searchInput} bind:value={searchQuery} onkeydown={(event) => event.key === 'Enter' && openGlobalSearch()} aria-label={t('quiet.discover.searchLabel')} placeholder={t('quiet.discover.searchPlaceholder')} />
			<kbd>⌘K</kbd>
		</label>
		<span class="edition-date">{new Intl.DateTimeFormat(prefs.uiLanguage, { weekday: 'short', day: '2-digit', month: 'short' }).format(new Date()).toUpperCase()}</span>
		<button class="language-switch" type="button" onclick={toggleUILanguage} aria-label={`${t('quiet.discover.changeLanguage')}: ${prefs.uiLanguage.toUpperCase()}`} title={t('quiet.discover.changeLanguage')}>
			{prefs.uiLanguage.toUpperCase()}
		</button>
	</header>

	{#if isLoading}
		<section class="spotlight loading">
			<div><Skeleton width="110px" height="20px" /><Skeleton width="85%" height="84px" /><Skeleton width="70%" height="20px" /></div>
			<Skeleton width="100%" height="100%" radius="6px" />
		</section>
	{:else if spotlight}
		<section class="spotlight">
			<div class="spotlight-copy">
				<div class="spotlight-meta"><span>{t('quiet.discover.coverStory')}</span><span>#1 · {spotlight.author}</span></div>
				<h1>{spotlight.title}</h1>
				<p>{plainSummary(spotlight.description, spotlight.author)}</p>
				<div class="spotlight-actions">
					<button class="primary" onclick={() => playLatest(spotlight)}><i class="ph-fill ph-play"></i> {t('quiet.discover.playNow')}</button>
					<button onclick={() => queueLatest(spotlight)}><i class="ph ph-list-plus"></i> {t('quiet.discover.queueNext')}</button>
					<button onclick={() => saveLatest(spotlight)}><i class="ph ph-bookmark-simple"></i> {t('quiet.discover.save')}</button>
					<span>P / Q / S</span>
				</div>
			</div>
			<button class="spotlight-art" onclick={() => openPodcast(spotlight)} aria-label={`${t('quiet.discover.coverCaption')}: ${spotlight.title}`} title={t('discover.openPodcast', { title: spotlight.title })}>
				<img src={optimizeArtwork(spotlight.artwork_url, 420)} alt={spotlight.title} loading="eager" fetchpriority="high" decoding="async" onerror={(event) => ((event.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
				<div class="waveform" aria-hidden="true">{#each [8,16,12,24,18,28,13,21,17,26,11,20,15,23,9,18] as height}<i style:height={`${height}px`}></i>{/each}</div>
				<span>{t('quiet.discover.coverCaption')}</span>
			</button>
		</section>
	{:else}
		<section class="discover-empty">
			<i class="ph ph-translate" aria-hidden="true"></i>
			<h1>{t('quiet.discover.pageTitle')}</h1>
			<p>{t('discover.noResultsInLanguages')}</p>
			<a href="/settings#languages">{t('nav.settings')}</a>
		</section>
	{/if}

	<section class="session-section">
		<div class="session-control">
			<span>{t('quiet.discover.iHave')}</span>
			<div role="group" aria-label={t('quiet.discover.sessionLength')}>
				<button aria-pressed={listeningSession.minutes === null} class:active={listeningSession.minutes === null} onclick={() => setSessionMinutes(null)}>{t('quiet.discover.anyTime')}</button>
				{#each [25, 40, 60] as minutes}
					<button aria-pressed={listeningSession.minutes === minutes} class:active={listeningSession.minutes === minutes} onclick={() => setSessionMinutes(minutes as SessionMinutes)}>{minutes} min</button>
				{/each}
			</div>
			<p>— {t('quiet.discover.sessionHint')}</p>
		</div>
		<div class="mood-grid">
			{#each moods as mood}
				<button aria-pressed={selectedMood === mood.id} class:active={selectedMood === mood.id} onclick={() => (selectedMood = mood.id)}>
					<i class="ph {mood.icon}" aria-hidden="true"></i>
					<strong>{t(mood.labelKey)}</strong>
					<span>{t(mood.effectKey)}</span>
				</button>
			{/each}
		</div>
	</section>

	{#if picks.length}
		<section class="reasoned-picks">
			<header><h2>{t('quiet.discover.becauseMood', { mood: selectedMoodLabel })}</h2><span>{t('quiet.discover.picksIncluded', { count: picks.length })}</span></header>
			<div>
				{#each picks as podcast}
					<button onclick={() => openPodcast(podcast)} title={podcast.title}>
						<img src={optimizeArtwork(podcast.artwork_url, 120)} alt="" loading="lazy" decoding="async" onerror={(event) => ((event.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
						<span>
							<strong>{podcast.title}</strong>
							<small>{t('quiet.discover.moodReason', { mood: selectedMoodLabel })}</small>
							<em>
								{podcast.category || podcast.author}
								{#if fitsSession && listeningSession.minutes !== null && podcast.latestDurationMs && podcast.latestDurationMs <= listeningSession.minutes * 60_000}
									· {t('quiet.discover.selectedFor', { count: listeningSession.minutes })}
								{/if}
							</em>
						</span>
					</button>
				{/each}
			</div>
		</section>
	{/if}

	<section class="chart-section">
		<header class="chart-head">
			<div><h2>{t('quiet.discover.topTrending')}</h2><span>{t('quiet.discover.matches', { count: arranged.length })}</span></div>
			<div class="sort-tabs" role="group" aria-label={t('quiet.discover.sortChart')}>
				{#each [
					{ id: 'momentum' as const, key: 'quiet.discover.sortMomentum' as const },
					{ id: 'rank' as const, key: 'quiet.discover.sortRank' as const },
					{ id: 'length' as const, key: 'quiet.discover.sortLength' as const },
					{ id: 'newest' as const, key: 'quiet.discover.sortNewest' as const }
				] as option}
					<button aria-pressed={sort === option.id} class:active={sort === option.id} onclick={() => changeSort(option.id)}>{t(option.key)}</button>
				{/each}
			</div>
		</header>
		<div class="chart-filters">
			<select value={selectedCategory} onchange={(event) => selectCategory(event.currentTarget.value)} aria-label={t('search.genreFilter')} disabled={isLoading}>
				{#each categories as category}<option value={category}>{genreLabel(category)}</option>{/each}
			</select>
			<span aria-live="polite">{isLoading ? t('common.loading') : t('quiet.discover.shown', { shown: chart.length, total: arranged.length })}</span>
		</div>

		<div class="chart-list" aria-busy={isLoading}>
			{#if isLoading}
				{#each Array(5) as _}
					<div class="chart-row chart-loading">
						<Skeleton width="20px" height="10px" />
						<Skeleton width="48px" height="48px" radius="4px" />
						<Skeleton width="65%" height="14px" />
						<Skeleton width="32px" height="10px" />
						<Skeleton width="60px" height="28px" radius="4px" />
					</div>
				{/each}
			{:else}
			{#each chart as podcast, index (podcast.feed_url || podcast.id)}
				<article class="chart-row">
					<span class="rank">{String(index + 1).padStart(2, '0')}</span>
					<button class="chart-art" onclick={() => openPodcast(podcast)} aria-label={t('discover.openPodcast', { title: podcast.title })} title={t('discover.openPodcast', { title: podcast.title })}>
						<img src={optimizeArtwork(podcast.artwork_url, 96)} alt="" loading="lazy" decoding="async" onerror={(event) => ((event.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
					</button>
					<button class="chart-title" onclick={() => openPodcast(podcast)} title={podcast.title}>
						<strong>{podcast.title}</strong><span>{podcast.author}</span>
					</button>
					<span class="fit" title={podcast.latestDurationMs === undefined ? t('quiet.discover.durationUnknown') : undefined}>{isHydratingMetadata && podcast.latestDurationMs === undefined ? t('common.loading') : formatEpisodeMinutes(podcast.latestDurationMs) ?? t('quiet.discover.durationUnknownShort')}</span>
					<div class="row-actions">
						<button onclick={() => playLatest(podcast)} aria-label={t('quiet.discover.playLatest', { title: podcast.title })} title={t('quiet.discover.playLatest', { title: podcast.title })}><i class="ph-fill ph-play" aria-hidden="true"></i></button>
						<button onclick={() => queueLatest(podcast)} aria-label={t('quiet.discover.queueLatest', { title: podcast.title })} title={t('quiet.discover.queueLatest', { title: podcast.title })}><i class="ph ph-list-plus" aria-hidden="true"></i></button>
					</div>
				</article>
			{/each}
			{#if fitsSession && arranged.length === 0}
				<div class="empty-filter">
					<p>{t('quiet.discover.noFitResults')}</p>
					<button type="button" onclick={() => (fitsSession = false)}>{t('quiet.discover.clearTimeFilter')}</button>
				</div>
			{/if}
			{/if}
		</div>
		<footer class="chart-footer">
			<span>{t('quiet.discover.keyboardHint')}</span>
			{#if hasMoreResults}<button onclick={loadMore} disabled={isLoadingMore}>{isLoadingMore ? t('common.loading') : t('quiet.discover.loadMore')}</button>{/if}
		</footer>
	</section>
</div>

<style>
	.discover-page { min-height: 100%; background: var(--bg-panel); }
	.discover-topbar {
		position: sticky; top: 0; z-index: 15; display: grid; grid-template-columns: minmax(260px, 1fr) auto 30px;
		align-items: center; gap: 16px; min-height: 64px; padding: 11px 22px; background: var(--bg-rail);
		border-bottom: 1px solid var(--border-hair);
	}
	.mobile-title { display: none; }
	.global-search {
		display: flex; align-items: center; gap: 9px; min-width: 0; height: 38px; padding: 0 10px;
		background: var(--bg-sunken); border: 1px solid var(--border-ui); border-radius: 5px; color: var(--ink-4);
	}
	.global-search input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 14px; }
	.global-search input::placeholder { color: var(--ink-4); }
	kbd { padding: 3px 5px; border: 1px solid var(--border-ui); border-radius: 4px; color: var(--ink-4); font: 600 10px/1 var(--font-mono); }
	.edition-date { color: var(--ink-4); font: 600 10px/1 var(--font-mono); letter-spacing: .08em; }
	.language-switch { display: grid; place-items: center; min-width: 34px; height: 30px; padding: 0 7px; border: 1px solid var(--border-ui); border-radius: 5px; background: var(--bg-sunken); color: var(--accent-ink); font: 700 10px/1 var(--font-mono); }

	.spotlight {
		position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 208px; gap: 24px; min-height: 286px;
		padding: 24px 22px; overflow: hidden; background: radial-gradient(85% 150% at 0 0, rgba(127,208,170,.13), transparent 62%);
		border-bottom: 1px solid var(--border-hair);
	}
	.discover-empty { display: grid; justify-items: start; gap: 10px; margin: 32px 22px; padding: 24px; border: 1px solid var(--border-hair); border-radius: 8px; background: var(--bg-sunken); }
	.discover-empty > i { color: var(--accent-ink); font-size: 28px; }
	.discover-empty h1 { font-size: 24px; }
	.discover-empty p { color: var(--ink-3); }
	.discover-empty a { display: inline-flex; align-items: center; min-height: 44px; padding: 0 14px; border-radius: 5px; background: var(--accent-fill); color: var(--accent-on); font-weight: 700; }
	.spotlight.loading > div:first-child { display: flex; flex-direction: column; gap: 20px; }
	.spotlight-copy { display: flex; flex-direction: column; align-items: flex-start; justify-content: center; min-width: 0; }
	.spotlight-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 13px; color: var(--ink-4); font: 600 10px/1 var(--font-mono); letter-spacing: .1em; text-transform: uppercase; }
	.spotlight-meta span:first-child { padding: 5px 7px; border-radius: 4px; background: var(--accent-fill); color: var(--accent-on); }
	.spotlight h1 { display: -webkit-box; max-width: 720px; overflow: hidden; font: 700 clamp(34px, 4vw, 46px)/.95 var(--font-display); font-stretch: condensed; letter-spacing: -.045em; line-clamp: 3; -webkit-line-clamp: 3; -webkit-box-orient: vertical; text-transform: uppercase; }
	.spotlight p { display: -webkit-box; max-width: 58ch; margin-top: 13px; overflow: hidden; color: var(--ink-3); font-size: 15px; line-clamp: 3; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
	.spotlight-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
	.spotlight-actions button { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; padding: 0 12px; border: 1px solid var(--border-ui); border-radius: 5px; background: transparent; color: var(--ink-2); font-size: 12px; font-weight: 700; }
	.spotlight-actions button.primary { background: var(--accent-fill); border-color: var(--accent-fill); color: var(--accent-on); }
	.spotlight-actions span { margin-left: 4px; color: var(--ink-4); font: 600 10px/1 var(--font-mono); }
	.spotlight-art { display: flex; flex-direction: column; justify-content: center; min-width: 0; padding: 0; border: 0; background: transparent; color: inherit; text-align: left; }
	.spotlight-art img { width: 208px; height: 208px; object-fit: cover; border-radius: 6px; background: var(--bg-tile); }
	.spotlight-art > span { margin-top: 7px; color: var(--ink-4); font: 600 10px/1.3 var(--font-mono); letter-spacing: .06em; text-transform: uppercase; }
	.waveform { display: flex; align-items: center; gap: 3px; height: 32px; margin-top: -32px; padding: 0 9px; background: rgba(5,10,7,.76); }
	.waveform i { flex: 1; max-width: 4px; background: var(--data-bar); }

	.session-section { padding: 18px 22px 22px; border-bottom: 1px solid var(--border-hair); }
	.session-control { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; }
	.session-control > span { color: var(--ink-4); font: 600 11px/1 var(--font-mono); letter-spacing: .12em; text-transform: uppercase; }
	.session-control > div { display: flex; padding: 3px; background: var(--bg-sunken); border: 1px solid var(--border-ui); border-radius: 5px; }
	.session-control button { min-height: 32px; padding: 0 12px; border: 0; border-radius: 3px; background: transparent; color: var(--ink-3); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.session-control button.active { background: var(--accent-fill); color: var(--accent-on); }
	.session-control p { color: var(--ink-4); font-size: 13px; }
	.mood-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 14px; }
	.mood-grid button { display: grid; grid-template-columns: 25px 1fr; gap: 2px 8px; align-items: center; padding: 13px; text-align: left; border: 1px solid var(--border-hair); border-radius: 6px; background: linear-gradient(150deg, color-mix(in srgb, var(--accent-fill) 24%, var(--bg-sunken)), var(--bg-sunken)); color: var(--ink); }
	:global(:root[data-theme='light']) .mood-grid button { background: linear-gradient(150deg,#edf5f0,#dceae1); }
	.mood-grid button.active { background: linear-gradient(150deg, var(--accent-fill), color-mix(in srgb, var(--accent-fill) 72%, var(--bg-app))); color: var(--accent-on); border-color: transparent; }
	.mood-grid i { grid-row: 1 / 3; font-size: 22px; }
	.mood-grid strong { font: 700 16px/1.2 var(--font-ui); }
	.mood-grid span { font: 600 10px/1.3 var(--font-mono); letter-spacing: .04em; text-transform: uppercase; }

	.reasoned-picks, .chart-section { padding: 20px 22px; border-bottom: 1px solid var(--border-hair); }
	.reasoned-picks > header, .chart-head { display: flex; align-items: end; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
	.reasoned-picks h2, .chart-head h2 { font-size: 20px; letter-spacing: -.02em; }
	.reasoned-picks header span, .chart-head span { color: var(--ink-4); font: 600 10px/1.2 var(--font-mono); letter-spacing: .06em; text-transform: uppercase; }
	.reasoned-picks > div { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
	.reasoned-picks button { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 10px; min-width: 0; padding: 8px; text-align: left; border: 1px solid var(--border-hair); border-radius: 6px; background: var(--bg-sunken); color: var(--ink); }
	.reasoned-picks img { width: 60px; height: 60px; border-radius: 4px; object-fit: cover; background: var(--bg-tile); }
	.reasoned-picks button > span { display: flex; flex-direction: column; min-width: 0; }
	.reasoned-picks strong { overflow: hidden; font: 700 14px/1.25 var(--font-ui); text-overflow: ellipsis; white-space: nowrap; }
	.reasoned-picks small { display: -webkit-box; margin-top: 3px; overflow: hidden; color: var(--ink-3); font-size: 12px; line-clamp: 2; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
	.reasoned-picks em { margin-top: auto; color: var(--ink-4); font: 500 10px/1.2 var(--font-mono); font-style: normal; text-transform: uppercase; }

	.chart-head > div:first-child { display: flex; align-items: baseline; gap: 10px; }
	.sort-tabs { display: flex; gap: 2px; }
	.sort-tabs button { min-height: 32px; padding: 5px 9px; border: 0; border-radius: 3px; background: transparent; color: var(--ink-4); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.sort-tabs button.active { background: var(--accent-wash); color: var(--accent-ink); }
	.chart-filters { display: flex; align-items: center; gap: 6px; padding-bottom: 10px; border-bottom: 1px solid var(--border-hair); }
	.chart-filters select { min-height: 34px; padding: 0 10px; border: 1px solid var(--border-ui); border-radius: 4px; background: transparent; color: var(--ink-3); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.chart-filters span { margin-left: auto; color: var(--ink-4); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.chart-row { display: grid; grid-template-columns: 30px 48px minmax(0, 1fr) 52px 66px; gap: 10px; align-items: center; min-height: 66px; border-bottom: 1px solid var(--border-row); }
	.rank, .fit { color: var(--ink-4); font: 600 10px/1 var(--font-mono); font-variant-numeric: tabular-nums; }
	.chart-art, .chart-title, .row-actions button { border: 0; background: transparent; color: inherit; }
	.chart-art img { width: 48px; height: 48px; object-fit: cover; border-radius: 4px; background: var(--bg-tile); }
	.chart-title { display: flex; flex-direction: column; min-width: 0; text-align: left; }
	.chart-title strong, .chart-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.chart-title strong { color: var(--ink-2); font: 700 14px/1.3 var(--font-ui); }
	.chart-title span { color: var(--ink-4); font: 500 11px/1.4 var(--font-sans); }
	.row-actions { display: flex; gap: 4px; justify-content: end; }
	.row-actions button { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid var(--border-ui); border-radius: 4px; }
	.row-actions button i { display: block; font-size: 14px; line-height: 1; }
	.row-actions button:first-child { background: var(--accent-fill); border-color: var(--accent-fill); color: var(--accent-on); }
	.chart-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 12px; color: var(--ink-4); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.chart-footer button { padding: 7px 10px; border: 1px solid var(--border-ui); border-radius: 20px; background: transparent; color: var(--ink-3); font: inherit; text-transform: inherit; }
	.empty-filter { display: grid; justify-items: start; gap: 10px; padding: 22px 0; color: var(--ink-3); font-size: 13px; }
	.empty-filter button { min-height: 34px; padding: 0 12px; border: 1px solid var(--border-ui); border-radius: 5px; background: var(--bg-sunken); color: var(--ink-2); }

	@media (max-width: 820px) {
		.discover-topbar { grid-template-columns: minmax(0,1fr) 30px; padding: 10px 14px; }
		.edition-date { display: none; }
		.spotlight { grid-template-columns: minmax(0,1fr) 160px; padding: 20px 16px; }
		.spotlight-art img { width: 160px; height: 160px; }
		.reasoned-picks > div { grid-template-columns: 1fr; }
	}
	@media (max-width: 560px) {
		.discover-page { display: flex; flex-direction: column; }
		.discover-topbar {
			position: static;
			order: 0;
			display: grid;
			grid-template-columns: 1fr;
			gap: 10px;
			padding: 13px 16px 14px;
		}
		.mobile-title { display: flex; align-items: center; justify-content: space-between; }
		.mobile-title strong { font: 800 17px/1 var(--font-ui); }
		.mobile-title span { color: var(--ink-4); font: 600 10px/1 var(--font-mono); letter-spacing: .06em; }
		.discover-topbar { position: relative; }
		.discover-topbar .language-switch { position: absolute; top: 12px; right: 16px; }
		.mobile-title { padding-right: 46px; }
		.global-search kbd { display: none; }
		.session-section { display: contents; }
		.session-control { order: 1; padding: 16px; border-bottom: 1px solid var(--border-hair); }
		.spotlight { order: 2; display: flex; flex-direction: column-reverse; gap: 16px; padding: 16px; }
		.mood-grid { order: 3; margin: 0; padding: 16px; border-bottom: 1px solid var(--border-hair); }
		.reasoned-picks { order: 4; }
		.chart-section { order: 5; }
		.spotlight-art img { width: 100%; height: auto; aspect-ratio: 1; }
		.spotlight h1 { font-size: 30px; }
		.spotlight p { display: none; }
		.spotlight-actions { width: 100%; }
		.spotlight-actions .primary { flex: 1; justify-content: center; }
		.spotlight-actions > span { display: none; }
		.spotlight-art { width: min(72vw, 300px); align-self: center; }
		.spotlight-actions button, .session-control button, .sort-tabs button, .chart-filters select, .row-actions button { min-height: 44px; }
		.reasoned-picks, .chart-section { padding: 16px; }
		.mood-grid { grid-template-columns: repeat(2, 1fr); }
		.session-control p { flex-basis: 100%; }
		.chart-head { align-items: flex-start; flex-direction: column; }
		.sort-tabs { width: 100%; overflow-x: auto; }
		.chart-row { grid-template-columns: 24px 42px minmax(0, 1fr) 58px; min-height: 60px; }
		.fit { display: none; }
		.chart-filters span { display: none; }
		.chart-footer span { display: none; }
		.chart-footer { justify-content: end; }
	}
</style>
