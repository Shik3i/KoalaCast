import { audioEngine } from '$lib/audio/engine';

/**
 * The one set of numbers every visualiser on screen draws from.
 *
 * Deliberately *not* Svelte state. These arrays change on every display frame, and
 * routing them through reactivity meant a fresh 48-element array twice a frame plus
 * a reactive invalidation that re-rendered forty-eight DOM nodes, each with an
 * inline `style` string whose `height` forced layout. That is a layout pass per bar
 * per frame — the reason the web visualiser cost so much on a phone and had to be
 * capped at 30 Hz to stay usable at all.
 *
 * The renderer reads these directly inside its own `requestAnimationFrame` callback
 * and paints one canvas. Nothing here allocates after construction.
 */

/**
 * How many bars the spectrum styles draw. Enough to fill the width of a desktop
 * player bar at a readable bar width, few enough that a phone does not end up
 * drawing hairlines. The visualiser stretches this count across whatever width it
 * is given rather than clustering it in the middle.
 */
export const VISUALIZER_BANDS = 48;

/** Speech RMS rarely passes 0.2; without this the meter never leaves the floor. */
const LEVEL_GAIN = 5;

/**
 * Time constants, in seconds, rather than per-frame blend factors.
 *
 * A fixed per-frame blend only behaves the same way at every refresh rate if the
 * number of frames per second never changes, which is exactly what is not true
 * across a 60 Hz laptop, a 120 Hz phone and a tab that has just been backgrounded.
 * `1 - e^(-dt/tau)` covers the same fraction of the remaining distance per unit of
 * *time*, so the shape rises and falls at one speed everywhere.
 *
 * Identical to the Android client's `AmplitudeTap`, because the two displays are
 * the same product and drifting tunings are how one of them ends up looking broken
 * while the other does not.
 */
const BAND_ATTACK_TAU = 0.07;
const BAND_RELEASE_TAU = 0.39;
const LEVEL_ATTACK_TAU = 0.05;
const LEVEL_RELEASE_TAU = 0.25;
/**
 * Peak markers fall a little under half of full scale per second.
 *
 * On speech the loud moments are brief and far apart, so a slower fall leaves the
 * caps pinned near the top while the bars work away underneath and the row reads
 * as two unrelated things rather than as a meter with a peak hold.
 */
const PEAK_FALL_PER_SECOND = 0.45;

/**
 * The longest gap the filter will integrate over in one step.
 *
 * A backgrounded tab stops firing frame callbacks; without this the first frame
 * after it returns would carry a `dt` of many seconds and snap the display to its
 * target in one jump.
 */
const MAX_FRAME_SECONDS = 0.1;

/**
 * How many analyser readings are kept so the display can be delayed to match the
 * speaker. Half a second at 120 Hz, which covers any sane `outputLatency`.
 */
const HISTORY = 64;

class VisualizerFrame {
	/** Band heights, 0..1, low frequencies first. Rewritten in place every frame. */
	readonly bands = new Float32Array(VISUALIZER_BANDS);
	/** Slow-falling peak marker per band. */
	readonly peaks = new Float32Array(VISUALIZER_BANDS);
	/** Overall loudness, 0..1, for the styles that draw one number. */
	level = 0;
	/**
	 * Bumped whenever the arrays above move. Renderers compare it so a second
	 * visualiser mounted on the same page cannot double the sampling work.
	 */
	revision = 0;

	private readonly scratch = new Float32Array(VISUALIZER_BANDS);
	private lastSampleMs = 0;

	/**
	 * A short delay line of analyser readings, so what is drawn is what is leaving
	 * the speaker rather than what is passing the analyser.
	 *
	 * The same correction the Android client makes, for the same reason and with a
	 * far smaller number: there the tap sits ahead of a 400 ms AudioTrack buffer,
	 * here the analyser sits ahead of the output pipeline's few tens of
	 * milliseconds. Small enough to argue about, large enough that leaving it out
	 * would make the two clients disagree on purpose.
	 */
	private readonly historyBands: Float32Array[] = Array.from(
		{ length: HISTORY },
		() => new Float32Array(VISUALIZER_BANDS)
	);
	private readonly historyLevel = new Float64Array(HISTORY);
	private readonly historyTime = new Float64Array(HISTORY);
	private historyWrite = 0;
	private historyCount = 0;

	reset() {
		this.bands.fill(0);
		this.peaks.fill(0);
		this.scratch.fill(0);
		this.level = 0;
		this.lastSampleMs = 0;
		this.historyWrite = 0;
		this.historyCount = 0;
		this.revision++;
	}

	/**
	 * The reading from `latencySeconds` ago, or the oldest one held if the delay
	 * line has not filled yet. Returns the ring index to read the bands from.
	 *
	 * A linear walk over at most 64 entries, once per frame. A binary search would
	 * be faster in theory and slower in practice at this size.
	 */
	private delayedIndex(nowMs: number, latencySeconds: number): number {
		const wanted = nowMs - latencySeconds * 1000;
		let best = (this.historyWrite - 1 + HISTORY) % HISTORY;
		let bestGap = Infinity;
		for (let step = 0; step < this.historyCount; step++) {
			const index = (this.historyWrite - 1 - step + HISTORY * 2) % HISTORY;
			const gap = Math.abs(this.historyTime[index] - wanted);
			if (gap < bestGap) {
				bestGap = gap;
				best = index;
			} else {
				// Timestamps decrease monotonically as we walk back, so once the gap
				// starts growing the closest entry is behind us.
				break;
			}
		}
		return best;
	}

	/**
	 * Advances the display toward what the analyser is reporting, by the time
	 * elapsed since the previous call.
	 *
	 * Safe to call more than once in the same frame: the second call is a no-op, so
	 * the compact player and the expanded one can each drive it without the shape
	 * moving twice as fast when both are on screen.
	 */
	sample(timestampMs: number, wantsSpectrum: boolean, wantsLevel: boolean) {
		if (timestampMs === this.lastSampleMs) return;
		const dt =
			this.lastSampleMs === 0
				? 0
				: Math.min(MAX_FRAME_SECONDS, Math.max(0, (timestampMs - this.lastSampleMs) / 1000));
		this.lastSampleMs = timestampMs;

		// Record this reading, then draw the one that is now audible.
		const slot = this.historyWrite;
		this.historyTime[slot] = timestampMs;
		this.historyLevel[slot] = wantsLevel ? Math.min(1, (audioEngine.getLevel() ?? 0) * LEVEL_GAIN) : 0;
		if (wantsSpectrum && audioEngine.getSpectrum(this.scratch)) {
			this.historyBands[slot].set(this.scratch);
		} else {
			this.historyBands[slot].fill(0);
		}
		this.historyWrite = (slot + 1) % HISTORY;
		if (this.historyCount < HISTORY) this.historyCount++;

		const audible = this.delayedIndex(timestampMs, audioEngine.outputLatencySeconds);

		if (wantsLevel) {
			// Speech sits far below full scale; the lift is applied on the way into
			// the delay line so the stored figure is the one that gets drawn.
			const target = this.historyLevel[audible];
			const tau = target > this.level ? LEVEL_ATTACK_TAU : LEVEL_RELEASE_TAU;
			this.level += (target - this.level) * (1 - Math.exp(-dt / tau));
		}

		if (wantsSpectrum) {
			const source = this.historyBands[audible];
			const attack = 1 - Math.exp(-dt / BAND_ATTACK_TAU);
			const release = 1 - Math.exp(-dt / BAND_RELEASE_TAU);
			const peakDrop = PEAK_FALL_PER_SECOND * dt;
			for (let band = 0; band < VISUALIZER_BANDS; band++) {
				const target = source[band];
				const previous = this.bands[band];
				// Fast up, slow down — the standard bar-meter asymmetry. A symmetric
				// filter either misses transients or leaves the bars twitching.
				const value = previous + (target - previous) * (target > previous ? attack : release);
				this.bands[band] = value;
				// A separate, slower envelope: the peak-hold outline that makes a
				// spectrum readable rather than a blur of moving sticks.
				this.peaks[band] = Math.max(value, this.peaks[band] - peakDrop);
			}
		}

		this.revision++;
	}
}

export const visualizerFrame = new VisualizerFrame();
