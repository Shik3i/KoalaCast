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
			<a href="/">Home</a>
			<a href="/library">Library</a>
			<a href="/search">Search</a>
			<a href="/settings">Settings</a>
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
		color: var(--text-secondary);
	}

	.nav-links a:hover {
		color: var(--accent-green);
	}

	.main-content {
		flex: 1;
		padding: 2rem;
		max-width: 1200px;
		width: 100%;
		margin: 0 auto;
	}
</style>
