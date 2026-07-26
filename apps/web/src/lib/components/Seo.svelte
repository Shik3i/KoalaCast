<script lang="ts">
	import { page } from '$app/stores';
	import { browser } from '$app/environment';
	import { currentLocale } from '$lib/i18n';

	type SeoCopy = {
		title: string;
		description: string;
		index: boolean;
	};

	const english: Record<string, SeoCopy> = {
		home: {
			title: 'KoalaCast — Free, private podcast player',
			description: 'Discover, follow and listen to podcasts in a calm, open-source player with local-first storage and optional cross-device sync.',
			index: true
		},
		globalStats: {
			title: 'Global listening statistics — KoalaCast',
			description: 'Explore opt-in podcast listening trends, popular shows, active weekdays and community listening time without exposing private listening histories.',
			index: true
		},
		privacy: {
			title: 'Privacy policy — KoalaCast',
			description: 'How KoalaCast handles local browser data, optional accounts and sync, server logs, podcast artwork proxies and direct audio streams.',
			index: true
		},
		podcast: {
			title: 'Podcast — KoalaCast',
			description: 'Listen to podcast episodes, browse the archive and follow the show with KoalaCast.',
			index: true
		},
		episode: {
			title: 'Podcast episode — KoalaCast',
			description: 'Listen to this podcast episode, read its show notes and use chapters or transcripts when the publisher provides them.',
			index: true
		},
		search: {
			title: 'Search podcasts — KoalaCast',
			description: 'Search podcast directories or add any compatible RSS feed directly to KoalaCast.',
			index: false
		},
		login: {
			title: 'Sign in — KoalaCast',
			description: 'Sign in to synchronize your KoalaCast library and listening progress across devices.',
			index: false
		},
		register: {
			title: 'Create an account — KoalaCast',
			description: 'Create an optional KoalaCast account for private cross-device synchronization.',
			index: false
		},
		private: {
			title: 'KoalaCast',
			description: 'Your private KoalaCast listening workspace.',
			index: false
		}
	};

	const german: Record<string, SeoCopy> = {
		home: {
			title: 'KoalaCast — Kostenloser, privater Podcast-Player',
			description: 'Podcasts entdecken, abonnieren und in einem ruhigen Open-Source-Player mit lokaler Speicherung und optionaler Gerätesynchronisierung hören.',
			index: true
		},
		globalStats: {
			title: 'Globale Hörstatistiken — KoalaCast',
			description: 'Freiwillig geteilte Podcast-Trends, beliebte Shows, aktive Wochentage und gemeinsame Hörzeit ohne Veröffentlichung privater Hörverläufe.',
			index: true
		},
		privacy: english.privacy,
		podcast: {
			title: 'Podcast — KoalaCast',
			description: 'Podcast-Folgen hören, das Archiv durchsuchen und die Sendung mit KoalaCast abonnieren.',
			index: true
		},
		episode: {
			title: 'Podcast-Folge — KoalaCast',
			description: 'Diese Podcast-Folge hören und verfügbare Shownotes, Kapitel oder Transkripte lesen.',
			index: true
		},
		search: {
			title: 'Podcasts suchen — KoalaCast',
			description: 'Podcast-Verzeichnisse durchsuchen oder jeden kompatiblen RSS-Feed direkt zu KoalaCast hinzufügen.',
			index: false
		},
		login: {
			title: 'Anmelden — KoalaCast',
			description: 'Bei KoalaCast anmelden und Mediathek sowie Hörfortschritt geräteübergreifend synchronisieren.',
			index: false
		},
		register: {
			title: 'Konto erstellen — KoalaCast',
			description: 'Ein optionales KoalaCast-Konto für die private Gerätesynchronisierung erstellen.',
			index: false
		},
		private: {
			title: 'KoalaCast',
			description: 'Dein privater KoalaCast-Hörbereich.',
			index: false
		}
	};

	const routeKey = $derived.by(() => {
		const path = $page.url.pathname;
		if (path === '/') return 'home';
		if (path === '/global-stats') return 'globalStats';
		if (path === '/privacy') return 'privacy';
		if (path.startsWith('/podcast/')) return 'podcast';
		if (path.startsWith('/episode/')) return 'episode';
		if (path === '/search') return 'search';
		if (path === '/login') return 'login';
		if (path === '/register') return 'register';
		return 'private';
	});
	const copy = $derived((currentLocale() === 'de' ? german : english)[routeKey] ?? english.private);
	const canonical = $derived(`${$page.url.origin}${$page.url.pathname === '/' ? '/' : $page.url.pathname.replace(/\/+$/, '')}`);
	const image = $derived(`${$page.url.origin}/icon-1024.png`);

	$effect(() => {
		const description = copy.description;
		if (browser) document.querySelector('meta[name="description"]')?.setAttribute('content', description);
	});
</script>

<svelte:head>
	<title>{copy.title}</title>
	<meta name="robots" content={copy.index ? 'index, follow, max-image-preview:large' : 'noindex, nofollow'} />
	<link rel="canonical" href={canonical} />
	<meta property="og:site_name" content="KoalaCast" />
	<meta property="og:type" content="website" />
	<meta property="og:title" content={copy.title} />
	<meta property="og:description" content={copy.description} />
	<meta property="og:url" content={canonical} />
	<meta property="og:image" content={image} />
	<meta property="og:image:width" content="1024" />
	<meta property="og:image:height" content="1024" />
	<meta property="og:image:alt" content="KoalaCast koala logo" />
	<meta name="twitter:card" content="summary" />
	<meta name="twitter:title" content={copy.title} />
	<meta name="twitter:description" content={copy.description} />
	<meta name="twitter:image" content={image} />
</svelte:head>
