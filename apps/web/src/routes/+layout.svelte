<script lang="ts">
	import '$lib/styles/fonts.css';
	import '$lib/styles/phosphor-subset.css';
	import '../lib/styles/app.css';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import RailResizer from '$lib/components/RailResizer.svelte';
	import RunningOrder from '$lib/components/RunningOrder.svelte';
	import Seo from '$lib/components/Seo.svelte';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import Toast from '$lib/components/Toast.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { player } from '$lib/stores/player.svelte';
	import {
		activateAccountContext,
		activateLoggedInAccount,
		getLastAccountContext
	} from '$lib/stores/account-context';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { shell } from '$lib/stores/shell.svelte';
	import { install } from '$lib/stores/install.svelte';
	import { opmlBackup } from '$lib/backup/opml-backup.svelte';
	import { t, loadLocale, getLocaleConfig } from '$lib/i18n';
	import { onMount } from 'svelte';
	import { getLocalSubscriptions } from '$lib/idb/db';
	import { preloadSubscriptionArtwork, setArtworkProxyEnabled } from '$lib/artwork';

	let { children } = $props();
	let currentUser = $state<{ user_id: string; username: string; role: string } | null>(null);
	let online = $state(true);
	let DeferredPlayer = $state<any>(null);
	let playerImport: Promise<void> | null = null;

	const path = $derived($page.url.pathname);
	const showRunningOrder = $derived(path === '/');
	const appLinks = $derived([
		{ href: '/', icon: 'ph-compass', label: t('quiet.nav.discover') },
		{ href: '/inbox', icon: 'ph-tray', label: t('nav.new') },
		{ href: '/library', icon: 'ph-books', label: t('nav.library') },
		{ href: '/profile', icon: 'ph-user-circle', label: t('nav.profileMenu') }
	]);

	onMount(async () => {
		try {
			const res = await fetch('/api/v1/auth/status');
			if (!res.ok) throw new Error(`auth status ${res.status}`);
			const me = await res.json();
			if (me?.authenticated && me.user_id) {
				currentUser = me;
				await activateLoggedInAccount(me.user_id);
			} else {
				await activateAccountContext(null);
			}
		} catch {
			const lastAccount = getLastAccountContext();
			if (lastAccount) {
				currentUser = { user_id: lastAccount, username: '', role: 'user' };
				await activateAccountContext(lastAccount);
			} else {
				await activateAccountContext(null);
			}
		}
		const startScreenMarker = 'koalacast_start_screen_applied';
		try {
			if (prefs.onboarded && location.pathname === '/' && sessionStorage.getItem(startScreenMarker) !== '1') {
				sessionStorage.setItem(startScreenMarker, '1');
				const destination = prefs.startScreen === 'inbox' ? '/inbox' : prefs.startScreen === 'library' ? '/library' : null;
				if (destination) void goto(destination, { replaceState: true });
			}
		} catch (_) {
			// Storage may be disabled; the app must still mount on the default route.
		}
		void getLocalSubscriptions().then(preloadSubscriptionArtwork);
		// Rewrites the listener's chosen OPML file at most once a day, and only once
		// the account context above has settled — so the backup describes the library
		// that is actually loaded, not the one from before the switch.
		void opmlBackup.write();
	});
	onMount(() => {
		const warmPlayer = (event: Event) => {
			const target = event.target as HTMLElement | null;
			if (target?.closest('button, a')?.querySelector('.ph-play, .ph-play-circle')) ensurePlayer();
		};
		window.addEventListener('pointerover', warmPlayer, { passive: true, capture: true });
		window.addEventListener('pointerdown', warmPlayer, { passive: true, capture: true });
		return () => {
			window.removeEventListener('pointerover', warmPlayer, { capture: true });
			window.removeEventListener('pointerdown', warmPlayer, { capture: true });
		};
	});

	function ensurePlayer() {
		if (DeferredPlayer || playerImport) return playerImport;
		playerImport = import('$lib/components/Player.svelte').then((module) => {
			DeferredPlayer = module.default;
		});
		return playerImport;
	}

	$effect(() => {
		if (player.isActive) ensurePlayer();
	});
	// Must be registered before the browser fires it, which it does very early.
	onMount(() => install.listen());
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
	onMount(() => {
		const viewport = window.visualViewport;
		const updateMobileViewportInset = () => {
			const inset = viewport
				? Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)
				: 0;
			document.documentElement.style.setProperty('--mobile-browser-inset', `${Math.round(inset)}px`);
		};
		updateMobileViewportInset();
		viewport?.addEventListener('resize', updateMobileViewportInset);
		viewport?.addEventListener('scroll', updateMobileViewportInset);
		window.addEventListener('orientationchange', updateMobileViewportInset);
		return () => {
			viewport?.removeEventListener('resize', updateMobileViewportInset);
			viewport?.removeEventListener('scroll', updateMobileViewportInset);
			window.removeEventListener('orientationchange', updateMobileViewportInset);
			document.documentElement.style.removeProperty('--mobile-browser-inset');
		};
	});

	$effect(() => {
		const locale = prefs.uiLanguage;
		loadLocale(locale);
		document.documentElement.lang = locale;
		document.documentElement.dir = getLocaleConfig(locale)?.rtl ? 'rtl' : 'ltr';
	});
	$effect(() => setArtworkProxyEnabled(prefs.proxyImages));

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
		<!--
			Profile only. On a phone these links used to sit under every tab, so
			Impressum and MIT-Lizenz appeared beneath an empty inbox — a web page
			footer bleeding into a view that is otherwise an app screen. The
			Android client keeps this kind of link on the profile screen; so does
			this now.
		-->
		<footer class="mobile-legal" class:on-profile={path === '/profile'} aria-label={t('footer.mobileLinks')}>
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

	{#if DeferredPlayer}<DeferredPlayer />{/if}
	<Toast />
	<ConfirmDialog />
</div>
