<script lang="ts">
	import type { VisualizerStyle } from '$lib/stores/prefs.svelte';
	import { visualizerFrame } from '$lib/audio/visualizer-frame';
	import {
		PREVIEW_BANDS,
		PREVIEW_LEVEL,
		PREVIEW_PEAKS,
		VisualizerPainter,
		type RenderInput,
		type VisualizerVariant
	} from '$lib/audio/visualizer-render';

	let {
		style,
		playing = true,
		variant = 'compact'
	}: {
		style: VisualizerStyle;
		playing?: boolean;
		variant?: VisualizerVariant;
	} = $props();

	/**
	 * The audio numbers are read from `visualizerFrame` inside the frame callback,
	 * not passed in as props.
	 *
	 * They change on every display frame, and a prop that changes every frame is a
	 * reactive invalidation every frame — which, when the props were the band arrays
	 * themselves, meant re-rendering forty-eight DOM nodes and forcing forty-eight
	 * layouts to move some rectangles. Keeping them out of the component's reactive
	 * graph entirely is what lets this run at the display's rate instead of at a
	 * hand-set 30 Hz.
	 */
	let canvas: HTMLCanvasElement | null = $state(null);
	let host: HTMLElement | null = $state(null);
	const painter = new VisualizerPainter();

	const preview: RenderInput = {
		bands: PREVIEW_BANDS,
		peaks: PREVIEW_PEAKS,
		level: PREVIEW_LEVEL
	};

	/**
	 * Motion for its own sake is exactly what this preference is for, so honouring
	 * it means falling back to a still frame rather than to a slower animation.
	 * The Android client makes the same call for the same reason.
	 */
	const reducedMotion =
		typeof window !== 'undefined' && window.matchMedia
			? window.matchMedia('(prefers-reduced-motion: reduce)')
			: null;

	$effect(() => {
		const element = canvas;
		const container = host;
		const currentStyle = style;
		const currentVariant = variant;
		const isPlaying = playing;
		if (!element || !container) return;

		const context = element.getContext('2d', { alpha: true });
		if (!context) return;

		let width = 0;
		let height = 0;
		let colour = '';
		let frame = 0;
		let lastDrawnRevision = -1;

		const measure = () => {
			const rect = container.getBoundingClientRect();
			// Cap the backing store at 2x. Beyond that the extra pixels are invisible
			// on a phone and the fill rate is not: a 3x device would otherwise paint
			// nine times the area of a CSS pixel for every frame of an animation that
			// runs for the length of an episode.
			const ratio = Math.min(2, window.devicePixelRatio || 1);
			const nextWidth = Math.max(1, Math.round(rect.width));
			const nextHeight = Math.max(1, Math.round(rect.height));
			if (nextWidth === width && nextHeight === height) return;
			width = nextWidth;
			height = nextHeight;
			element.width = Math.round(width * ratio);
			element.height = Math.round(height * ratio);
			element.style.width = `${width}px`;
			element.style.height = `${height}px`;
			context.setTransform(ratio, 0, 0, ratio, 0, 0);
			lastDrawnRevision = -1;
		};

		// `currentColor` on the canvas element resolves the show accent and the theme
		// for us; reading it back is cheaper and less brittle than threading the
		// palette through as a prop. It is re-read only on resize and style change.
		const readColour = () => {
			colour = getComputedStyle(element).color || '';
		};

		measure();
		readColour();

		const observer = new ResizeObserver(() => {
			measure();
			readColour();
			paintStill();
		});
		observer.observe(container);

		const wantsSpectrum = currentStyle !== 'level' && currentStyle !== 'pulse';
		const wantsLevel = currentStyle === 'level' || currentStyle === 'pulse';

		const paintStill = () => {
			painter.draw(context, currentStyle, preview, width, height, colour, currentVariant);
		};

		if (currentVariant === 'preview' || !isPlaying || reducedMotion?.matches) {
			paintStill();
			return () => observer.disconnect();
		}

		const tick = (timestamp: number) => {
			frame = requestAnimationFrame(tick);
			visualizerFrame.sample(timestamp, wantsSpectrum, wantsLevel);
			// Two visualisers can be mounted at once — the bar behind the expanded
			// player. The second one's `sample` is a no-op, and this keeps it from
			// repainting a canvas whose contents cannot have changed.
			if (visualizerFrame.revision === lastDrawnRevision) return;
			lastDrawnRevision = visualizerFrame.revision;
			painter.draw(context, currentStyle, visualizerFrame, width, height, colour, currentVariant);
		};
		frame = requestAnimationFrame(tick);

		return () => {
			cancelAnimationFrame(frame);
			observer.disconnect();
		};
	});
</script>

{#if style !== 'off' && (playing || variant === 'preview')}
	<div class="signal {variant}" bind:this={host} data-visualizer={style} aria-hidden="true">
		<canvas bind:this={canvas}></canvas>
	</div>
{/if}

<style>
	.signal {
		position: relative;
		display: block;
		width: 100%;
		height: 100%;
		min-width: 0;
		overflow: hidden;
		pointer-events: none;
		/* The painter reads this back off the canvas, so the accent still comes from
		   the cascade rather than from a prop. */
		color: var(--show-accent, var(--accent-green));
	}

	.signal canvas {
		display: block;
	}
</style>
