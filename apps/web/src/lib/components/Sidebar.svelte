<script lang="ts">
	import { page } from '$app/stores';
	import { onMount } from 'svelte';
	import {
		getAllLocalPlaybackStates,
		getLocalListeningSessions,
		getLocalSubscriptions,
		getRecentPlaybackStates,
		type LocalSubscription
	} from '$lib/idb/db';
	import { t } from '$lib/i18n';
	import { sync } from '$lib/stores/sync.svelte';
	import {
		getPodcastPlaybackSettings,
		savePodcastPlaybackSettings,
		type PodcastPlaybackSettings
	} from '$lib/stores/podcast-settings';

	let { isAdmin = false }: { isAdmin?: boolean } = $props();
	let subscriptions = $state<LocalSubscription[]>([]);
	let recentCount = $state(0);
	let showStats = $state({ listenedMs: 0, finished: 0, episodes: 0, averageSpeed: 1 });
	let showSettings = $state<PodcastPlaybackSettings>(getPodcastPlaybackSettings(''));
	let showContextRequest = 0;
	let loadedSyncAt = 0;

	const path = $derived($page.url.pathname);
	const links = $derived([
		{ href: '/', icon: 'ph-newspaper', label: t('quiet.nav.discover') },
		{ href: '/inbox', icon: 'ph-tray', label: t('quiet.nav.new'), count: recentCount },
		{ href: '/library', icon: 'ph-squares-four', label: t('quiet.nav.library') },
		{ href: '/profile', icon: 'ph-user-circle', label: t('quiet.nav.profile') },
		{ href: '/settings', icon: 'ph-gear', label: t('quiet.nav.settings') },
		...(isAdmin ? [{ href: '/admin', icon: 'ph-shield-star', label: t('nav.admin') }] : [])
	]);

	const profileLinks = $derived([
		{ href: '/profile#stats', label: t('quiet.profile.stats') },
		{ href: '/profile#activity', label: t('quiet.profile.activity') },
		{ href: '/profile#rankings', label: t('quiet.profile.rankings') },
		{ href: '/profile#time-saved', label: t('quiet.profile.timeSaved') },
		{ href: '/profile#privacy', label: t('quiet.profile.dataPrivacy') }
	]);

	async function loadSidebarData() {
		subscriptions = await getLocalSubscriptions();
		recentCount = (await getRecentPlaybackStates(100)).length;
	}

	onMount(loadSidebarData);

	$effect(() => {
		const syncedAt = sync.lastSyncedAt;
		if (!syncedAt || syncedAt === loadedSyncAt) return;
		loadedSyncAt = syncedAt;
		void loadSidebarData();
	});

	$effect(() => {
		const currentPath = path;
		sync.lastSyncedAt;
		if (!currentPath.startsWith('/podcast/')) return;
		const podcastId = currentPath.split('/')[2] || '';
		const request = ++showContextRequest;
		showSettings = getPodcastPlaybackSettings(podcastId);
		Promise.all([getLocalListeningSessions(), getAllLocalPlaybackStates()]).then(([sessions, states]) => {
			if (request !== showContextRequest) return;
			const showSessions = sessions.filter((session) => session.podcast_id === podcastId);
			const showStates = states.filter((state) => state.podcast_id === podcastId);
			const wallMs = showSessions.reduce((sum, session) => sum + session.wall_clock_ms, 0);
			const weighted = showSessions.reduce((sum, session) => sum + session.speed_weighted_ms, 0);
			showStats = {
				listenedMs: wallMs,
				finished: showStates.filter((state) => state.completed).length,
				episodes: showStates.length,
				averageSpeed: wallMs ? weighted / wallMs : 1
			};
		});
	});

	function active(href: string) {
		return href === '/' ? path === '/' : path === href || path.startsWith(`${href}/`);
	}

	function formatDuration(ms: number) {
		const minutes = Math.round(ms / 60_000);
		return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
	}

	function updateShowSetting(patch: Partial<PodcastPlaybackSettings>) {
		const podcastId = path.split('/')[2] || '';
		showSettings = { ...showSettings, ...patch };
		savePodcastPlaybackSettings(podcastId, showSettings);
	}
</script>

<aside class="quiet-rail" aria-label={t('quiet.nav.primary')}>
	<a class="quiet-brand" href="/" aria-label={t('nav.brandHome')}>
		<picture>
			<source type="image/avif" srcset="/icon-40.avif 1x, /icon-80.avif 2x" />
			<source type="image/webp" srcset="/icon-40.webp 1x, /icon-80.webp 2x" />
			<img src="/icon-40.png" srcset="/icon-40.png 1x, /icon-80.png 2x" width="28" height="28" alt="" />
		</picture>
		<span>KoalaCast</span>
	</a>

	<nav class="quiet-nav">
		{#each links as link}
			<a href={link.href} class:active={active(link.href)} aria-current={active(link.href) ? 'page' : undefined}>
				<i class="{active(link.href) ? 'ph-fill' : 'ph'} {link.icon}" aria-hidden="true"></i>
				<span>{link.label}</span>
				{#if link.count}<span class="nav-count">{link.count}</span>{/if}
			</a>
		{/each}
	</nav>

	{#if path.startsWith('/profile')}
		<nav class="profile-subnav" aria-label={t('quiet.profile.sections')}>
			{#each profileLinks as link}
				<a href={link.href}>{link.label}</a>
			{/each}
		</nav>
	{:else if path.startsWith('/podcast/')}
		<section class="rail-context show-context">
			<p class="rail-eyebrow">Your history with this show</p>
			<strong class="show-total">{formatDuration(showStats.listenedMs)}</strong>
			<span>{showStats.finished} of {showStats.episodes} episodes finished · avg {showStats.averageSpeed.toFixed(2)}×</span>
			<p class="rail-eyebrow settings-label">Per-show settings</p>
			<label>Skip intro <input type="number" min="0" max="600" value={showSettings.skipIntroSeconds} onchange={(event) => updateShowSetting({ skipIntroSeconds: Number(event.currentTarget.value) })} /> s</label>
			<label>Skip outro <input type="number" min="0" max="600" value={showSettings.skipOutroSeconds} onchange={(event) => updateShowSetting({ skipOutroSeconds: Number(event.currentTarget.value) })} /> s</label>
			<label>Speed
				<select value={showSettings.speed ?? ''} onchange={(event) => updateShowSetting({ speed: event.currentTarget.value ? Number(event.currentTarget.value) : null })}>
					<option value="">Global</option>
					{#each [1, 1.1, 1.2, 1.25, 1.3, 1.5, 1.75, 2] as speed}<option value={speed}>{speed}×</option>{/each}
				</select>
			</label>
			<label class="auto-queue"><input type="checkbox" checked={showSettings.autoQueueNew} onchange={(event) => updateShowSetting({ autoQueueNew: event.currentTarget.checked })} /> Auto-queue new</label>
		</section>
	{:else if path.startsWith('/admin')}
		<section class="rail-context">
			<p class="rail-eyebrow">{t('admin.title')}</p>
			<a href="/admin#metrics">{t('admin.metrics')}</a>
			<a href="/admin#registration">{t('admin.publicRegistration')}</a>
			<a href="/admin#users">{t('admin.registeredUsers', { count: 0 })}</a>
			<a href="/admin#feeds">{t('admin.feedHealth')}</a>
		</section>
	{:else if path.startsWith('/library')}
		<section class="rail-context">
			<p class="rail-eyebrow">{t('quiet.library.collections')}</p>
			<a href="/library">{t('quiet.library.allShows')} <span>{subscriptions.length}</span></a>
			<a href="/library?view=episodes">{t('quiet.library.inProgress')}</a>
			<a href="/library?view=favorites">{t('quiet.library.savedEpisodes')}</a>
			<a href="/library?view=queue">{t('quiet.library.queue')}</a>
		</section>
	{:else if path.startsWith('/inbox')}
		<section class="rail-context">
			<p class="rail-eyebrow">{t('quiet.inbox.filters')}</p>
			<a href="/inbox">{t('quiet.inbox.all')} <span>{recentCount}</span></a>
			<a href="/inbox?filter=fits">{t('quiet.inbox.fits')}</a>
			<a href="/inbox?filter=started">{t('quiet.inbox.started')}</a>
			<a href="/inbox?filter=downloaded">{t('quiet.inbox.downloaded')}</a>
		</section>
	{:else}
		<section class="rail-context subscriptions">
			<p class="rail-eyebrow">{t('quiet.nav.subscriptions')}</p>
			{#each subscriptions.slice(0, 6) as sub}
				<a href={`/podcast/${sub.podcast_id}`}>
					<span class="rail-dot" aria-hidden="true"></span>
					<span class="truncate">{sub.title}</span>
				</a>
			{:else}
				<p class="rail-empty">{t('quiet.nav.noSubscriptions')}</p>
			{/each}
		</section>
	{/if}

	<div class="rail-bottom">
		<span class="rail-eyebrow">{sync.enabled ? t('quiet.profile.syncActive') : t('quiet.profile.localOnly')}</span>
		<strong>{t('quiet.profile.private')}</strong>
		<span>{sync.enabled ? t('quiet.profile.onAccount') : t('quiet.profile.onDevice')}</span>
	</div>
</aside>
