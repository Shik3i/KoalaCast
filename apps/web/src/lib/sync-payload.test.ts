import { describe, expect, it } from 'vitest';
import { normalizeListeningSessionForSync } from './sync-payload';

describe('normalizeListeningSessionForSync', () => {
	it('rounds fractional millisecond metrics to the integer sync contract', () => {
		const normalized = normalizeListeningSessionForSync({
			id: 'session-1',
			episode_id: 'episode-1',
			podcast_id: 'podcast-1',
			title: 'Episode',
			podcast_title: 'Podcast',
			started_at: 1000.4,
			ended_at: 2000.4,
			wall_clock_ms: 1000.5,
			audio_listened_ms: 900.5,
			speed_saved_ms: 35510.5,
			silence_saved_ms: -1,
			manual_skipped_ms: Number.NaN,
			intro_outro_skipped_ms: 0,
			speed_weighted_ms: 800.5
		});

		expect(normalized).toMatchObject({
			started_at: 1001,
			ended_at: 2001,
			wall_clock_ms: 1001,
			audio_listened_ms: 901,
			speed_saved_ms: 35511,
			silence_saved_ms: 0,
			manual_skipped_ms: 0,
			intro_outro_skipped_ms: 0,
			speed_weighted_ms: 801
		});
	});
});
