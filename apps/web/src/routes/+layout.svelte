<script lang="ts">
	import '@fontsource/archivo/latin-700.css';
	import '@fontsource/bricolage-grotesque/latin-400.css';
	import '@fontsource/bricolage-grotesque/latin-700.css';
	import '@fontsource/bricolage-grotesque/latin-800.css';
	import '@fontsource/outfit/400.css';
	import '@fontsource/outfit/600.css';
	import '@fontsource/outfit/700.css';
	import '@fontsource/ibm-plex-mono/latin-400.css';
	import '@fontsource/ibm-plex-mono/latin-600.css';
	import '@fontsource/ibm-plex-mono/latin-700.css';
	import '@phosphor-icons/web/regular/style.css';
	import '@phosphor-icons/web/fill/style.css';
	import '../lib/styles/app.css';
	import { page } from '$app/stores';
	import Player from '$lib/components/Player.svelte';
	import RailResizer from '$lib/components/RailResizer.svelte';
	import RunningOrder from '$lib/components/RunningOrder.svelte';
	import Seo from '$lib/components/Seo.svelte';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import Toast from '$lib/components/Toast.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { player } from '$lib/stores/player.svelte';
	import { sync } from '$lib/stores/sync.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { shell } from '$lib/stores/shell.svelte';
	import { t, loadLocale, getLocaleConfig } from '$lib/i18n';
	import { onMount } from 'svelte';

	let { children } = $props();
	let currentUser = $state<{ user_id: string; username: string; role: string } | null>(null);
	let online = $state(true);

	const path = $derived($page.url.pathname);
	const showRunningOrder = $derived(path === '/');
	const appLinks = $derived([
		{ href: '/', icon: 'ph-newspaper', label: t('quiet.nav.discover') },
		{ href: '/search', icon: 'ph-magnifying-glass', label: t('nav.search') },
		{ href: '/inbox', icon: 'ph-tray', label: t('nav.new') },
		{ href: '/library', icon: 'ph-squares-four', label: t('nav.library') },
		{ href: '/more', icon: 'ph-dots-three-circle', label: t('nav.profileMenu') }
	]);

	onMount(() => {
		fetch('/api/v1/auth/status')
			.then((res) => (res.ok ? res.json() : null))
			.then((me) => {
				if (me?.user_id) {
					currentUser = me;
					sync.enable(me.user_id);
				}
			})
			.catch(() => {});
	});
	onMount(() => {
		online = navigator.onLine;
		const update = () => (online = navigator.onLine);
		window.addEventListener('online', update);
		window.addEventListener('offline', update);
		return () => {
			window.removeEventListener('online', update);
			window.removeEventListener('offline', update);
		};
	});

	$effect(() => {
		const locale = prefs.uiLanguage;
		loadLocale(locale);
		document.documentElement.lang = locale;
		document.documentElement.dir = getLocaleConfig(locale)?.rtl ? 'rtl' : 'ltr';
	});

	function active(href: string) {
		return href === '/' ? path === '/' : path === href || path.startsWith(`${href}/`);
	}
</script>

<Seo />
<a class="skip-link" href="#main-content">{t('common.skipToContent')}</a>
<div
	class="quiet-app"
	class:has-player={player.isActive}
	class:left-collapsed={shell.leftCollapsed}
	class:left-compact={shell.leftCompact}
	class:right-collapsed={shell.rightCollapsed}
	class:right-compact={shell.rightCompact}
	class:empty-running-order={showRunningOrder && !player.current && player.queue.length === 0}
	class:without-running-order={!showRunningOrder}
	style={`--left-rail-width:${shell.leftWidth}px;--right-rail-width:${shell.rightWidth}px`}
>
	<Sidebar isAdmin={currentUser?.role === 'admin'} />
	<RailResizer side="left" controls="navigation-rail" label={t('quiet.shell.resizeNavigation')} />
	<main class="quiet-main" id="main-content" tabindex="-1">
		{#if !online}<div class="offline-banner" role="status"><i class="ph ph-wifi-slash" aria-hidden="true"></i>{t('common.offline')}</div>{/if}
		{@render children()}
		<footer class="mobile-legal" aria-label={t('footer.mobileLinks')}>
			<a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer">{t('footer.legalNotice')}</a>
			<a href="/privacy">{t('footer.privacy')}</a>
			<a href="https://github.com/Shik3i/KoalaCast" target="_blank" rel="noopener noreferrer">{t('footer.github')}</a>
			<a href="https://github.com/Shik3i/KoalaCast/blob/main/LICENSE" target="_blank" rel="noopener noreferrer">{t('footer.license')}</a>
		</footer>
	</main>
	{#if showRunningOrder}
		<RailResizer side="right" controls="running-order" label={t('quiet.shell.resizeQueue')} />
		<RunningOrder />
	{/if}

	<nav class="quiet-mobile-nav" class:has-player={player.isActive} aria-label={t('nav.primary')}>
		{#each appLinks as link}
			<a href={link.href} class:active={active(link.href)} aria-current={active(link.href) ? 'page' : undefined}>
				<i class="{active(link.href) ? 'ph-fill' : 'ph'} {link.icon}" aria-hidden="true"></i>
				<span>{link.label}</span>
			</a>
		{/each}
	</nav>

	<Player />
	<Toast />
	<ConfirmDialog />
</div>
