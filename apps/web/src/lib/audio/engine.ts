import {
	applyAutoGain,
	createAutoGainState,
	reduceToBands,
	spectrumBandEdges,
	SPECTRUM_CEILING_DB,
	SPECTRUM_FLOOR_DB,
	type AutoGainState
} from '$lib/audio/spectrum';

// Web Audio API Engine for KoalaCast
// Handles Volume Boost (Gain + Compressor) and Real-time Silence Detection (Analyser)

export class AudioEngine {
	private audioCtx: AudioContext | null = null;
	private sourceNode: MediaElementAudioSourceNode | null = null;
	private gainNode: GainNode | null = null;
	private compressorNode: DynamicsCompressorNode | null = null;
	private analyserNode: AnalyserNode | null = null;
	private outputGainNode: GainNode | null = null;
	private levelData: Uint8Array<ArrayBuffer> | null = null;
	private freqData: Uint8Array<ArrayBuffer> | null = null;
	/** Cached per-band bin ranges; the sample rate cannot change under a graph. */
	private bandEdges: Int32Array | null = null;
	private autoGain: AutoGainState = createAutoGainState();

	public volumeBoost = false;
	public skipSilence = false;

	// The listener's own volume, applied at the very end of the chain.
	//
	// It cannot ride on HTMLAudioElement.volume while the graph is active: that
	// lands *before* the compressor, which then flattens most of it away. Measured
	// on a -6 dBFS tone, dropping the element from 1.0 to 0.3 moved the output by
	// 10.5 dB with the graph off and only 5.4 dB with boost on — the slider losing
	// half its travel, which reads as "the volume control does nothing".
	private volume = 1;

	public get initialized(): boolean {
		return this.audioCtx !== null;
	}

	public init(audioEl: HTMLAudioElement): boolean {
		if (this.audioCtx) return true;
		try {
			const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
			if (!AudioContextClass) return false;

			this.audioCtx = new AudioContextClass();
			this.sourceNode = this.audioCtx.createMediaElementSource(audioEl);
			this.gainNode = this.audioCtx.createGain();
			this.compressorNode = this.audioCtx.createDynamicsCompressor();
			this.analyserNode = this.audioCtx.createAnalyser();
			// 2048 gives ~21 Hz bins at 44.1 kHz. 1024 was too coarse for the bottom
			// of a log-spaced display: at 43 Hz per bin the lowest dozen bands all
			// landed on the same one or two bins and drew the same number, which is
			// most of what "only the left edge moves" was.
			this.analyserNode.fftSize = 2048;
			// The analyser's own smoothing is lowered from the 0.8 default because
			// the visualiser is redrawn every frame and 0.8 visibly lags the audio.
			this.analyserNode.smoothingTimeConstant = 0.6;
			// Without this the defaults apply: -100 to -30. Ordinary mastered speech
			// spends most of a sentence above a -30 ceiling, so band after band sat
			// pinned at 255 — and a clipped bar cannot move. The window matches the
			// Android client's exactly, so both displays answer alike.
			this.analyserNode.minDecibels = SPECTRUM_FLOOR_DB;
			this.analyserNode.maxDecibels = SPECTRUM_CEILING_DB;
			this.levelData = new Uint8Array(this.analyserNode.fftSize);
			this.freqData = new Uint8Array(this.analyserNode.frequencyBinCount);

			this.outputGainNode = this.audioCtx.createGain();

			this.sourceNode.connect(this.gainNode);
			this.gainNode.connect(this.compressorNode);
			this.compressorNode.connect(this.analyserNode);
			this.analyserNode.connect(this.outputGainNode);
			this.outputGainNode.connect(this.audioCtx.destination);
			this.gainNode.gain.setValueAtTime(this.volumeBoost ? 2.2 : 1.0, this.audioCtx.currentTime);
			this.outputGainNode.gain.setValueAtTime(this.volume, this.audioCtx.currentTime);
			return true;
		} catch (err) {
			console.warn('Web Audio API initialized with warning:', err);
			return false;
		}
	}

	public async resume(): Promise<boolean> {
		if (!this.audioCtx) return false;
		if (this.audioCtx.state === 'suspended') {
			try {
				await this.audioCtx.resume();
			} catch {
				return false;
			}
		}
		return this.audioCtx.state === 'running';
	}

	public destroy() {
		this.sourceNode?.disconnect();
		this.gainNode?.disconnect();
		this.compressorNode?.disconnect();
		this.analyserNode?.disconnect();
		this.outputGainNode?.disconnect();
		void this.audioCtx?.close();
		this.audioCtx = null;
		this.sourceNode = null;
		this.gainNode = null;
		this.compressorNode = null;
		this.analyserNode = null;
		this.outputGainNode = null;
		this.levelData = null;
		this.freqData = null;
		this.bandEdges = null;
		this.autoGain = createAutoGainState();
		this.volumeBoost = false;
		this.skipSilence = false;
	}

	/**
	 * The listener's volume, 0..1. Remembered even when no graph exists so that a
	 * graph created later starts at the right level instead of jumping to full.
	 */
	public setVolume(volume: number) {
		this.volume = Math.max(0, Math.min(1, volume));
		if (this.outputGainNode && this.audioCtx) {
			this.outputGainNode.gain.setValueAtTime(this.volume, this.audioCtx.currentTime);
		}
	}

	public setVolumeBoost(enabled: boolean) {
		this.volumeBoost = enabled;
		if (this.gainNode && this.audioCtx) {
			this.gainNode.gain.setValueAtTime(enabled ? 2.2 : 1.0, this.audioCtx.currentTime);
		}
	}

	public getLevel(): number | null {
		if (!this.analyserNode || !this.levelData) return null;
		this.analyserNode.getByteTimeDomainData(this.levelData);
		let sumSquares = 0;
		for (let i = 0; i < this.levelData.length; i++) {
			const centered = (this.levelData[i] - 128) / 128;
			sumSquares += centered * centered;
		}
		return Math.sqrt(sumSquares / this.levelData.length);
	}

	/**
	 * Fills [out] with one 0..1 energy per band, low frequencies first, for a
	 * spectrum display. Returns false when there is no graph to read.
	 *
	 * The mapping itself lives in `spectrum.ts` so it can be tested without a
	 * browser; this method is only the part that needs a live AnalyserNode.
	 */
	public getSpectrum(out: Float32Array): boolean {
		const analyser = this.analyserNode;
		const data = this.freqData;
		if (!analyser || !data || !this.audioCtx) return false;
		analyser.getByteFrequencyData(data);

		if (!this.bandEdges || this.bandEdges.length !== out.length + 1) {
			this.bandEdges = spectrumBandEdges(this.audioCtx.sampleRate, data.length, out.length);
		}
		reduceToBands(data, this.bandEdges, out);
		// Normalise against what this display has been hearing rather than against
		// full scale, so a quietly mastered episode fills the same height as a loud
		// one. Silence is left alone.
		applyAutoGain(out, this.autoGain);
		return true;
	}
}

export const audioEngine = new AudioEngine();
