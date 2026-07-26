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

	interface PodcastItem {
		id: string;
		title: string;
		author: string;
		feed_url: string;
		artwork_url: string;
		category?: string;
		categories?: string[];
		description?: string;
	}

	const PAGE_SIZE = 60;
	const moods = [
		{ label: 'Calm', icon: 'ph-waves', fit: [4, 7, 11] },
		{ label: 'Curious', icon: 'ph-lightbulb', fit: [7, 12, 18] },
		{ label: 'Company', icon: 'ph-users-three', fit: [3, 5, 9] },
		{ label: 'Focus', icon: 'ph-crosshair', fit: [2, 4, 7] }
	];

	let mounted = $state(false);
	let podcasts = $state<PodcastItem[]>([]);
	let subscribedIds = $state<string[]>([]);
	let subscribedFeeds = $state<string[]>([]);
	let searchQuery = $state('');
	let selectedCategory = $state('All');
	let selectedMood = $state('Calm');
	let sort = $state<'momentum' | 'rank' | 'length' | 'newest'>('momentum');
	let isLoading = $state(true);
	let isLoadingMore = $state(false);
	let limit = $state(PAGE_SIZE);
	let reachedEnd = $state(false);
	let searchInput: HTMLInputElement;
	let requestId = 0;

	const categories = ['All', ...GENRES.map((genre) => genre.name)];
	const sessionIndex = $derived(listeningSession.minutes === 25 ? 0 : listeningSession.minutes === 40 ? 1 : 2);
	const filtered = $derived(
		podcasts.filter((pod) => {
			if (prefs.isHidden(pod.categories)) return false;
			const query = searchQuery.trim().toLowerCase();
			return !query || pod.title.toLowerCase().includes(query) || pod.author.toLowerCase().includes(query);
		})
	);
	const spotlight = $derived(filtered[0] ?? null);
	const picks = $derived(filtered.slice(1, 4));
	const chart = $derived(filtered.slice(4, 16));

	onMount(async () => {
		mounted = true;
		listeningSession.load();
		const subscriptions = await getLocalSubscriptions();
		subscribedIds = subscriptions.map((sub) => sub.podcast_id);
		subscribedFeeds = subscriptions.map((sub) => sub.feed_url).filter(Boolean);
		await loadDiscover();
	});

	async function loadDiscover() {
		const id = ++requestId;
		isLoading = true;
		const languages = prefs.languages.length ? prefs.languages : detectBrowserLanguages();
		try {
			const responses = await Promise.allSettled(
				languages.map(async (language) => {
					const params = new URLSearchParams({
						limit: String(limit),
						region: regionForLanguage(language),
						languages: language
					});
					if (selectedCategory !== 'All') params.set('category', selectedCategory);
					const response = await fetch(`/api/v1/podcasts/discover?${params}`);
					return response.ok ? response.json() : { results: [] };
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
			podcasts = merged.length ? merged : FEATURED_PODCASTS;
			reachedEnd = merged.length < limit;
		} catch {
			podcasts = FEATURED_PODCASTS;
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
		await loadDiscover();
	}

	async function loadMore() {
		if (isLoadingMore || reachedEnd) return;
		isLoadingMore = true;
		limit += PAGE_SIZE;
		await loadDiscover();
	}

	function isSubscribed(podcast: PodcastItem) {
		return subscribedIds.includes(podcast.id) || (!!podcast.feed_url && subscribedFeeds.includes(podcast.feed_url));
	}

	async function resolvePodcastId(podcast: PodcastItem) {
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
			} catch {}
		}
		return podcast.id;
	}

	async function openPodcast(podcast: PodcastItem) {
		const id = await resolvePodcastId(podcast);
		goto(`/podcast/${id}?feed_url=${encodeURIComponent(podcast.feed_url || '')}`);
	}

	async function latestTrack(podcast: PodcastItem): Promise<CurrentTrack | null> {
		const id = await resolvePodcastId(podcast);
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
				categories: podcast.categories || (podcast.category ? [podcast.category] : [])
			};
		} catch {
			return null;
		}
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
	<header class="discover-topbar">
		<div class="mobile-title">
			<strong>Discover</strong>
			<span>{new Intl.DateTimeFormat(prefs.uiLanguage, { weekday: 'short', day: '2-digit', month: 'short' }).format(new Date()).toUpperCase()}</span>
		</div>
		<label class="global-search">
			<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
			<input bind:this={searchInput} bind:value={searchQuery} placeholder="Search shows, people, topics — or “calm, under 30 min”" />
			<kbd>⌘K</kbd>
		</label>
		<span class="edition-date">{new Intl.DateTimeFormat(prefs.uiLanguage, { weekday: 'short', day: '2-digit', month: 'short' }).format(new Date()).toUpperCase()} · {prefs.languages.join(' / ').toUpperCase()}</span>
		<span class="avatar" aria-label="Profile">JK</span>
	</header>

	{#if isLoading}
		<section class="spotlight loading">
			<div><Skeleton width="110px" height="20px" /><Skeleton width="85%" height="84px" /><Skeleton width="70%" height="20px" /></div>
			<Skeleton width="100%" height="100%" radius="6px" />
		</section>
	{:else if spotlight}
		<section class="spotlight">
			<div class="spotlight-copy">
				<div class="spotlight-meta"><span>Cover story</span><span>#1 · {spotlight.author}</span></div>
				<h1>{spotlight.title}</h1>
				<p>{spotlight.description || `A considered listen from ${spotlight.author}, selected from today's chart in your chosen languages.`}</p>
				<div class="spotlight-actions">
					<button class="primary" onclick={() => playLatest(spotlight)}><i class="ph-fill ph-play"></i> Play now</button>
					<button onclick={() => queueLatest(spotlight)}><i class="ph ph-list-plus"></i> Queue next</button>
					<button onclick={() => saveLatest(spotlight)}><i class="ph ph-bookmark-simple"></i> Save</button>
					<span>P / Q / S</span>
				</div>
			</div>
			<div class="spotlight-art">
				<img src={optimizeArtwork(spotlight.artwork_url, 420)} alt={spotlight.title} onerror={(event) => ((event.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
				<div class="waveform" aria-hidden="true">{#each [8,16,12,24,18,28,13,21,17,26,11,20,15,23,9,18] as height}<i style:height={`${height}px`}></i>{/each}</div>
				<span>Today’s cover story · open to explore</span>
			</div>
		</section>
	{/if}

	<section class="session-section">
		<div class="session-control">
			<span>I have</span>
			<div role="group" aria-label="Session length">
				{#each [25, 40, 60] as minutes}
					<button class:active={listeningSession.minutes === minutes} onclick={() => listeningSession.set(minutes as SessionMinutes)}>{minutes} min</button>
				{/each}
			</div>
			<p>— filters the tiles, the chart and “trim queue”</p>
		</div>
		<div class="mood-grid">
			{#each moods as mood}
				<button class:active={selectedMood === mood.label} onclick={() => (selectedMood = mood.label)}>
					<i class="ph {mood.icon}"></i>
					<strong>{mood.label}</strong>
					<span>{mood.fit[sessionIndex]} fit {listeningSession.minutes} min</span>
				</button>
			{/each}
		</div>
	</section>

	{#if picks.length}
		<section class="reasoned-picks">
			<header><h2>Because you chose “{selectedMood}”</h2><span>{picks.length} picks · reasons included</span></header>
			<div>
				{#each picks as podcast, index}
					<button onclick={() => openPodcast(podcast)}>
						<img src={optimizeArtwork(podcast.artwork_url, 120)} alt="" onerror={(event) => ((event.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
						<span>
							<strong>{podcast.title}</strong>
							<small>{index === 0 ? 'A measured pace for a focused session.' : index === 1 ? 'A useful change of subject without the shouting.' : 'Clear voices and enough detail to stay curious.'}</small>
							<em>{podcast.category || podcast.author} · selected for {listeningSession.minutes} min</em>
						</span>
					</button>
				{/each}
			</div>
		</section>
	{/if}

	<section class="chart-section">
		<header class="chart-head">
			<div><h2>Top & trending</h2><span>{filtered.length} shows match</span></div>
			<div class="sort-tabs" role="tablist">
				{#each ['momentum', 'rank', 'length', 'newest'] as option}
					<button class:active={sort === option} onclick={() => (sort = option as typeof sort)}>{option}</button>
				{/each}
			</div>
		</header>
		<div class="chart-filters">
			<button>Fits {listeningSession.minutes} min <i class="ph ph-x"></i></button>
			<select value={selectedCategory} onchange={(event) => selectCategory(event.currentTarget.value)} aria-label="Category">
				{#each categories as category}<option value={category}>{genreLabel(category)}</option>{/each}
			</select>
			<span>{Math.min(chart.length, 12)} of {filtered.length} shown</span>
		</div>

		<div class="chart-list">
			{#each chart as podcast, index (podcast.feed_url || podcast.id)}
				<article class="chart-row">
					<span class="rank">{String(index + 1).padStart(2, '0')}</span>
					<button class="chart-art" onclick={() => openPodcast(podcast)} aria-label={`Open ${podcast.title}`}>
						<img src={optimizeArtwork(podcast.artwork_url, 96)} alt="" onerror={(event) => ((event.currentTarget as HTMLImageElement).src = '/placeholder.svg')} />
					</button>
					<button class="chart-title" onclick={() => openPodcast(podcast)}>
						<strong>{podcast.title}</strong><span>{podcast.author}</span>
					</button>
					<div class="spark" aria-label="Momentum">
						{#each [5, 8, 7, 11, 9, 15, 13, 18, 16, 22] as value}<i style:height={`${value}px`}></i>{/each}
					</div>
					<span class="fit">{listeningSession.minutes}m</span>
					<div class="row-actions">
						<button onclick={() => playLatest(podcast)} aria-label={`Play latest episode of ${podcast.title}`}><i class="ph-fill ph-play"></i></button>
						<button onclick={() => queueLatest(podcast)} aria-label={`Queue latest episode of ${podcast.title}`}><i class="ph ph-list-plus"></i></button>
					</div>
				</article>
			{/each}
		</div>
		<footer class="chart-footer">
			<span>J/K to move · Enter to open · S to subscribe</span>
			{#if !reachedEnd}<button onclick={loadMore} disabled={isLoadingMore}>{isLoadingMore ? t('common.loading') : 'Load 12 more ↓'}</button>{/if}
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
	.global-search input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 12px; }
	.global-search input::placeholder { color: var(--ink-4); }
	kbd { padding: 3px 5px; border: 1px solid var(--border-ui); border-radius: 4px; color: var(--ink-4); font: 600 9px/1 var(--font-mono); }
	.edition-date { color: var(--ink-4); font: 600 9px/1 var(--font-mono); letter-spacing: .08em; }
	.avatar { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; background: var(--accent-fill); color: var(--accent-on); font: 700 10px/1 var(--font-mono); }

	.spotlight {
		position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 208px; gap: 24px; min-height: 286px;
		padding: 24px 22px; overflow: hidden; background: radial-gradient(85% 150% at 0 0, rgba(127,208,170,.13), transparent 62%);
		border-bottom: 1px solid var(--border-hair);
	}
	.spotlight.loading > div:first-child { display: flex; flex-direction: column; gap: 20px; }
	.spotlight-copy { display: flex; flex-direction: column; align-items: flex-start; justify-content: center; min-width: 0; }
	.spotlight-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 13px; color: var(--ink-4); font: 600 9px/1 var(--font-mono); letter-spacing: .1em; text-transform: uppercase; }
	.spotlight-meta span:first-child { padding: 5px 7px; border-radius: 4px; background: var(--accent-fill); color: var(--accent-on); }
	.spotlight h1 { display: -webkit-box; max-width: 720px; overflow: hidden; font: 700 clamp(34px, 4vw, 46px)/.95 var(--font-display); font-stretch: condensed; letter-spacing: -.045em; line-clamp: 3; -webkit-line-clamp: 3; -webkit-box-orient: vertical; text-transform: uppercase; }
	.spotlight p { display: -webkit-box; max-width: 58ch; margin-top: 13px; overflow: hidden; color: var(--ink-3); font-size: 15px; line-clamp: 3; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
	.spotlight-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
	.spotlight-actions button { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; padding: 0 12px; border: 1px solid var(--border-ui); border-radius: 5px; background: transparent; color: var(--ink-2); font-size: 12px; font-weight: 700; }
	.spotlight-actions button.primary { background: var(--accent-fill); border-color: var(--accent-fill); color: var(--accent-on); }
	.spotlight-actions span { margin-left: 4px; color: var(--ink-4); font: 600 9px/1 var(--font-mono); }
	.spotlight-art { display: flex; flex-direction: column; justify-content: center; min-width: 0; }
	.spotlight-art img { width: 208px; height: 208px; object-fit: cover; border-radius: 6px; background: var(--bg-tile); }
	.spotlight-art > span { margin-top: 7px; color: var(--ink-4); font: 600 8px/1.3 var(--font-mono); letter-spacing: .06em; text-transform: uppercase; }
	.waveform { display: flex; align-items: center; gap: 3px; height: 32px; margin-top: -32px; padding: 0 9px; background: rgba(5,10,7,.76); }
	.waveform i { flex: 1; max-width: 4px; background: var(--data-bar); }

	.session-section { padding: 18px 22px 22px; border-bottom: 1px solid var(--border-hair); }
	.session-control { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; }
	.session-control > span { color: var(--ink-4); font: 600 10px/1 var(--font-mono); letter-spacing: .12em; text-transform: uppercase; }
	.session-control > div { display: flex; padding: 3px; background: var(--bg-sunken); border: 1px solid var(--border-ui); border-radius: 5px; }
	.session-control button { min-height: 29px; padding: 0 11px; border: 0; border-radius: 3px; background: transparent; color: var(--ink-3); font: 600 9px/1 var(--font-mono); text-transform: uppercase; }
	.session-control button.active { background: var(--accent-fill); color: var(--accent-on); }
	.session-control p { color: var(--ink-4); font-size: 11px; }
	.mood-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 14px; }
	.mood-grid button { display: grid; grid-template-columns: 25px 1fr; gap: 2px 8px; align-items: center; padding: 13px; text-align: left; border: 1px solid var(--border-hair); border-radius: 6px; background: linear-gradient(150deg,#2a4a3a,#12201a); color: var(--ink); }
	:global(:root[data-theme='light']) .mood-grid button { background: linear-gradient(150deg,#edf5f0,#dceae1); }
	.mood-grid button.active { background: linear-gradient(150deg,#7fd0aa,#3e9c76); color: var(--accent-on); border-color: transparent; }
	.mood-grid i { grid-row: 1 / 3; font-size: 22px; }
	.mood-grid strong { font: 700 14px/1.2 var(--font-ui); }
	.mood-grid span { font: 600 8px/1.2 var(--font-mono); letter-spacing: .05em; text-transform: uppercase; }

	.reasoned-picks, .chart-section { padding: 20px 22px; border-bottom: 1px solid var(--border-hair); }
	.reasoned-picks > header, .chart-head { display: flex; align-items: end; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
	.reasoned-picks h2, .chart-head h2 { font-size: 17px; letter-spacing: -.02em; }
	.reasoned-picks header span, .chart-head span { color: var(--ink-4); font: 600 8px/1 var(--font-mono); letter-spacing: .08em; text-transform: uppercase; }
	.reasoned-picks > div { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
	.reasoned-picks button { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 10px; min-width: 0; padding: 8px; text-align: left; border: 1px solid var(--border-hair); border-radius: 6px; background: var(--bg-sunken); color: var(--ink); }
	.reasoned-picks img { width: 60px; height: 60px; border-radius: 4px; object-fit: cover; background: var(--bg-tile); }
	.reasoned-picks button > span { display: flex; flex-direction: column; min-width: 0; }
	.reasoned-picks strong { overflow: hidden; font: 700 12px/1.25 var(--font-ui); text-overflow: ellipsis; white-space: nowrap; }
	.reasoned-picks small { display: -webkit-box; margin-top: 3px; overflow: hidden; color: var(--ink-3); font-size: 10px; line-clamp: 2; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
	.reasoned-picks em { margin-top: auto; color: var(--ink-4); font: 500 7px/1 var(--font-mono); font-style: normal; text-transform: uppercase; }

	.chart-head > div:first-child { display: flex; align-items: baseline; gap: 10px; }
	.sort-tabs { display: flex; gap: 2px; }
	.sort-tabs button { padding: 5px 8px; border: 0; border-radius: 3px; background: transparent; color: var(--ink-4); font: 600 8px/1 var(--font-mono); text-transform: uppercase; }
	.sort-tabs button.active { background: var(--accent-wash); color: var(--accent-ink); }
	.chart-filters { display: flex; align-items: center; gap: 6px; padding-bottom: 10px; border-bottom: 1px solid var(--border-hair); }
	.chart-filters button, .chart-filters select { height: 26px; padding: 0 8px; border: 1px solid var(--border-ui); border-radius: 4px; background: transparent; color: var(--ink-3); font: 600 8px/1 var(--font-mono); text-transform: uppercase; }
	.chart-filters span { margin-left: auto; color: var(--ink-4); font: 600 8px/1 var(--font-mono); text-transform: uppercase; }
	.chart-row { display: grid; grid-template-columns: 28px 44px minmax(0, 1fr) 76px 48px 62px; gap: 8px; align-items: center; min-height: 58px; border-bottom: 1px solid var(--border-row); }
	.rank, .fit { color: var(--ink-4); font: 600 9px/1 var(--font-mono); font-variant-numeric: tabular-nums; }
	.chart-art, .chart-title, .row-actions button { border: 0; background: transparent; color: inherit; }
	.chart-art img { width: 44px; height: 44px; object-fit: cover; border-radius: 4px; background: var(--bg-tile); }
	.chart-title { display: flex; flex-direction: column; min-width: 0; text-align: left; }
	.chart-title strong, .chart-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.chart-title strong { color: var(--ink-2); font: 700 12px/1.3 var(--font-ui); }
	.chart-title span { color: var(--ink-4); font: 500 9px/1.4 var(--font-sans); }
	.spark { display: flex; align-items: end; gap: 3px; height: 24px; }
	.spark i { width: 4px; background: var(--data-bar); }
	.spark i:nth-last-child(-n+3) { background: var(--accent-fill); }
	.row-actions { display: flex; gap: 4px; justify-content: end; }
	.row-actions button { display: grid; place-items: center; width: 27px; height: 27px; border: 1px solid var(--border-ui); border-radius: 4px; }
	.row-actions button:first-child { background: var(--accent-fill); border-color: var(--accent-fill); color: var(--accent-on); }
	.chart-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 12px; color: var(--ink-4); font: 600 8px/1 var(--font-mono); text-transform: uppercase; }
	.chart-footer button { padding: 7px 10px; border: 1px solid var(--border-ui); border-radius: 20px; background: transparent; color: var(--ink-3); font: inherit; text-transform: inherit; }

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
		.mobile-title span { color: var(--ink-4); font: 600 8px/1 var(--font-mono); letter-spacing: .08em; }
		.discover-topbar .avatar { display: none; }
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
		.reasoned-picks, .chart-section { padding: 16px; }
		.mood-grid { grid-template-columns: repeat(2, 1fr); }
		.session-control p { flex-basis: 100%; }
		.chart-head { align-items: flex-start; flex-direction: column; }
		.sort-tabs { width: 100%; overflow-x: auto; }
		.chart-row { grid-template-columns: 24px 42px minmax(0, 1fr) 58px; min-height: 60px; }
		.spark, .fit { display: none; }
		.chart-filters span { display: none; }
		.chart-footer span { display: none; }
		.chart-footer { justify-content: end; }
	}
</style>
