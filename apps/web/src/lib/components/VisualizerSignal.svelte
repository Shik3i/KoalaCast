<script lang="ts">
	import type { VisualizerStyle } from '$lib/stores/prefs.svelte';

	let {
		style,
		level = 0,
		progress = 0,
		spectrum = [],
		peaks = [],
		playing = true,
		variant = 'compact'
	}: {
		style: VisualizerStyle;
		level?: number;
		progress?: number;
		/** Per-band energy, low frequencies first. Drives the bar styles. */
		spectrum?: number[];
		/** Slow-falling peak per band, same order and length as `spectrum`. */
		peaks?: number[];
		playing?: boolean;
		variant?: 'compact' | 'full' | 'preview';
	} = $props();

	// Settings previews and the first frames of playback have no audio to read.
	// A canned shape is better than an empty row, which reads as a broken control
	// rather than as a style you have not heard yet.
	const PREVIEW_BANDS = 48;
	const previewSpectrum = Array.from({ length: PREVIEW_BANDS }, (_, index) => {
		const t = index / (PREVIEW_BANDS - 1);
		return Math.max(0.12, (1 - t * 0.8) * (0.55 + 0.45 * Math.sin(index * 1.7)));
	});

	const bands = $derived(spectrum.length > 0 ? spectrum : previewSpectrum);
	const peakLine = $derived(peaks.length === bands.length ? peaks : bands);
	// Dots are the same signal on a coarser grid: every fourth band, so the row
	// stays a row of dots instead of turning into a dotted line.
	const dotBands = $derived(bands.filter((_, index) => index % 4 === 0));
</script>

{#if playing && style !== 'off' && style !== 'level'}
	<div
		class="signal"
		class:compact={variant === 'compact'}
		class:full={variant === 'full'}
		class:preview={variant === 'preview'}
		class:dots={style === 'dots'}
		style="--level: {Math.max(0, Math.min(1, level))}; --progress: {Math.max(0, Math.min(100, progress))}%"
		aria-hidden="true"
	>
		{#if style === 'waveform'}
			<div class="row">
				{#each bands as band}
					<span class="band" style="--band: {Math.max(0.04, band)}"><i class="fill wave"></i></span>
				{/each}
			</div>
		{:else if style === 'bars'}
			<div class="row">
				{#each bands as band, index}
					<span class="band" style="--band: {Math.max(0.04, band)}; --peak: {Math.max(0.04, peakLine[index] ?? band)}">
						<i class="fill"></i>
						<i class="peak"></i>
					</span>
				{/each}
			</div>
		{:else if style === 'pulse'}
			<div class="pulse-point"><span></span><span></span></div>
		{:else if style === 'dots'}
			<div class="row">
				{#each dotBands as band}
					<span class="band" style="--band: {Math.max(0.04, band)}"><i class="dot"></i></span>
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
	/*
	 * `width: 100%` is load-bearing. The other two variants are absolutely
	 * positioned with left/right pinned, so they stretch for free; the preview is
	 * in normal flow, and as a flex item it shrank to its content — 48 bands at
	 * 1px, a 47px smudge in the middle of a 394px panel.
	 */
	.signal.preview { position: relative; width: 100%; height: 30px; }

	/*
	 * The bands span the full width of whatever they are laid over. The old row
	 * was `justify-content: center` over eighteen 2px bars, which drew a ~70px
	 * smudge in the middle of a 900px player and read as a rendering fault.
	 * `flex: 1` on the children is what decouples the band count from the width:
	 * the same 48 bands fill a phone and a desktop without this component
	 * knowing which it is on.
	 */
	.row {
		display: flex;
		height: 100%;
		width: 100%;
		align-items: stretch;
		gap: 1px;
	}
	.band {
		position: relative;
		flex: 1 1 0;
		min-width: 0;
		height: 100%;
	}

	/*
	 * Height, not position: a bar meter's bars stay put and only change size.
	 * The previous build shifted every value one slot left per sample, so the
	 * shape crawled leftwards across the track like a seismograph — a fine way
	 * to show history and a poor way to show what is playing right now.
	 *
	 * Everything is centred on the same line the progress track runs along, so
	 * bars grow symmetrically out of it rather than hanging off it.
	 */
	.fill {
		position: absolute;
		left: 0;
		right: 0;
		top: 50%;
		height: max(2px, calc(var(--band, 0.1) * 100%));
		transform: translateY(-50%);
		border-radius: 999px;
		background: currentColor;
		opacity: 0.8;
	}
	/* The wave is context around the progress bar, not a competitor to it. */
	.fill.wave { opacity: 0.5; }
	/* A peak that falls back slowly is what makes a spectrum readable at a
	   glance rather than a blur of moving sticks. */
	.peak {
		position: absolute;
		left: 0;
		right: 0;
		height: 2px;
		top: calc(50% - var(--peak, 0) * 50%);
		border-radius: 999px;
		background: currentColor;
		opacity: 0.5;
	}
	.dot {
		position: absolute;
		left: 50%;
		top: 50%;
		width: max(3px, calc(var(--band, 0.1) * 34%));
		aspect-ratio: 1;
		transform: translate(-50%, -50%);
		border-radius: 50%;
		background: currentColor;
		opacity: 0.82;
	}
	/* Dots are round, so they need breathing room rather than a 1px gutter. */
	.signal.dots .row { gap: 4px; }

	.pulse-point {
		position: absolute;
		left: var(--progress, 0%);
		top: 50%;
		width: 0;
		height: 0;
	}
	/*
	 * The floor on both size and opacity is the point. The old curve bottomed out
	 * near 0.12 opacity on a 7px ring, which is invisible at the conversational
	 * level this app spends its life playing — the style looked broken unless
	 * something shouted.
	 */
	.pulse-point span {
		position: absolute;
		left: 0;
		top: 0;
		width: calc(12px + var(--level, 0) * 22px);
		height: calc(12px + var(--level, 0) * 22px);
		border: 1.5px solid currentColor;
		border-radius: 50%;
		opacity: calc(0.28 + var(--level, 0) * 0.32);
		transform: translate(-50%, -50%);
		transition: width 60ms linear, height 60ms linear, opacity 60ms linear;
	}
	.pulse-point span + span {
		width: calc(12px + var(--level, 0) * 10px);
		height: calc(12px + var(--level, 0) * 10px);
		opacity: calc(0.5 + var(--level, 0) * 0.4);
	}

	@media (prefers-reduced-motion: reduce) {
		.pulse-point span { transition: none; }
	}
</style>
