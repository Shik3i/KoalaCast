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
	const average = (values: number[]) => values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
	const vuLow = $derived(Math.min(1, average(displayBands.slice(0, Math.ceil(displayBands.length / 2))) * 1.35));
	const vuHigh = $derived(Math.min(1, average(displayBands.slice(Math.ceil(displayBands.length / 2))) * 1.8));

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
	const ribbonPath = $derived.by(() => {
		if (displayBands.length < 2) return '';
		const step = 100 / (displayBands.length - 1);
		const height = (index: number) => 8 + Math.min(1, displayBands[index]) * 40;
		let path = `M 0 ${50 - height(0)}`;
		for (let index = 1; index < displayBands.length; index++) path += ` L ${index * step} ${50 - height(index)}`;
		for (let index = displayBands.length - 1; index >= 0; index--) path += ` L ${index * step} ${50 + height(index)}`;
		return `${path} Z`;
	});
	const constellation = $derived.by(() => Array.from({ length: 18 }, (_, index) => {
		const bandIndex = Math.round(index * (displayBands.length - 1) / 17);
		const energy = Math.min(1, displayBands[bandIndex] ?? 0);
		return {
			x: 3 + index * 94 / 17,
			y: 50 + (index % 2 === 0 ? -1 : 1) * energy * 34,
			radius: 1.25 + energy * 2.5
		};
	}));
	const constellationPath = $derived(constellation.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' '));

	const LEVEL_SEGMENTS = 18;
	const VU_SEGMENTS = 20;
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
		{:else if style === 'spectrum'}
			<div class="mirror-spectrum">
				{#each displayBands as band}
					<span style="--band: {Math.max(0.035, band)}"><i></i></span>
				{/each}
			</div>
		{:else if style === 'ribbon'}
			<svg class="ribbon" viewBox="0 0 100 100" preserveAspectRatio="none">
				<path class="ribbon-glow" d={ribbonPath} />
				<path class="ribbon-edge" d={wavePath} />
				<path class="ribbon-edge lower" d={wavePath} transform="translate(0 100) scale(1 -1)" />
			</svg>
		{:else if style === 'vu'}
			<div class="vu-meter">
				<div class="vu-lane">{#each Array(VU_SEGMENTS) as _, index}<span class:active={(index + 1) / VU_SEGMENTS <= vuLow}></span>{/each}</div>
				<div class="vu-lane">{#each Array(VU_SEGMENTS) as _, index}<span class:active={(index + 1) / VU_SEGMENTS <= vuHigh}></span>{/each}</div>
			</div>
		{:else if style === 'constellation'}
			<svg class="constellation" viewBox="0 0 100 100" preserveAspectRatio="none">
				<path d={constellationPath} />
				{#each constellation as point}
					<circle cx={point.x} cy={point.y} r={point.radius} />
				{/each}
			</svg>
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

	.mirror-spectrum {
		display: flex;
		align-items: stretch;
		gap: 2px;
		width: 100%;
		height: 100%;
	}
	.mirror-spectrum span { position: relative; flex: 1 1 0; min-width: 1px; height: 100%; }
	.mirror-spectrum i {
		position: absolute;
		left: 0;
		right: 0;
		top: 50%;
		height: max(2px, calc(var(--band) * 92%));
		transform: translateY(-50%);
		border-radius: 2px;
		background: linear-gradient(to bottom, color-mix(in srgb, currentColor 38%, transparent), currentColor 50%, color-mix(in srgb, currentColor 38%, transparent));
		opacity: 0.82;
	}

	.ribbon, .constellation { display: block; width: 100%; height: 100%; overflow: visible; }
	.ribbon-glow { fill: currentColor; fill-opacity: 0.24; stroke: none; }
	.ribbon-edge {
		fill: none;
		stroke: currentColor;
		stroke-width: 1.25;
		stroke-linecap: round;
		vector-effect: non-scaling-stroke;
		opacity: 0.75;
	}
	.ribbon-edge.lower { opacity: 0.3; }

	.vu-meter { display: grid; gap: 3px; width: 100%; }
	.vu-lane { display: grid; grid-template-columns: repeat(20, 1fr); gap: 2px; height: 4px; }
	.vu-lane span { border-radius: 1px; background: color-mix(in srgb, currentColor 12%, transparent); }
	.vu-lane span.active { background: currentColor; opacity: 0.78; }
	.vu-lane span.active:nth-last-child(-n + 4) { opacity: 1; }

	.constellation path {
		fill: none;
		stroke: currentColor;
		stroke-width: 1;
		vector-effect: non-scaling-stroke;
		opacity: 0.4;
	}
	.constellation circle { fill: currentColor; opacity: 0.96; vector-effect: non-scaling-stroke; }

	@media (prefers-reduced-motion: reduce) {
		.level-meter span, .pulse-core span, .vu-lane span { transition: none; }
	}
</style>
