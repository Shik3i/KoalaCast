<script lang="ts">
	import { page } from '$app/stores';
	import { onMount } from 'svelte';
	import {
		getLocalSubscriptions,
		getRecentPlaybackStates,
		type LocalSubscription
	} from '$lib/idb/db';
	import { t } from '$lib/i18n';
	import { sync } from '$lib/stores/sync.svelte';

	let { isAdmin = false }: { isAdmin?: boolean } = $props();
	let subscriptions = $state<LocalSubscription[]>([]);
	let recentCount = $state(0);
	let loadedSyncAt = 0;

	const path = $derived($page.url.pathname);
	const links = $derived([
		{ href: '/', icon: 'ph-newspaper', label: t('quiet.nav.discover') },
		{ href: '/search', icon: 'ph-magnifying-glass', label: t('nav.search') },
		{ href: '/inbox', icon: 'ph-tray', label: t('quiet.nav.new'), count: recentCount },
		{ href: '/library', icon: 'ph-squares-four', label: t('quiet.nav.library') },
		{ href: '/profile', icon: 'ph-user-circle', label: t('quiet.nav.profile') },
		{ href: '/global-stats', icon: 'ph-chart-line-up', label: t('globalStats.nav') },
		{ href: '/settings', icon: 'ph-gear', label: t('quiet.nav.settings') },
		...(isAdmin ? [{ href: '/admin', icon: 'ph-shield-star', label: t('nav.admin') }] : [])
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

	function active(href: string) {
		return href === '/' ? path === '/' : path === href || path.startsWith(`${href}/`);
	}
</script>

<aside class="quiet-rail" id="navigation-rail" aria-label={t('quiet.nav.primary')}>
	<header class="rail-header">
		<a class="quiet-brand" href="/" aria-label={t('nav.brandHome')}>
			<picture>
				<source type="image/avif" srcset="/icon-40.avif 1x, /icon-80.avif 2x" />
				<source type="image/webp" srcset="/icon-40.webp 1x, /icon-80.webp 2x" />
				<img src="/icon-40.png" srcset="/icon-40.png 1x, /icon-80.png 2x" width="28" height="28" alt="" />
			</picture>
			<span>KoalaCast</span>
		</a>
	</header>

	<nav class="quiet-nav">
		{#each links as link}
			<a href={link.href} class:active={active(link.href)} aria-current={active(link.href) ? 'page' : undefined}>
				<i class="{active(link.href) ? 'ph-fill' : 'ph'} {link.icon}" aria-hidden="true"></i>
				<span>{link.label}</span>
				{#if link.count}<span class="nav-count">{link.count}</span>{/if}
			</a>
		{/each}
	</nav>

	<section class="rail-context subscriptions">
		<p class="rail-eyebrow">{t('quiet.nav.subscriptions')}</p>
		{#each subscriptions.slice(0, 6) as sub}
			<a href={`/podcast/${sub.podcast_id}`} title={sub.title}>
				<span class="rail-dot" aria-hidden="true"></span>
				<span class="truncate">{sub.title}</span>
			</a>
		{:else}
			<p class="rail-empty">{t('quiet.nav.noSubscriptions')}</p>
		{/each}
	</section>

	<footer class="rail-bottom" aria-label={t('footer.links')}>
		<a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer" title={t('footer.legalNotice')}>
			<i class="ph ph-scales" aria-hidden="true"></i><span>{t('footer.legalNotice')}</span>
		</a>
		<a href="/privacy" title={t('footer.privacy')}>
			<i class="ph ph-shield-check" aria-hidden="true"></i><span>{t('footer.privacy')}</span>
		</a>
		<a href="https://github.com/Shik3i/KoalaCast" target="_blank" rel="noopener noreferrer" title={t('footer.github')}>
			<i class="ph ph-github-logo" aria-hidden="true"></i><span>{t('footer.github')}</span>
		</a>
		<a href="https://github.com/Shik3i/KoalaCast/blob/main/LICENSE" target="_blank" rel="noopener noreferrer" title={t('footer.license')}>
			<i class="ph ph-file-text" aria-hidden="true"></i><span>{t('footer.license')}</span>
		</a>
		<small>{t('footer.copyright')}</small>
	</footer>
</aside>
