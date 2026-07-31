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
			this.analyserNode.fftSize = 512;
			this.levelData = new Uint8Array(this.analyserNode.fftSize);

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
		if (!this.skipSilence || !this.analyserNode || !this.levelData) return null;
		this.analyserNode.getByteTimeDomainData(this.levelData);
		let sumSquares = 0;
		for (let i = 0; i < this.levelData.length; i++) {
			const centered = (this.levelData[i] - 128) / 128;
			sumSquares += centered * centered;
		}
		return Math.sqrt(sumSquares / this.levelData.length);
	}
}

export const audioEngine = new AudioEngine();
