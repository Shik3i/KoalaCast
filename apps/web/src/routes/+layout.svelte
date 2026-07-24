<script lang="ts">
	import '../lib/styles/app.css';
	import { page } from '$app/stores';
	import Footer from '$lib/components/Footer.svelte';
	import Player from '$lib/components/Player.svelte';
	import { player } from '$lib/stores/player.svelte';

	let { children } = $props();

	const links = [
		{ href: '/library', icon: 'ph-books', label: 'Library' },
		{ href: '/search', icon: 'ph-magnifying-glass', label: 'Search' },
		{ href: '/settings', icon: 'ph-gear', label: 'Settings' }
	];

	const path = $derived($page.url.pathname);
	function isActive(href: string) {
		return path === href || path.startsWith(href + '/');
	}
</script>

<div class="app-container">
	<header class="navbar">
		<a class="brand" href="/" aria-label="KoalaCast home">
			<span class="logo-icon" aria-hidden="true">🌿</span>
			<span class="brand-title">KoalaCast</span>
		</a>
		<nav class="nav-links">
			{#each links as link}
				<a href={link.href} class:active={isActive(link.href)} aria-label={link.label} aria-current={isActive(link.href) ? 'page' : undefined}>
					<i class="ph {link.icon}" aria-hidden="true"></i>
					<span class="nav-label">{link.label}</span>
				</a>
			{/each}
		</nav>
	</header>

	<main class="main-content" class:has-player={player.isActive}>
		{@render children()}
	</main>

	<Footer />
	<Player />
</div>

<style>
	.app-container {
		display: flex;
		flex-direction: column;
		min-height: 100vh;
	}

	.navbar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.85rem 2rem;
		background: color-mix(in srgb, var(--bg-surface) 78%, transparent);
		border-bottom: 1px solid var(--border-subtle);
		position: sticky;
		top: 0;
		z-index: 50;
		backdrop-filter: blur(14px) saturate(140%);
		-webkit-backdrop-filter: blur(14px) saturate(140%);
	}

	.brand {
		display: flex;
		align-items: center;
		gap: 0.55rem;
		font-weight: 800;
		font-size: 1.3rem;
		letter-spacing: -0.02em;
		color: var(--text-primary);
		border-radius: 12px;
		padding: 0.2rem 0.4rem;
		transition: var(--transition-smooth);
	}
	.brand:hover {
		text-decoration: none;
		transform: translateY(-1px);
	}
	.brand:hover .logo-icon {
		transform: rotate(-12deg) scale(1.12);
	}
	.brand-title {
		background: linear-gradient(120deg, var(--text-primary), var(--accent-green));
		-webkit-background-clip: text;
		background-clip: text;
		-webkit-text-fill-color: transparent;
	}

	.logo-icon {
		font-size: 1.6rem;
		display: inline-block;
		transition: transform 0.3s var(--ease-spring, cubic-bezier(0.16, 1, 0.3, 1));
	}

	.nav-links {
		display: flex;
		align-items: center;
		gap: 0.35rem;
	}

	.nav-links a {
		position: relative;
		font-weight: 600;
		font-size: 0.95rem;
		display: flex;
		align-items: center;
		gap: 0.45rem;
		padding: 0.5rem 0.9rem;
		border-radius: 12px;
		color: var(--text-secondary);
		transition: var(--transition-smooth);
	}
	.nav-links a :global(.ph) {
		font-size: 1.2rem;
		transition: transform 0.25s var(--ease-spring, cubic-bezier(0.16, 1, 0.3, 1));
	}
	.nav-links a:hover {
		text-decoration: none;
		color: var(--text-primary);
		background: var(--bg-elevated);
	}
	.nav-links a:hover :global(.ph) {
		transform: translateY(-2px) scale(1.12);
	}
	.nav-links a:active :global(.ph) {
		transform: scale(0.9);
	}
	.nav-links a.active {
		color: var(--accent-green);
		background: color-mix(in srgb, var(--accent-green) 12%, transparent);
	}

	.main-content {
		flex: 1;
		padding: 2.5rem 2rem;
		max-width: 1200px;
		width: 100%;
		margin: 0 auto;
		padding-bottom: 3rem;
	}
	/* Reserve space for the floating player only when it is active. */
	.main-content.has-player {
		padding-bottom: 130px;
	}

	@media (max-width: 640px) {
		.navbar { padding: 0.75rem 1rem; }
		.nav-label { display: none; }
		.nav-links a { padding: 0.5rem 0.7rem; }
		.main-content { padding: 1.5rem 1rem; }
	}
</style>
