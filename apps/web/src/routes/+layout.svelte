<script lang="ts">
	import '../lib/styles/app.css';
	import Footer from '$lib/components/Footer.svelte';

	let { children } = $props();
</script>

<div class="app-container">
	<header class="navbar">
		<div class="brand">
			<span class="logo-icon">🌿</span>
			<span class="brand-title">KoalaCast</span>
		</div>
		<nav class="nav-links">
			<a href="/" aria-label="Home page"><i class="ph ph-house" aria-hidden="true"></i> Home</a>
			<a href="/library" aria-label="Library page"><i class="ph ph-books" aria-hidden="true"></i> Library</a>
			<a href="/search" aria-label="Search podcasts"><i class="ph ph-magnifying-glass" aria-hidden="true"></i> Search</a>
			<a href="/settings" aria-label="Settings page"><i class="ph ph-gear" aria-hidden="true"></i> Settings</a>
			<a href="https://github.com/Shik3i/KoalaCast" target="_blank" rel="noopener noreferrer" aria-label="GitHub Repository" class="github-link">
				<i class="ph ph-github-logo" aria-hidden="true"></i> GitHub
			</a>
		</nav>
	</header>

	<main class="main-content">
		{@render children()}
	</main>

	<Footer />
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
		padding: 1rem 2rem;
		background-color: var(--bg-surface);
		border-bottom: 1px solid var(--border-subtle);
		position: sticky;
		top: 0;
		z-index: 50;
		backdrop-filter: blur(10px);
	}

	.brand {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 700;
		font-size: 1.3rem;
	}

	.logo-icon {
		font-size: 1.6rem;
	}

	.nav-links {
		display: flex;
		align-items: center;
		gap: 1.5rem;
	}

	.nav-links a {
		font-weight: 500;
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.github-link {
		padding: 0.35rem 0.75rem;
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		background: var(--bg-elevated);
		color: var(--text-primary);
	}

	.main-content {
		flex: 1;
		padding: 2.5rem 2rem;
		max-width: 1200px;
		width: 100%;
		margin: 0 auto;
		padding-bottom: 120px;
	}
</style>
