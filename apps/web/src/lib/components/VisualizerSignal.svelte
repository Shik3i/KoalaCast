<script lang="ts">
	import type { VisualizerStyle } from '$lib/stores/prefs.svelte';

	let {
		style,
		level = 0,
		spectrum = [],
		peaks = [],
		playing = true,
		variant = 'compact'
	}: {
		style: VisualizerStyle;
		level?: number;
		spectrum?: number[];
		peaks?: number[];
		playing?: boolean;
		variant?: 'compact' | 'full' | 'preview';
	} = $props();

	const PREVIEW_BANDS = 32;
	const previewSpectrum = Array.from({ length: PREVIEW_BANDS }, (_, index) => {
		const t = index / (PREVIEW_BANDS - 1);
		return Math.max(0.1, (0.72 - Math.abs(t - 0.42) * 0.7) * (0.72 + 0.28 * Math.sin(index * 1.43)));
	});
	const displayBands = $derived.by(() => {
		const source = spectrum.some((band) => band > 0.001) ? spectrum : variant === 'preview' ? previewSpectrum : spectrum;
		return source.length > 0 ? source : new Array(PREVIEW_BANDS).fill(0);
	});
	const peakLine = $derived(peaks.length === displayBands.length ? peaks : displayBands);
	const normalizedLevel = $derived(Math.max(0, Math.min(1, level)));

	const wavePath = $derived.by(() => {
		if (displayBands.length < 2) return '';
		const step = 100 / (displayBands.length - 1);
		const point = (index: number) => 50 - Math.min(1, displayBands[index]) * 42;
		let path = `M 0 ${point(0)}`;
		for (let index = 0; index < displayBands.length - 1; index++) {
			const midpoint = (index + 0.5) * step;
			path += ` C ${midpoint} ${point(index)} ${midpoint} ${point(index + 1)} ${(index + 1) * step} ${point(index + 1)}`;
		}
		return path;
	});

	const LEVEL_SEGMENTS = 18;
</script>

{#if playing && style !== 'off'}
	<div
		class="signal {variant} signal-{style}"
		style="--level: {normalizedLevel}"
		data-visualizer={style}
		aria-hidden="true"
	>
		{#if style === 'level'}
			<div class="level-meter">
				{#each Array(LEVEL_SEGMENTS) as _, index}
					<span class:active={(index + 1) / LEVEL_SEGMENTS <= normalizedLevel}></span>
				{/each}
			</div>
		{:else if style === 'waveform'}
			<svg class="wave" viewBox="0 0 100 100" preserveAspectRatio="none">
				<path class="wave-shadow" d={wavePath} transform="translate(0 100) scale(1 -1)" />
				<path d={wavePath} />
			</svg>
		{:else if style === 'bars'}
			<div class="spectrum">
				{#each displayBands as band, index}
					<span style="--band: {Math.max(0.035, band)}; --peak: {Math.max(0.035, peakLine[index] ?? band)}">
						<i class="bar-fill"></i><i class="bar-peak"></i>
					</span>
				{/each}
			</div>
		{:else if style === 'pulse'}
			<div class="pulse-rail left"></div>
			<div class="pulse-core"><span></span><span></span><i></i></div>
			<div class="pulse-rail right"></div>
		{/if}
	</div>
{/if}

<style>
	.signal {
		position: relative;
		display: flex;
		align-items: center;
		justify-content: center;
		width: 100%;
		height: 100%;
		min-width: 0;
		overflow: hidden;
		pointer-events: none;
		color: var(--show-accent, var(--accent-green));
	}

	.level-meter {
		display: grid;
		grid-template-columns: repeat(18, 1fr);
		align-items: center;
		gap: 3px;
		width: 100%;
		height: 58%;
	}
	.level-meter span {
		height: 100%;
		border-radius: 2px;
		background: color-mix(in srgb, currentColor 14%, transparent);
		transition: background 70ms linear, opacity 70ms linear;
	}
	.level-meter span.active {
		background: currentColor;
		opacity: calc(0.58 + var(--level) * 0.38);
	}

	.wave { display: block; width: 100%; height: 100%; overflow: visible; }
	.wave path {
		fill: none;
		stroke: currentColor;
		stroke-width: 1.6;
		stroke-linecap: round;
		stroke-linejoin: round;
		vector-effect: non-scaling-stroke;
		opacity: 0.9;
	}
	.wave .wave-shadow { opacity: 0.22; }

	.spectrum {
		display: flex;
		align-items: stretch;
		gap: 2px;
		width: 100%;
		height: 100%;
	}
	.spectrum > span { position: relative; flex: 1 1 0; min-width: 1px; height: 100%; }
	.bar-fill {
		position: absolute;
		left: 0;
		right: 0;
		bottom: 0;
		height: max(2px, calc(var(--band) * 100%));
		border-radius: 2px 2px 1px 1px;
		background: currentColor;
		opacity: 0.72;
	}
	.bar-peak {
		position: absolute;
		left: 0;
		right: 0;
		bottom: calc(var(--peak) * 100%);
		height: 1px;
		background: currentColor;
		opacity: 0.42;
	}

	.signal-pulse { gap: 10px; }
	.pulse-rail {
		flex: 1;
		height: 1px;
		background: linear-gradient(90deg, transparent, color-mix(in srgb, currentColor 45%, transparent));
	}
	.pulse-rail.right { transform: scaleX(-1); }
	.pulse-core { position: relative; width: 28px; height: 28px; flex: 0 0 28px; }
	.pulse-core span,
	.pulse-core i {
		position: absolute;
		inset: 50% auto auto 50%;
		border-radius: 50%;
		transform: translate(-50%, -50%);
	}
	.pulse-core span {
		width: calc(8px + var(--level) * 18px);
		height: calc(8px + var(--level) * 18px);
		border: 1px solid currentColor;
		opacity: calc(0.22 + var(--level) * 0.35);
		transition: width 70ms linear, height 70ms linear, opacity 70ms linear;
	}
	.pulse-core span + span { width: calc(5px + var(--level) * 10px); height: calc(5px + var(--level) * 10px); opacity: 0.55; }
	.pulse-core i { width: 3px; height: 3px; background: currentColor; }

	@media (prefers-reduced-motion: reduce) {
		.level-meter span, .pulse-core span { transition: none; }
	}
</style>
