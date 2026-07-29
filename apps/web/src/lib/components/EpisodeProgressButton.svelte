<script lang="ts">
	let {
		progress = 0,
		current = false,
		label,
		onclick,
		size = 44
	}: {
		progress?: number;
		current?: boolean;
		label: string;
		onclick: () => void;
		size?: number;
	} = $props();

	const safeProgress = $derived(Math.max(0, Math.min(100, Number.isFinite(progress) ? progress : 0)));
</script>

<button
	type="button"
	class="episode-progress"
	class:current
	style={`--episode-progress:${safeProgress * 3.6}deg;--episode-progress-size:${size}px`}
	{onclick}
	aria-label={label}
	title={label}
>
	<span class="progress-inner">
		<i class="ph-fill {current ? 'ph-waveform' : 'ph-play'}" aria-hidden="true"></i>
		{#if current}
			<span class="current-pulse" aria-hidden="true"></span>
		{/if}
	</span>
</button>

<style>
	.episode-progress {
		width: var(--episode-progress-size);
		height: var(--episode-progress-size);
		flex: 0 0 auto;
		padding: 3px;
		border: 1px solid var(--border-ui);
		border-radius: 50%;
		background: conic-gradient(
			var(--show-accent, var(--accent-fill)) var(--episode-progress),
			var(--track) 0
		);
		color: var(--ink-2);
		box-shadow: none;
		transition: transform .16s ease, border-color .16s ease, color .16s ease;
	}

	.progress-inner {
		position: relative;
		display: grid;
		width: 100%;
		height: 100%;
		place-items: center;
		border-radius: inherit;
		background: var(--bg-panel);
	}

	i {
		display: block;
		font-size: calc(var(--episode-progress-size) * .38);
		line-height: 1;
	}

	.episode-progress:hover {
		border-color: var(--show-accent, var(--accent-fill));
		color: var(--show-accent, var(--accent-fill));
		transform: scale(1.04);
	}

	.episode-progress:focus-visible {
		outline: 3px solid var(--focus-ring);
		outline-offset: 3px;
	}

	.episode-progress.current {
		color: var(--show-accent, var(--accent-fill));
	}
	.current-pulse {
		position: absolute;
		inset: -4px;
		border: 1px solid currentColor;
		border-radius: inherit;
		animation: current-pulse 1.8s ease-out infinite;
		pointer-events: none;
	}
	.episode-progress:active { transform: scale(.94); }
	@keyframes current-pulse {
		0% { opacity: .45; transform: scale(.82); }
		70%, 100% { opacity: 0; transform: scale(1.15); }
	}

	@media (prefers-reduced-motion: reduce) {
		.episode-progress { transition: none; }
		.current-pulse { display: none; }
	}
</style>
