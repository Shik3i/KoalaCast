<script lang="ts">
	let {
		width = '100%',
		height = '1rem',
		radius = '8px'
	}: { width?: string; height?: string; radius?: string } = $props();
</script>

<span class="skeleton" style="width:{width};height:{height};border-radius:{radius}"></span>

<style>
	.skeleton {
		display: block;
		background: linear-gradient(
			100deg,
			var(--bg-elevated) 30%,
			color-mix(in srgb, var(--text-muted) 18%, var(--bg-elevated)) 50%,
			var(--bg-elevated) 70%
		);
		background-size: 200% 100%;
		animation: shimmer 1.3s ease-in-out infinite;
	}

	@keyframes shimmer {
		0% {
			background-position: 200% 0;
		}
		100% {
			background-position: -200% 0;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.skeleton {
			animation: none;
		}
	}
</style>
