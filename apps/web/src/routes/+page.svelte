<script lang="ts">
	let isLocalMode = $state(true);
</script>

<div class="page-container">
	<section class="hero">
		<h1>Free, open-source podcast player</h1>
		<p class="tagline">Listen calmly. Synchronize across devices. Zero tracking, zero ads.</p>
	</section>

	<section class="mode-card">
		<div class="mode-badge">
			{isLocalMode ? 'Local Mode Active' : 'Synced Mode Active'}
		</div>
		<p>
			{#if isLocalMode}
				Your subscriptions, queue, and playback history remain strictly inside this browser's IndexedDB.
			{:else}
				Your playback state and subscriptions synchronize securely across your authorized devices.
			{/if}
		</p>
	</section>

	<section class="sections-grid">
		<div class="grid-card">
			<h3>Continue Listening</h3>
			<p class="empty-state">No episodes in progress.</p>
		</div>
		<div class="grid-card">
			<h3>Up Next</h3>
			<p class="empty-state">Your queue is empty.</p>
		</div>
	</section>
</div>

<style>
	.page-container {
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.hero h1 {
		font-size: 2.25rem;
		font-weight: 700;
		color: var(--text-primary);
	}

	.tagline {
		font-size: 1.125rem;
		color: var(--text-secondary);
		margin-top: 0.5rem;
	}

	.mode-card {
		background-color: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1.25rem;
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	.mode-badge {
		align-self: flex-start;
		background-color: var(--accent-light);
		color: var(--accent-green-hover);
		padding: 0.25rem 0.75rem;
		border-radius: 12px;
		font-size: 0.875rem;
		font-weight: 600;
	}

	.sections-grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
		gap: 1.5rem;
	}

	.grid-card {
		background-color: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.empty-state {
		color: var(--text-muted);
		margin-top: 1rem;
		font-style: italic;
	}
</style>
