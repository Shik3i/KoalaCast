// The frequency half of the visualiser signal.
//
// Kept apart from the audio graph so the arithmetic can be tested without a
// browser: an AnalyserNode cannot be driven from a unit test, and every failure
// this file exists to prevent is a failure of the mapping rather than of the
// audio plumbing.
//
// The Android client reaches the same numbers through its own FFT (see
// core/player/Spectrum.kt). The constants below are deliberately identical to
// its, because the two displays are the same product and drifting tunings are
// how one of them ends up looking broken while the other does not — which is
// exactly what happened here.

/** Below this is rumble, above it is hiss; neither says anything about speech. */
export const SPECTRUM_MIN_HZ = 60;
export const SPECTRUM_MAX_HZ = 12_000;

/**
 * The visible window, in dBFS.
 *
 * The AnalyserNode's defaults are -100 and -30, and this file used to leave
 * them alone. A -30 ceiling is below the level ordinary mastered speech spends
 * most of a sentence at, so band after band sat pinned at 255 — and a bar that
 * is clipped does not move. Half the display looking frozen was that.
 */
export const SPECTRUM_FLOOR_DB = -78;
export const SPECTRUM_CEILING_DB = -4;

/**
 * Recorded speech rolls off with frequency; without this the right half is dead.
 * Modest on purpose: at 1.6 the top bands are lifted by 2.6x and clip on their
 * own, which is the same frozen display arriving from the other end.
 */
export const SPECTRUM_TILT = 0.8;

/**
 * The FFT bin index each band starts at, plus a final entry for the end of the
 * last band, so a band's bins are `edges[i]` until `edges[i + 1]`.
 *
 * Log-spaced: linear spacing puts nine tenths of the display above 4 kHz where
 * speech has almost nothing, leaving a row of bars in which only the leftmost
 * two ever move. Every band is guaranteed at least one bin, so no bar renders
 * as a permanent gap.
 */
export function spectrumBandEdges(
	sampleRateHz: number,
	binCount: number,
	bands: number
): Int32Array {
	if (bands >= binCount) throw new Error('spectrum needs more FFT bins than bands');
	const nyquist = Math.max(1, sampleRateHz) / 2;
	const logMin = Math.log(SPECTRUM_MIN_HZ);
	const logMax = Math.log(
		Math.max(SPECTRUM_MIN_HZ * 2, Math.min(SPECTRUM_MAX_HZ, nyquist))
	);
	const edges = new Int32Array(bands + 1);
	for (let band = 0; band <= bands; band++) {
		const hz = Math.exp(logMin + ((logMax - logMin) * band) / bands);
		edges[band] = Math.min(binCount, Math.max(0, Math.floor((hz / nyquist) * binCount)));
	}
	// The bottom bands are narrower than one bin at any practical FFT size, so
	// without this they collapse onto each other and a stretch of bars all draw
	// the same number. Safe without an upper clamp only because there are far
	// more bins than bands, which the guard above enforces.
	for (let band = 1; band <= bands; band++) {
		if (edges[band] <= edges[band - 1]) edges[band] = edges[band - 1] + 1;
	}
	return edges;
}

/**
 * Reduces one byte-magnitude spectrum to per-band energies, 0..1.
 *
 * `data` is what `AnalyserNode.getByteFrequencyData` produces, which is already
 * the dB window mapped onto 0..255 — provided the window was configured; see
 * SPECTRUM_FLOOR_DB. Peak within a band rather than mean, because averaging
 * across a band spanning several kHz buries every transient, and transients are
 * the part a listener recognises.
 */
export function reduceToBands(data: Uint8Array, edges: Int32Array, out: Float32Array): void {
	for (let band = 0; band < out.length; band++) {
		let peak = 0;
		const end = Math.min(data.length, edges[band + 1]);
		for (let bin = edges[band]; bin < end; bin++) {
			if (data[bin] > peak) peak = data[bin];
		}
		const tilt = 1 + (SPECTRUM_TILT * band) / Math.max(1, out.length - 1);
		out[band] = Math.min(1, (peak / 255) * tilt);
	}
}

/**
 * Where a loud passage should land, and how far the gain may reach for it.
 *
 * This is the part neither client had, and the reason a quiet episode drew a
 * flat display while a loud one drew a lively one. A music player's spectrum
 * looks alive at every volume because it is normalised against what it has been
 * hearing, not against full scale. Podcast audio makes it more pronounced still:
 * levelling between shows is far less consistent than in mastered music.
 */
export const AGC_TARGET = 0.82;
export const AGC_MAX_GAIN = 3;
/** Below this the frame is silence or room tone, and lifting it only draws noise. */
export const AGC_SILENCE = 0.06;
/** Rises quickly so a transient pulls the gain down at once; falls slowly. */
export const AGC_ATTACK = 0.35;
export const AGC_RELEASE = 0.015;

export interface AutoGainState {
	/** The loudest band this display has been seeing, smoothed. */
	reference: number;
}

export function createAutoGainState(): AutoGainState {
	return { reference: 0 };
}

/**
 * Scales `bands` in place so the display uses its height at any input level.
 *
 * Returns the gain applied, for tests and for callers that want to reason about
 * it. Silence is deliberately left alone: an empty display during a pause is
 * correct, and amplifying room tone into a full-height wall is not.
 */
export function applyAutoGain(bands: Float32Array, state: AutoGainState): number {
	let frontRunner = 0;
	for (let band = 0; band < bands.length; band++) {
		if (bands[band] > frontRunner) frontRunner = bands[band];
	}
	const coefficient = frontRunner > state.reference ? AGC_ATTACK : AGC_RELEASE;
	state.reference += (frontRunner - state.reference) * coefficient;

	if (state.reference < AGC_SILENCE) return 1;
	const gain = Math.min(AGC_MAX_GAIN, Math.max(1, AGC_TARGET / state.reference));
	if (gain === 1) return 1;
	for (let band = 0; band < bands.length; band++) {
		bands[band] = Math.min(1, bands[band] * gain);
	}
	return gain;
}
