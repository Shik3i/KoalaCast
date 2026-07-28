export interface SilenceGateOptions {
	enterThreshold?: number;
	exitThreshold?: number;
	enterDelayMs?: number;
	exitDelayMs?: number;
}

export class SilenceGate {
	private readonly enterThreshold: number;
	private readonly exitThreshold: number;
	private readonly enterDelayMs: number;
	private readonly exitDelayMs: number;
	private quietSince: number | null = null;
	private soundSince: number | null = null;
	private active = false;

	constructor(options: SilenceGateOptions = {}) {
		this.enterThreshold = options.enterThreshold ?? 0.012;
		this.exitThreshold = options.exitThreshold ?? 0.02;
		this.enterDelayMs = options.enterDelayMs ?? 250;
		this.exitDelayMs = options.exitDelayMs ?? 80;
	}

	update(level: number, nowMs: number): boolean {
		if (!Number.isFinite(level) || !Number.isFinite(nowMs)) {
			return this.reset();
		}

		if (this.active) {
			if (level >= this.exitThreshold) {
				this.soundSince ??= nowMs;
				if (nowMs - this.soundSince >= this.exitDelayMs) {
					this.active = false;
					this.quietSince = null;
				}
			} else {
				this.soundSince = null;
			}
			return this.active;
		}

		if (level <= this.enterThreshold) {
			this.quietSince ??= nowMs;
			if (nowMs - this.quietSince >= this.enterDelayMs) {
				this.active = true;
				this.soundSince = null;
			}
		} else {
			this.quietSince = null;
		}
		return this.active;
	}

	reset(): false {
		this.active = false;
		this.quietSince = null;
		this.soundSince = null;
		return false;
	}
}
