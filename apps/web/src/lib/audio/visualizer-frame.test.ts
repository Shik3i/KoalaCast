import { beforeEach, describe, expect, it, vi } from 'vitest';

// The frame reads the singleton engine, so the analyser is stubbed rather than
// constructed: an AnalyserNode cannot be driven from a unit test, and every
// failure this file exists to prevent is a failure of the *timing*, not of the
// audio plumbing.
const engine = {
	level: 0,
	bands: 0,
	outputLatencySeconds: 0,
	getLevel(): number | null {
		return this.level;
	},
	getSpectrum(out: Float32Array): boolean {
		out.fill(this.bands);
		return true;
	}
};

vi.mock('$lib/audio/engine', () => ({ audioEngine: engine }));

const { visualizerFrame, VISUALIZER_BANDS } = await import('./visualizer-frame');

/** Plays `seconds` of a constant spectrum at `fps`, and returns where it got to. */
function play(seconds: number, fps: number): number {
	const step = 1000 / fps;
	let now = 1000;
	// The first sample only establishes the clock, so it carries no elapsed time.
	visualizerFrame.sample(now, true, true);
	for (let frame = 0; frame < Math.round(seconds * fps); frame++) {
		now += step;
		visualizerFrame.sample(now, true, true);
	}
	return visualizerFrame.bands[0];
}

describe('visualizerFrame', () => {
	beforeEach(() => {
		engine.level = 0;
		engine.bands = 0;
		engine.outputLatencySeconds = 0;
		visualizerFrame.reset();
	});

	it('reaches the same height after the same time whatever the refresh rate', () => {
		// The bug this guards: the envelope used to blend by a fixed fraction *per
		// frame*, so the same audio rose more than twice as fast on a 120 Hz phone
		// as on a 60 Hz laptop, and slower still whenever a frame was dropped.
		engine.bands = 1;
		const atSixty = play(0.2, 60);
		visualizerFrame.reset();
		const atOneTwenty = play(0.2, 120);
		visualizerFrame.reset();
		const atThirty = play(0.2, 30);

		expect(atOneTwenty).toBeCloseTo(atSixty, 2);
		expect(atThirty).toBeCloseTo(atSixty, 2);
	});

	it('rises fast and falls slowly', () => {
		engine.bands = 1;
		const risen = play(0.25, 60);
		expect(risen).toBeGreaterThan(0.95);

		engine.bands = 0;
		const afterSameTime = play(0.25, 60);
		// Still well up: the release is more than five times the attack, which is
		// what keeps the shape reading as one thing moving rather than as a flicker.
		expect(afterSameTime).toBeGreaterThan(0.35);
		expect(afterSameTime).toBeLessThan(risen);
	});

	it('holds the peak marker above the bar it was set by', () => {
		engine.bands = 1;
		play(0.25, 60);
		engine.bands = 0;
		play(0.15, 60);

		expect(visualizerFrame.peaks[0]).toBeGreaterThan(visualizerFrame.bands[0]);
	});

	it('ignores a second call in the same frame', () => {
		// Two visualisers can be mounted at once — the player bar behind the
		// expanded player. Without this the shape moves at twice the speed whenever
		// both happen to be on screen.
		engine.bands = 1;
		visualizerFrame.sample(1000, true, true);
		visualizerFrame.sample(1016, true, true);
		const once = visualizerFrame.bands[0];

		visualizerFrame.reset();
		visualizerFrame.sample(1000, true, true);
		visualizerFrame.sample(1016, true, true);
		visualizerFrame.sample(1016, true, true);
		visualizerFrame.sample(1016, true, true);

		expect(visualizerFrame.bands[0]).toBeCloseTo(once, 6);
	});

	it('does not snap to the target after a backgrounded tab', () => {
		// A hidden tab fires no frame callbacks. Without a ceiling on the step, the
		// first frame back would carry a dt of many seconds and jump the display
		// straight to full height.
		engine.bands = 1;
		visualizerFrame.sample(1000, true, true);
		visualizerFrame.sample(31_000, true, true);

		expect(visualizerFrame.bands[0]).toBeLessThan(0.9);
	});

	it('draws what is leaving the speaker, not what is passing the analyser', () => {
		// The analyser sits ahead of the output pipeline, so a loud passage it
		// reports has not been heard yet. Without the delay line the picture runs
		// ahead of the sound by the output latency.
		engine.outputLatencySeconds = 0.15;
		engine.bands = 0;
		let now = 1000;
		visualizerFrame.sample(now, true, true);
		for (let frame = 0; frame < 12; frame++) {
			now += 1000 / 60;
			visualizerFrame.sample(now, true, true);
		}

		// The analyser goes loud. For the next 150 ms that is still in the pipeline.
		engine.bands = 1;
		for (let frame = 0; frame < 4; frame++) {
			now += 1000 / 60;
			visualizerFrame.sample(now, true, true);
		}
		const whileStillInFlight = visualizerFrame.bands[0];

		// Once the latency has elapsed it becomes audible, and visible.
		for (let frame = 0; frame < 25; frame++) {
			now += 1000 / 60;
			visualizerFrame.sample(now, true, true);
		}

		expect(whileStillInFlight).toBeLessThan(0.2);
		expect(visualizerFrame.bands[0]).toBeGreaterThan(0.6);
	});

	it('falls back to the newest reading before the delay line has filled', () => {
		// A latency longer than the history held must not leave the display blank.
		engine.outputLatencySeconds = 0.4;
		engine.bands = 1;
		let now = 1000;
		visualizerFrame.sample(now, true, true);
		for (let frame = 0; frame < 40; frame++) {
			now += 1000 / 60;
			visualizerFrame.sample(now, true, true);
		}

		expect(visualizerFrame.bands[0]).toBeGreaterThan(0.1);
	});

	it('empties every band on reset', () => {
		engine.bands = 1;
		play(0.25, 60);
		visualizerFrame.reset();

		expect(visualizerFrame.level).toBe(0);
		for (let band = 0; band < VISUALIZER_BANDS; band++) {
			expect(visualizerFrame.bands[band]).toBe(0);
			expect(visualizerFrame.peaks[band]).toBe(0);
		}
	});
});
