<script lang="ts">
	import type { VisualizerStyle } from '$lib/stores/prefs.svelte';

	let {
		style,
		level = 0,
		progress = 0,
		history = [],
		playing = true,
		variant = 'compact'
	}: {
		style: VisualizerStyle;
		level?: number;
		progress?: number;
		history?: number[];
		playing?: boolean;
		variant?: 'compact' | 'full' | 'preview';
	} = $props();

	const fallbackHistory = [0.16, 0.42, 0.7, 0.28, 0.84, 0.54, 0.32, 0.76, 0.46, 0.62, 0.22, 0.72, 0.38, 0.58, 0.3, 0.66, 0.44, 0.2];
	const barScales = [0.42, 0.7, 0.92, 0.62, 1, 0.76, 0.48, 0.84, 0.56];
	const samples = $derived(history.length > 0 ? history.slice(-18) : fallbackHistory);
	const dotSamples = $derived(samples.filter((_, index) => index % 2 === 0).slice(-9));
</script>

{#if playing && style !== 'off' && style !== 'level'}
	<div
		class="signal"
		class:compact={variant === 'compact'}
		class:full={variant === 'full'}
		class:preview={variant === 'preview'}
		class:waveform={style === 'waveform'}
		class:bars={style === 'bars'}
		class:pulse={style === 'pulse'}
		class:dots={style === 'dots'}
		style="--level: {Math.max(0, Math.min(1, level))}; --progress: {Math.max(0, Math.min(100, progress))}%"
		aria-hidden="true"
	>
		{#if style === 'waveform'}
			<div class="sample-row">
				{#each samples as sample}
					<span style="--sample: {Math.max(0.08, sample)}"></span>
				{/each}
			</div>
		{:else if style === 'bars'}
			<div class="sample-row bar-row">
				{#each barScales as scale}
					<span style="--scale: {scale}"></span>
				{/each}
			</div>
		{:else if style === 'pulse'}
			<div class="pulse-point"><span></span><span></span></div>
		{:else if style === 'dots'}
			<div class="dot-row">
				{#each dotSamples as sample, index}
					<span class:alternate={index % 2 === 1} style="--sample: {Math.max(0.08, sample)}"></span>
				{/each}
			</div>
		{/if}
	</div>
{/if}

<style>
	.signal {
		position: absolute;
		left: 0;
		right: 0;
		z-index: 2;
		pointer-events: none;
		color: var(--show-accent, var(--accent-green));
	}
	.signal.compact { top: 3px; height: 24px; }
	.signal.full { top: 50%; height: 32px; transform: translateY(-50%); }
	.signal.preview { position: relative; height: 30px; }
	.sample-row, .dot-row {
		display: flex;
		height: 100%;
		align-items: center;
		justify-content: center;
		gap: 2px;
	}
	.sample-row span {
		width: 2px;
		height: max(2px, calc(var(--sample, 0.1) * 24px));
		border-radius: 999px;
		background: currentColor;
		opacity: 0.76;
		transition: height 70ms linear, opacity 120ms ease;
	}
	.bar-row { gap: 3px; }
	.bar-row span {
		width: 3px;
		height: max(3px, calc((0.12 + var(--level, 0) * 0.88) * var(--scale, 1) * 28px));
		opacity: 0.84;
	}
	.pulse-point {
		position: absolute;
		left: var(--progress, 0%);
		top: 50%;
		width: 0;
		height: 0;
	}
	.pulse-point span {
		position: absolute;
		left: 0;
		top: 0;
		width: calc(7px + var(--level, 0) * 22px);
		height: calc(7px + var(--level, 0) * 22px);
		border: 2px solid currentColor;
		border-radius: 50%;
		opacity: calc(0.12 + var(--level, 0) * 0.34);
		transform: translate(-50%, -50%);
		transition: width 80ms linear, height 80ms linear, opacity 80ms linear;
	}
	.pulse-point span + span {
		width: calc(5px + var(--level, 0) * 12px);
		height: calc(5px + var(--level, 0) * 12px);
		opacity: calc(0.24 + var(--level, 0) * 0.42);
	}
	.dot-row { gap: 6px; }
	.dot-row span {
		width: calc(3px + var(--sample, 0.1) * 4px);
		height: calc(3px + var(--sample, 0.1) * 4px);
		border-radius: 50%;
		background: currentColor;
		opacity: 0.82;
		transform: translateY(calc(var(--sample, 0.1) * -7px));
		transition: width 70ms linear, height 70ms linear, transform 70ms linear;
	}
	.dot-row span.alternate { transform: translateY(calc(var(--sample, 0.1) * 7px)); }

	@media (prefers-reduced-motion: reduce) {
		.sample-row span, .pulse-point span, .dot-row span { transition: none; }
	}
</style>
