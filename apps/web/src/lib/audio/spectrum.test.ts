import { describe, expect, it } from 'vitest';
import {
	AGC_MAX_GAIN,
	AGC_SILENCE,
	applyAutoGain,
	createAutoGainState,
	reduceToBands,
	spectrumBandEdges,
	SPECTRUM_MAX_HZ,
	SPECTRUM_MIN_HZ
} from './spectrum';

const SAMPLE_RATE = 44_100;
const BINS = 1024; // fftSize 2048
const BANDS = 48;

function edges() {
	return spectrumBandEdges(SAMPLE_RATE, BINS, BANDS);
}

/**
 * A byte spectrum shaped like speech: strong around the fundamental, rolling
 * off with frequency, and essentially nothing above 10 kHz. This is the input
 * that used to leave most of the display motionless.
 */
function speechLikeSpectrum(scale = 1): Uint8Array {
	const data = new Uint8Array(BINS);
	const nyquist = SAMPLE_RATE / 2;
	for (let bin = 0; bin < BINS; bin++) {
		const hz = (bin / BINS) * nyquist;
		// Real recordings carry room tone below the fundamental. Starting the
		// fixture above the display's lowest band would test a silence the input
		// created, not one the mapping did.
		if (hz < 40) continue;
		// -9 dB per octave above 200 Hz, which is roughly what recorded speech does.
		const octaves = Math.max(0, Math.log2(Math.max(hz, 200) / 200));
		const magnitude = Math.max(0, 210 - octaves * 32);
		data[bin] = Math.round(Math.min(255, magnitude * scale));
	}
	return data;
}

describe('spectrumBandEdges', () => {
	it('gives every band at least one bin, so no bar is a permanent gap', () => {
		const bandEdges = edges();
		expect(bandEdges).toHaveLength(BANDS + 1);
		for (let band = 0; band < BANDS; band++) {
			expect(bandEdges[band + 1]).toBeGreaterThan(bandEdges[band]);
		}
	});

	it('spans the intended range and stays inside the bin count', () => {
		const bandEdges = edges();
		const nyquist = SAMPLE_RATE / 2;
		const firstHz = (bandEdges[0] / BINS) * nyquist;
		const lastHz = (bandEdges[BANDS] / BINS) * nyquist;
		expect(firstHz).toBeLessThanOrEqual(SPECTRUM_MIN_HZ * 1.5);
		expect(lastHz).toBeLessThanOrEqual(SPECTRUM_MAX_HZ * 1.05);
		expect(bandEdges[BANDS]).toBeLessThanOrEqual(BINS);
	});

	it('refuses a configuration with fewer bins than bands', () => {
		expect(() => spectrumBandEdges(SAMPLE_RATE, 32, 48)).toThrow();
	});

	// The bug this whole file exists for: at fftSize 1024 the lowest bands landed
	// on the same one or two bins and drew the same number, so the left edge of
	// the display moved as one block.
	it('resolves the low bands onto distinct bins at the shipped FFT size', () => {
		const bandEdges = edges();
		const lowest = new Set<number>();
		for (let band = 0; band < 12; band++) lowest.add(bandEdges[band]);
		expect(lowest.size).toBe(12);
	});
});

describe('reduceToBands', () => {
	it('lights up the whole display for speech, not just the left edge', () => {
		const out = new Float32Array(BANDS);
		reduceToBands(speechLikeSpectrum(), edges(), out);

		const silent = [...out].filter((value) => value <= 0.001).length;
		expect(silent).toBe(0);
		// The top third has to carry visible energy, which is what the tilt is for.
		const topThird = [...out].slice(Math.floor((BANDS * 2) / 3));
		expect(Math.max(...topThird)).toBeGreaterThan(0.15);
	});

	it('does not pin the display at full height', () => {
		const out = new Float32Array(BANDS);
		reduceToBands(speechLikeSpectrum(), edges(), out);
		// A clipped bar cannot move, and a display of clipped bars is exactly the
		// "everything is static" report. The tilt was 1.6 here, which lifted the
		// top bands by 2.6x and clipped them on their own.
		const clipped = [...out].filter((value) => value >= 0.999).length;
		expect(clipped).toBeLessThan(BANDS / 3);
	});

	it('stays within range and rises with input level', () => {
		const quiet = new Float32Array(BANDS);
		const loud = new Float32Array(BANDS);
		reduceToBands(speechLikeSpectrum(0.4), edges(), quiet);
		reduceToBands(speechLikeSpectrum(1), edges(), loud);
		for (let band = 0; band < BANDS; band++) {
			expect(loud[band]).toBeGreaterThanOrEqual(quiet[band] - 1e-6);
			expect(loud[band]).toBeLessThanOrEqual(1);
			expect(quiet[band]).toBeGreaterThanOrEqual(0);
		}
	});
});

describe('applyAutoGain', () => {
	function settle(bands: () => Float32Array<ArrayBuffer>, frames: number) {
		const state = createAutoGainState();
		let last = new Float32Array(BANDS);
		let gain = 1;
		for (let frame = 0; frame < frames; frame++) {
			last = bands();
			gain = applyAutoGain(last, state);
		}
		return { bands: last, gain, state };
	}

	it('lifts a quietly mastered episode towards the target', () => {
		const quiet = () => {
			const out = new Float32Array(BANDS);
			reduceToBands(speechLikeSpectrum(0.35), edges(), out);
			return out;
		};
		const before = quiet();
		const { bands: after, gain } = settle(quiet, 400);
		expect(gain).toBeGreaterThan(1);
		expect(Math.max(...after)).toBeGreaterThan(Math.max(...before));
	});

	it('leaves silence alone rather than amplifying room tone', () => {
		const { gain, bands } = settle(() => {
			const out = new Float32Array(BANDS);
			out.fill(AGC_SILENCE / 3);
			return out;
		}, 400);
		expect(gain).toBe(1);
		expect(Math.max(...bands)).toBeLessThan(AGC_SILENCE);
	});

	it('never exceeds its gain ceiling', () => {
		const { gain } = settle(() => {
			const out = new Float32Array(BANDS);
			out.fill(AGC_SILENCE * 1.2);
			return out;
		}, 2000);
		expect(gain).toBeLessThanOrEqual(AGC_MAX_GAIN);
	});

	it('backs off quickly when a loud passage arrives', () => {
		const state = createAutoGainState();
		const quiet = new Float32Array(BANDS);
		quiet.fill(0.2);
		for (let frame = 0; frame < 400; frame++) applyAutoGain(Float32Array.from(quiet), state);
		const liftedGain = Math.min(AGC_MAX_GAIN, 0.82 / state.reference);

		const loud = new Float32Array(BANDS);
		loud.fill(0.95);
		let gain = 1;
		for (let frame = 0; frame < 20; frame++) gain = applyAutoGain(Float32Array.from(loud), state);
		expect(gain).toBeLessThan(liftedGain);
		expect(gain).toBeCloseTo(1, 1);
	});

	it('never pushes a band past full height', () => {
		const state = createAutoGainState();
		for (let frame = 0; frame < 500; frame++) {
			const out = new Float32Array(BANDS);
			out.fill(0.3);
			applyAutoGain(out, state);
			for (const value of out) expect(value).toBeLessThanOrEqual(1);
		}
	});
});
