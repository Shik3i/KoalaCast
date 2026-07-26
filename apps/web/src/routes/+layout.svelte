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
	import RunningOrder from '$lib/components/RunningOrder.svelte';
	import Seo from '$lib/components/Seo.svelte';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import Toast from '$lib/components/Toast.svelte';
	import { player } from '$lib/stores/player.svelte';
	import { sync } from '$lib/stores/sync.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { t, loadLocale, getLocaleConfig } from '$lib/i18n';
	import { onMount } from 'svelte';

	let { children } = $props();
	let currentUser = $state<{ user_id: string; username: string; role: string } | null>(null);

	const path = $derived($page.url.pathname);
	const showRunningOrder = $derived(path === '/');
	const appLinks = $derived([
		{ href: '/', icon: 'ph-newspaper', label: t('quiet.nav.discover') },
		{ href: '/inbox', icon: 'ph-tray', label: t('nav.new') },
		{ href: '/library', icon: 'ph-squares-four', label: t('nav.library') },
		{ href: '/profile', icon: 'ph-user-circle', label: t('quiet.nav.profile') },
		{ href: '/global-stats', icon: 'ph-chart-line-up', label: t('globalStats.mobileNav') }
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
<div class="quiet-app" class:has-player={player.isActive}>
	<Sidebar isAdmin={currentUser?.role === 'admin'} />
	<main class="quiet-main" id="main-content" tabindex="-1">
		{@render children()}
	</main>
	{#if showRunningOrder}
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
</div>
