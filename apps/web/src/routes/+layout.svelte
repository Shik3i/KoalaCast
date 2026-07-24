<script lang="ts">
	import '../lib/styles/app.css';

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
		</nav>
	</header>

	<main class="main-content">
		{@render children()}
	</main>
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
	}

	.brand {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 700;
		font-size: 1.25rem;
	}

	.logo-icon {
		font-size: 1.5rem;
	}

	.nav-links {
		display: flex;
		gap: 1.5rem;
	}

	.nav-links a {
		font-weight: 500;
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.main-content {
		flex: 1;
		padding: 2rem;
		max-width: 1200px;
		width: 100%;
		margin: 0 auto;
		padding-bottom: 100px;
	}
</style>
