// Web Audio API Engine for KoalaCast
// Handles Volume Boost (Gain + Compressor) and Real-time Silence Detection (Analyser)

export class AudioEngine {
	private audioCtx: AudioContext | null = null;
	private sourceNode: MediaElementAudioSourceNode | null = null;
	private gainNode: GainNode | null = null;
	private compressorNode: DynamicsCompressorNode | null = null;
	private analyserNode: AnalyserNode | null = null;

	public volumeBoost = false;
	public skipSilence = false;

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
			this.analyserNode.fftSize = 256;

			this.sourceNode.connect(this.gainNode);
			this.gainNode.connect(this.compressorNode);
			this.compressorNode.connect(this.analyserNode);
			this.analyserNode.connect(this.audioCtx.destination);
			return true;
		} catch (err) {
			console.warn('Web Audio API initialized with warning:', err);
			return false;
		}
	}

	public resume() {
		if (this.audioCtx && this.audioCtx.state === 'suspended') {
			this.audioCtx.resume().catch(() => {});
		}
	}

	public setVolumeBoost(enabled: boolean) {
		this.volumeBoost = enabled;
		this.resume();
		if (this.gainNode && this.audioCtx) {
			this.gainNode.gain.setValueAtTime(enabled ? 2.2 : 1.0, this.audioCtx.currentTime);
		}
	}

	public isSilent(): boolean {
		if (!this.skipSilence || !this.analyserNode) return false;
		const data = new Uint8Array(this.analyserNode.frequencyBinCount);
		this.analyserNode.getByteFrequencyData(data);
		let sum = 0;
		for (let i = 0; i < data.length; i++) sum += data[i];
		const avg = sum / data.length;
		return avg < 4;
	}
}

export const audioEngine = new AudioEngine();
