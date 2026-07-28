import { describe, expect, it } from 'vitest';
import { calculateQueueTiming } from './queue-timing';

describe('calculateQueueTiming', () => {
	it('uses playback speed for the current episode and every queued episode', () => {
		const timing = calculateQueueTiming({
			now: 1_000,
			positionMs: 10 * 60_000,
			positionUpdatedAt: 1_000,
			currentDurationMs: 40 * 60_000,
			queueDurationsMs: [30 * 60_000, 15 * 60_000],
			playbackSpeed: 1.5,
			isPlaying: false
		});

		expect(timing.currentRemainingWallMs).toBe(20 * 60_000);
		expect(timing.queueWallMs).toBe(30 * 60_000);
		expect(timing.totalRemainingWallMs).toBe(50 * 60_000);
		expect(timing.finishOffsetsMs).toEqual([40 * 60_000, 50 * 60_000]);
	});

	it('keeps the finish clock stable between media timeupdate events', () => {
		const base = {
			positionMs: 5 * 60_000,
			positionUpdatedAt: 10_000,
			currentDurationMs: 35 * 60_000,
			queueDurationsMs: [20 * 60_000],
			playbackSpeed: 1.25,
			isPlaying: true
		};
		const first = calculateQueueTiming({ ...base, now: 10_000 });
		const tenSecondsLater = calculateQueueTiming({ ...base, now: 20_000 });

		expect(tenSecondsLater.naturalEndsAt).toBe(first.naturalEndsAt);
	});

	it('moves the finish clock forward while playback is paused', () => {
		const base = {
			positionMs: 5 * 60_000,
			positionUpdatedAt: 10_000,
			currentDurationMs: 35 * 60_000,
			queueDurationsMs: [],
			playbackSpeed: 1.15,
			isPlaying: false
		};
		const first = calculateQueueTiming({ ...base, now: 10_000 });
		const later = calculateQueueTiming({ ...base, now: 20_000 });

		expect(later.naturalEndsAt - first.naturalEndsAt).toBe(10_000);
	});
});
