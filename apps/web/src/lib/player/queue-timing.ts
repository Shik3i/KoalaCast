export interface QueueTiming {
	currentRemainingMediaMs: number;
	currentRemainingWallMs: number;
	queueWallMs: number;
	totalRemainingWallMs: number;
	naturalEndsAt: number;
	finishOffsetsMs: number[];
}

function safeSpeed(speed: number): number {
	return Number.isFinite(speed) && speed > 0 ? speed : 1;
}

export function calculateQueueTiming(input: {
	now: number;
	positionMs: number;
	positionUpdatedAt: number;
	currentDurationMs: number;
	queueDurationsMs: number[];
	playbackSpeed: number;
	isPlaying: boolean;
}): QueueTiming {
	const speed = safeSpeed(input.playbackSpeed);
	const duration = Math.max(0, input.currentDurationMs);
	const elapsedSinceSample = input.isPlaying
		? Math.max(0, input.now - input.positionUpdatedAt) * speed
		: 0;
	const projectedPosition = Math.min(duration, Math.max(0, input.positionMs) + elapsedSinceSample);
	const currentRemainingMediaMs = Math.max(0, duration - projectedPosition);
	const queueDurations = input.queueDurationsMs.map((value) => Math.max(0, value || 0));
	const queueMediaMs = queueDurations.reduce((sum, value) => sum + value, 0);
	const currentRemainingWallMs = currentRemainingMediaMs / speed;
	const queueWallMs = queueMediaMs / speed;
	let cumulativeMediaMs = currentRemainingMediaMs;
	const finishOffsetsMs = queueDurations.map((durationMs) => {
		cumulativeMediaMs += durationMs;
		return cumulativeMediaMs / speed;
	});
	const totalRemainingWallMs = currentRemainingWallMs + queueWallMs;

	return {
		currentRemainingMediaMs,
		currentRemainingWallMs,
		queueWallMs,
		totalRemainingWallMs,
		naturalEndsAt: input.now + totalRemainingWallMs,
		finishOffsetsMs
	};
}
