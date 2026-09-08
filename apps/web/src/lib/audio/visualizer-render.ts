import type { VisualizerStyle } from '$lib/stores/prefs.svelte';

/**
 * Every visualiser style, drawn into a 2D canvas context.
 *
 * A canvas rather than the DOM. The previous renderer built one `<span>` per bar
 * and drove it with an inline `style` string, so a frame cost forty-eight style
 * recalculations and forty-eight layouts — geometric properties, `height` and
 * `bottom`, which are the two the browser cannot composite around. That is why it
 * had to be capped at 30 Hz, and why it still cost too much on a phone. One canvas
 * costs one paint whatever the bar count, so the cap could go.
 *
 * Nothing here allocates per frame except the cached gradients, which are keyed by
 * size and colour and rebuilt only when one of those changes.
 */

export interface Rgb {
	r: number;
	g: number;
	b: number;
}

export type VisualizerVariant = 'compact' | 'full' | 'preview';

export interface RenderInput {
	bands: Float32Array | number[];
	peaks: Float32Array | number[];
	level: number;
}

/**
 * Normalises any CSS colour to its components.
 *
 * Round-tripping through `fillStyle` is the reliable way to do this: the canvas
 * serialises whatever it is given — a named colour, `oklch()`, a `color-mix()`
 * already resolved by `getComputedStyle` — back out as `#rrggbb` or
 * `rgba(r, g, b, a)`, so there is no colour syntax to parse by hand.
 */
export function parseColour(ctx: CanvasRenderingContext2D, colour: string): Rgb {
	const previous = ctx.fillStyle;
	ctx.fillStyle = '#7dd3a0';
	try {
		ctx.fillStyle = colour;
	} catch {
		/* left at the fallback */
	}
	const normalised = ctx.fillStyle;
	ctx.fillStyle = previous;

	if (typeof normalised === 'string') {
		if (normalised.startsWith('#') && normalised.length >= 7) {
			return {
				r: parseInt(normalised.slice(1, 3), 16),
				g: parseInt(normalised.slice(3, 5), 16),
				b: parseInt(normalised.slice(5, 7), 16)
			};
		}
		const parts = normalised.match(/[\d.]+/g);
		if (parts && parts.length >= 3) {
			return { r: Number(parts[0]), g: Number(parts[1]), b: Number(parts[2]) };
		}
	}
	return { r: 125, g: 211, b: 160 };
}

const rgba = (c: Rgb, alpha: number) => `rgba(${c.r}, ${c.g}, ${c.b}, ${alpha})`;

/**
 * How many bars a variant draws.
 *
 * The compact player is sixteen pixels tall and a couple of hundred wide. Forty
 * eight bars in that space is a bar every four pixels with a gap that rounds to
 * nothing, which is a texture rather than a spectrum — one of the reasons the
 * styles read as mush in the bar and as themselves only in the expanded player.
 * Bands are averaged down rather than sampled, so the shape stays the same shape.
 */
function barCount(variant: VisualizerVariant, width: number): number {
	const target = variant === 'compact' ? 24 : 48;
	// Never so many that a bar plus its gap falls below three physical pixels.
	return Math.max(8, Math.min(target, Math.floor(width / 3)));
}

/** Averages `source` down to `count` buckets, into the reusable `out`. */
function resample(source: ArrayLike<number>, out: Float32Array, count: number) {
	const ratio = source.length / count;
	for (let index = 0; index < count; index++) {
		const start = Math.floor(index * ratio);
		const end = Math.max(start + 1, Math.floor((index + 1) * ratio));
		let peak = 0;
		for (let position = start; position < end && position < source.length; position++) {
			// Peak rather than mean: averaging buries the transient, and the
			// transient is the part a listener recognises.
			if (source[position] > peak) peak = source[position];
		}
		out[index] = peak;
	}
}

/**
 * Caches the gradients and scratch buffers a canvas needs, so a frame allocates
 * nothing. Rebuilt when the size or the accent colour changes, which is rare.
 */
export class VisualizerPainter {
	private width = 0;
	private height = 0;
	private key = '';
	private colour: Rgb = { r: 125, g: 211, b: 160 };
	private vertical: CanvasGradient | null = null;
	private rising: CanvasGradient | null = null;
	private readonly bars = new Float32Array(64);
	private readonly caps = new Float32Array(64);

	/** Re-derives everything that depends on size or colour. Cheap to call often. */
	private ensure(ctx: CanvasRenderingContext2D, width: number, height: number, colour: string) {
		const key = `${width}x${height}|${colour}`;
		if (key === this.key) return;
		this.key = key;
		this.width = width;
		this.height = height;
		this.colour = parseColour(ctx, colour);

		// Bright through the middle, falling away at both edges: a column shaded
		// this way reads as light coming through a slot. A single flat fill is what
		// made every style look like the same row of rectangles.
		this.vertical = ctx.createLinearGradient(0, 0, 0, height);
		this.vertical.addColorStop(0, rgba(this.colour, 0.28));
		this.vertical.addColorStop(0.5, rgba(this.colour, 1));
		this.vertical.addColorStop(1, rgba(this.colour, 0.28));

		// For shapes that stand on a baseline: solid where they meet it, thinning
		// toward the tip, so height reads as weight and not only as size.
		this.rising = ctx.createLinearGradient(0, height, 0, 0);
		this.rising.addColorStop(0, rgba(this.colour, 0.95));
		this.rising.addColorStop(0.65, rgba(this.colour, 0.72));
		this.rising.addColorStop(1, rgba(this.colour, 0.4));
	}

	draw(
		ctx: CanvasRenderingContext2D,
		style: VisualizerStyle,
		input: RenderInput,
		width: number,
		height: number,
		colour: string,
		variant: VisualizerVariant
	) {
		if (width <= 0 || height <= 0) return;
		this.ensure(ctx, width, height, colour);
		ctx.clearRect(0, 0, width, height);
		ctx.lineJoin = 'round';
		ctx.lineCap = 'round';

		switch (style) {
			case 'level':
				return this.drawLevel(ctx, input.level, width, height);
			case 'waveform':
				return this.drawWave(ctx, input, width, height, variant);
			case 'bars':
				return this.drawBars(ctx, input, width, height, variant);
			case 'pulse':
				return this.drawPulse(ctx, input.level, width, height);
			case 'spectrum':
				return this.drawMirrored(ctx, input, width, height, variant);
			case 'ribbon':
				return this.drawRibbon(ctx, input, width, height, variant);
			case 'vu':
				return this.drawVu(ctx, input, width, height);
			case 'constellation':
				return this.drawConstellation(ctx, input, width, height);
			default:
				return;
		}
	}

	/** A row of segments filling left to right, with the leading one brightest. */
	private drawLevel(ctx: CanvasRenderingContext2D, level: number, width: number, height: number) {
		const segments = Math.max(8, Math.min(24, Math.floor(width / 9)));
		const gap = Math.max(1, width * 0.004);
		const segmentWidth = (width - gap * (segments - 1)) / segments;
		const lit = level * segments;
		const barHeight = height * 0.72;
		const top = (height - barHeight) / 2;

		for (let index = 0; index < segments; index++) {
			// Partial illumination of the leading segment rather than a hard step.
			// With whole segments only, a meter this short has eight or nine visible
			// states and spends most of a sentence sitting on one of them.
			const fill = Math.max(0, Math.min(1, lit - index));
			ctx.fillStyle = rgba(this.colour, 0.13 + fill * 0.8);
			roundRect(ctx, index * (segmentWidth + gap), top, segmentWidth, barHeight, Math.min(2, segmentWidth / 2));
			ctx.fill();
		}
	}

	/** A filled, mirrored curve: low frequencies at the left, high at the right. */
	private drawWave(
		ctx: CanvasRenderingContext2D,
		input: RenderInput,
		width: number,
		height: number,
		variant: VisualizerVariant
	) {
		const count = barCount(variant, width);
		resample(input.bands, this.bars, count);
		const centre = height / 2;
		// The full half-height, against the ribbon's narrow band: the two styles are
		// both mirrored and filled, so the silhouette is what has to separate them.
		const reach = height / 2 - 1;
		const step = width / (count - 1);
		const at = (index: number) => Math.max(0.6, Math.min(1, this.bars[index]) * reach);

		const contour = (direction: number) => {
			ctx.moveTo(0, centre + direction * at(0));
			for (let index = 0; index < count - 1; index++) {
				const x = index * step;
				const mid = x + step / 2;
				// Horizontal control points at the midpoint keep the curve monotone
				// between samples, so a loud band cannot make the line overshoot past
				// the axis and cross its own mirror.
				ctx.bezierCurveTo(
					mid,
					centre + direction * at(index),
					mid,
					centre + direction * at(index + 1),
					x + step,
					centre + direction * at(index + 1)
				);
			}
		};

		ctx.beginPath();
		contour(-1);
		// Down the right-hand edge before walking back. Without this the closing
		// segment ran diagonally from the top right corner to the bottom left of the
		// previous sample and clipped the end of the shape off.
		ctx.lineTo(width, centre + at(count - 1));
		for (let index = count - 1; index > 0; index--) {
			const x = index * step;
			const mid = x - step / 2;
			ctx.bezierCurveTo(mid, centre + at(index), mid, centre + at(index - 1), x - step, centre + at(index - 1));
		}
		ctx.closePath();
		ctx.fillStyle = this.vertical!;
		ctx.globalAlpha = 0.42;
		ctx.fill();
		ctx.globalAlpha = 1;

		// Both contours, and both bright. This is the style called "waveform"; with
		// only the upper edge drawn it read as a filled area chart, which is a
		// different thing and was hard to tell apart from the ribbon.
		ctx.lineWidth = Math.max(1.25, height * 0.05);
		ctx.strokeStyle = rgba(this.colour, 0.95);
		ctx.beginPath();
		contour(-1);
		ctx.stroke();
		ctx.beginPath();
		contour(1);
		ctx.stroke();
	}

	/** The classic equaliser: bars on a baseline, with peak markers above them. */
	private drawBars(
		ctx: CanvasRenderingContext2D,
		input: RenderInput,
		width: number,
		height: number,
		variant: VisualizerVariant
	) {
		const count = barCount(variant, width);
		resample(input.bands, this.bars, count);
		resample(input.peaks, this.caps, count);

		const slot = width / count;
		const gap = Math.min(slot * 0.3, Math.max(1, width * 0.003));
		const barWidth = Math.max(1, slot - gap);
		const capHeight = Math.max(1, height * 0.05);
		const headroom = height - capHeight * 2;
		const radius = Math.min(barWidth / 2, 2);

		for (let index = 0; index < count; index++) {
			const energy = Math.min(1, this.bars[index]);
			const x = index * slot + gap / 2;
			// A floor rather than zero: silence should read as a quiet bar, not as a
			// hole in the row.
			const barHeight = Math.max(1.5, headroom * energy);
			ctx.fillStyle = this.rising!;
			// Loud bars sit near solid and quiet ones stay well back, so a busy
			// passage has contrast inside it rather than being one flat block.
			ctx.globalAlpha = 0.45 + energy * 0.55;
			roundRect(ctx, x, height - barHeight, barWidth, barHeight, radius);
			ctx.fill();

			// A peak that falls back slowly is what makes a spectrum readable at a
			// glance rather than a blur of moving sticks.
			const peak = Math.max(barHeight + capHeight, headroom * Math.min(1, this.caps[index]));
			ctx.globalAlpha = 0.65;
			ctx.fillStyle = rgba(this.colour, 1);
			roundRect(ctx, x, height - peak - capHeight, barWidth, capHeight, capHeight / 2);
			ctx.fill();
		}
		ctx.globalAlpha = 1;
	}

	/** Rings travelling out from a core that swells with the recording. */
	private drawPulse(ctx: CanvasRenderingContext2D, level: number, width: number, height: number) {
		const centreX = width / 2;
		const centreY = height / 2;
		const strength = Math.max(0, Math.min(1, level));
		const unit = height / 2;

		// Rails, so the core sits on something rather than floating in an empty box.
		const railGap = unit * 1.9;
		ctx.lineWidth = 1;
		for (const direction of [-1, 1]) {
			const gradient = ctx.createLinearGradient(centreX + direction * railGap, 0, centreX + direction * width, 0);
			gradient.addColorStop(0, rgba(this.colour, 0.45));
			gradient.addColorStop(1, rgba(this.colour, 0));
			ctx.strokeStyle = gradient;
			ctx.beginPath();
			ctx.moveTo(centreX + direction * railGap, centreY);
			ctx.lineTo(centreX + (direction * width) / 2, centreY);
			ctx.stroke();
		}

		// Three rings at staggered radii read as something travelling outward; two
		// read as a thick circle.
		ctx.lineWidth = Math.max(1, unit * 0.08);
		for (let ring = 0; ring < 3; ring++) {
			const spread = ring / 2;
			const radius = unit * (0.28 + spread * 0.3 + strength * (0.22 + spread * 0.34));
			ctx.strokeStyle = rgba(this.colour, (0.4 + strength * 0.42) * (1 - spread * 0.62));
			ctx.beginPath();
			ctx.arc(centreX, centreY, radius, 0, Math.PI * 2);
			ctx.stroke();
		}
		ctx.fillStyle = rgba(this.colour, 0.95);
		ctx.beginPath();
		ctx.arc(centreX, centreY, Math.max(1.2, unit * 0.1), 0, Math.PI * 2);
		ctx.fill();
	}

	/** Symmetric columns about the centre axis, bright in the middle. */
	private drawMirrored(
		ctx: CanvasRenderingContext2D,
		input: RenderInput,
		width: number,
		height: number,
		variant: VisualizerVariant
	) {
		const count = barCount(variant, width);
		resample(input.bands, this.bars, count);
		const centre = height / 2;
		const slot = width / count;
		const gap = Math.min(slot * 0.35, Math.max(1, width * 0.003));
		const columnWidth = Math.max(1, slot - gap);

		ctx.fillStyle = this.vertical!;
		for (let index = 0; index < count; index++) {
			const energy = Math.min(1, this.bars[index]);
			const columnHeight = Math.max(1.5, energy * height * 0.94);
			ctx.globalAlpha = 0.5 + energy * 0.5;
			roundRect(
				ctx,
				index * slot + gap / 2,
				centre - columnHeight / 2,
				columnWidth,
				columnHeight,
				Math.min(columnWidth / 2, 2)
			);
			ctx.fill();
		}
		ctx.globalAlpha = 1;
	}

	/**
	 * A filled body between two mirrored contours, the upper drawn firmly and the
	 * lower as an echo.
	 *
	 * Straight segments here rather than the wave's curves, deliberately: with both
	 * styles curved and filled they were the same drawing at two opacities.
	 */
	private drawRibbon(
		ctx: CanvasRenderingContext2D,
		input: RenderInput,
		width: number,
		height: number,
		variant: VisualizerVariant
	) {
		const count = barCount(variant, width);
		resample(input.bands, this.bars, count);
		const centre = height / 2;
		const step = width / (count - 1);
		const at = (index: number) => 1 + Math.min(1, this.bars[index]) * height * 0.3;

		ctx.beginPath();
		for (let index = 0; index < count; index++) {
			const x = index * step;
			if (index === 0) ctx.moveTo(x, centre - at(index));
			else ctx.lineTo(x, centre - at(index));
		}
		for (let index = count - 1; index >= 0; index--) ctx.lineTo(index * step, centre + at(index));
		ctx.closePath();
		ctx.fillStyle = this.vertical!;
		ctx.globalAlpha = 0.2;
		ctx.fill();
		ctx.globalAlpha = 1;

		ctx.lineWidth = Math.max(1, height * 0.04);
		for (const [direction, alpha] of [
			[-1, 0.85],
			[1, 0.34]
		] as const) {
			ctx.beginPath();
			for (let index = 0; index < count; index++) {
				const x = index * step;
				const y = centre + direction * at(index);
				if (index === 0) ctx.moveTo(x, y);
				else ctx.lineTo(x, y);
			}
			ctx.strokeStyle = rgba(this.colour, alpha);
			ctx.stroke();
		}
	}

	/** Two segmented meters, the low half of the spectrum over the high half. */
	private drawVu(ctx: CanvasRenderingContext2D, input: RenderInput, width: number, height: number) {
		const source = input.bands;
		const half = Math.max(1, Math.floor(source.length / 2));
		let lowSum = 0;
		for (let index = 0; index < half; index++) lowSum += source[index];
		let highSum = 0;
		for (let index = half; index < source.length; index++) highSum += source[index];
		const low = Math.min(1, (lowSum / half) * 1.35);
		const high = Math.min(1, (highSum / Math.max(1, source.length - half)) * 1.8);

		const segments = Math.max(10, Math.min(20, Math.floor(width / 10)));
		const gap = Math.max(1, width * 0.004);
		const segmentWidth = (width - gap * (segments - 1)) / segments;
		// The lanes fill the height they are given. They used to be pinned a few
		// pixels either side of the exact middle at a fixed size, which left most of
		// the box empty and read as a rendering fault.
		const laneGap = Math.max(2, height * 0.14);
		const laneHeight = (height - laneGap) / 2;

		for (const [lane, strength] of [
			[0, high],
			[1, low]
		] as const) {
			const y = lane * (laneHeight + laneGap);
			for (let index = 0; index < segments; index++) {
				const threshold = (index + 1) / segments;
				const lit = threshold <= strength;
				// The last few segments are headroom marks; lighting them has to look
				// like a different event from lighting the body of the meter.
				const alpha = lit ? (index >= segments - 4 ? 1 : 0.8) : 0.14;
				ctx.fillStyle = rgba(this.colour, alpha);
				roundRect(ctx, index * (segmentWidth + gap), y, segmentWidth, laneHeight, Math.min(2, laneHeight / 2));
				ctx.fill();
			}
		}
	}

	/** Energy points spread across the width, threaded together. */
	private drawConstellation(
		ctx: CanvasRenderingContext2D,
		input: RenderInput,
		width: number,
		height: number
	) {
		const points = 18;
		resample(input.bands, this.bars, points);
		const centre = height / 2;
		const reach = height * 0.38;
		// Thirds rather than a strict up/down flip. Strict alternation produced a
		// zigzag of constant shape whose only variable was its amplitude, which is
		// not what the name promises and not interesting to look at.
		const sides = [-1, 0.45, 1, -0.45];
		const x = (index: number) => 2 + (index * (width - 4)) / (points - 1);
		const y = (index: number) => centre + sides[index % sides.length] * Math.min(1, this.bars[index]) * reach;

		ctx.beginPath();
		for (let index = 0; index < points; index++) {
			if (index === 0) ctx.moveTo(x(index), y(index));
			else ctx.lineTo(x(index), y(index));
		}
		ctx.lineWidth = 1;
		ctx.strokeStyle = rgba(this.colour, 0.3);
		ctx.stroke();

		const base = Math.max(1.1, height * 0.045);
		for (let index = 0; index < points; index++) {
			const energy = Math.min(1, this.bars[index]);
			// A wide, faint disc under a small solid one. One flat dot at a fixed
			// radius disappears against a busy background; the halo is what makes the
			// point read as a light rather than as a speck of paint.
			ctx.fillStyle = rgba(this.colour, 0.12 + energy * 0.16);
			ctx.beginPath();
			ctx.arc(x(index), y(index), base + energy * base * 3.4, 0, Math.PI * 2);
			ctx.fill();
			ctx.fillStyle = rgba(this.colour, 0.7 + energy * 0.3);
			ctx.beginPath();
			ctx.arc(x(index), y(index), base + energy * base * 1.4, 0, Math.PI * 2);
			ctx.fill();
		}
	}
}

/** `roundRect` with a fallback, and a radius that can never exceed the box. */
function roundRect(
	ctx: CanvasRenderingContext2D,
	x: number,
	y: number,
	width: number,
	height: number,
	radius: number
) {
	const r = Math.max(0, Math.min(radius, width / 2, height / 2));
	ctx.beginPath();
	if (typeof ctx.roundRect === 'function') {
		ctx.roundRect(x, y, width, height, r);
		return;
	}
	ctx.moveTo(x + r, y);
	ctx.arcTo(x + width, y, x + width, y + height, r);
	ctx.arcTo(x + width, y + height, x, y + height, r);
	ctx.arcTo(x, y + height, x, y, r);
	ctx.arcTo(x, y, x + width, y, r);
	ctx.closePath();
}

/** A canned spectrum that looks like speech: loud low end, rolling off upwards. */
export const PREVIEW_BANDS = Float32Array.from({ length: 48 }, (_, index) => {
	const t = index / 47;
	return Math.max(0.12, Math.min(1, (1 - t * 0.8) * (0.55 + 0.45 * Math.sin(index * 1.7))));
});
export const PREVIEW_PEAKS = PREVIEW_BANDS.map((value) => Math.min(1, value + 0.12));
export const PREVIEW_LEVEL = 0.7;
