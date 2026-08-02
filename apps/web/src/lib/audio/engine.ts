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
			// 1024 gives ~43 Hz bins at 44.1 kHz, which is the coarsest resolution
			// that still separates a voice's fundamental from the band below it.
			// The analyser's own smoothing is lowered from the 0.8 default because
			// the visualiser is redrawn every frame and 0.8 visibly lags the audio.
			this.analyserNode.fftSize = 1024;
			this.analyserNode.smoothingTimeConstant = 0.6;
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
	 * Bands are log-spaced, because linear bins put nine tenths of a spectrum
	 * display above 4 kHz where speech has almost nothing, and the result is a
	 * row of bars in which only the leftmost two ever move. They are also tilted
	 * upwards with frequency to offset the natural rolloff of recorded speech,
	 * so the right-hand bars are visible rather than technically-correct stubs.
	 */
	public getSpectrum(out: Float32Array): boolean {
		const analyser = this.analyserNode;
		const data = this.freqData;
		if (!analyser || !data || !this.audioCtx) return false;
		analyser.getByteFrequencyData(data);

		const nyquist = this.audioCtx.sampleRate / 2;
		const bins = data.length;
		const bands = out.length;
		const logMin = Math.log(SPECTRUM_MIN_HZ);
		const logMax = Math.log(SPECTRUM_MAX_HZ);

		for (let band = 0; band < bands; band++) {
			const lowHz = Math.exp(logMin + ((logMax - logMin) * band) / bands);
			const highHz = Math.exp(logMin + ((logMax - logMin) * (band + 1)) / bands);
			let lowBin = Math.floor((lowHz / nyquist) * bins);
			let highBin = Math.ceil((highHz / nyquist) * bins);
			lowBin = Math.max(0, Math.min(bins - 1, lowBin));
			// Narrow bands at the bottom can collapse onto a single bin; never let
			// a band read zero bins and render as a permanent gap.
			highBin = Math.max(lowBin + 1, Math.min(bins, highBin));

			// Peak, not mean: averaging across a band that spans several kHz buries
			// every transient, and transients are the part a listener recognises.
			let peak = 0;
			for (let bin = lowBin; bin < highBin; bin++) {
				if (data[bin] > peak) peak = data[bin];
			}
			const tilt = 1 + (SPECTRUM_TILT * band) / Math.max(1, bands - 1);
			out[band] = Math.max(0, Math.min(1, (peak / 255) * tilt));
		}
		return true;
	}
}

/** Below this is rumble, above it is hiss; neither says anything about speech. */
const SPECTRUM_MIN_HZ = 55;
const SPECTRUM_MAX_HZ = 12_000;
/** The top band ends up with this much extra gain over the bottom one. */
const SPECTRUM_TILT = 1.6;

export const audioEngine = new AudioEngine();
