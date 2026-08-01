import { describe, expect, it } from 'vitest';
import { summarizeListening, type ListeningAnalytics } from './analytics';
import type { LocalListeningSession, LocalPlaybackState } from '$lib/idb/db';

function session(overrides: Partial<LocalListeningSession> = {}): LocalListeningSession {
	return {
		id: 's1',
		episode_id: 'e1',
		podcast_id: 'p1',
		title: 'Episode',
		podcast_title: 'Show',
		categories: ['Technology'],
		started_at: new Date('2026-07-24T08:00:00').getTime(),
		ended_at: new Date('2026-07-24T08:10:00').getTime(),
		wall_clock_ms: 600_000,
		audio_listened_ms: 750_000,
		speed_saved_ms: 150_000,
		silence_saved_ms: 30_000,
		manual_skipped_ms: 60_000,
		intro_outro_skipped_ms: 0,
		speed_weighted_ms: 750_000,
		...overrides
	};
}

describe('summarizeListening', () => {
	it('aggregates real time, savings, speed, podcast, day, hour and category', () => {
		const states = [{ episode_id: 'e1', podcast_id: 'p1', completed: true }] as LocalPlaybackState[];
		const result: ListeningAnalytics = summarizeListening([session()], states);
		expect(result.totalWallMs).toBe(600_000);
		expect(result.totalSavedMs).toBe(240_000);
		expect(result.baselineAudioMs).toBe(840_000);
		expect(result.averageSpeed).toBe(1.25);
		expect(result.activeDays).toBe(1);
		expect(result.longestStreak).toBe(1);
		expect(result.completedCount).toBe(1);
		expect(result.showTotals[0]).toMatchObject({ id: 'p1', ms: 600_000, episodes: 1 });
		expect(result.hourTotals[8]).toBe(600_000);
		expect(result.categoryTotals[0]).toEqual({ label: 'Technology', ms: 600_000 });
	});

	it('finds the longest consecutive local-day streak', () => {
		const at = (iso: string) => {
			const started_at = new Date(iso).getTime();
			return { started_at, ended_at: started_at + 600_000 };
		};
		const sessions = [
			session({ id: 'a', ...at('2026-07-20T12:00:00') }),
			session({ id: 'b', ...at('2026-07-21T12:00:00') }),
			session({ id: 'c', ...at('2026-07-23T12:00:00') })
		];
		expect(summarizeListening(sessions, []).longestStreak).toBe(2);
	});

	it('does not count a manually played episode with less than five percent listened', () => {
		const state = {
			episode_id: 'e1',
			podcast_id: 'p1',
			completed: true,
			duration_ms: 30 * 60_000
		} as LocalPlaybackState;
		const barelyStarted = session({ audio_listened_ms: 30_000 });

		expect(summarizeListening([barelyStarted], [state]).completedCount).toBe(0);
	});

	it('counts a completed episode listened past the halfway point', () => {
		const state = {
			episode_id: 'e1',
			podcast_id: 'p1',
			completed: true,
			duration_ms: 30 * 60_000
		} as LocalPlaybackState;
		const heard = session({ audio_listened_ms: 20 * 60_000 });

		expect(summarizeListening([heard], [state]).completedCount).toBe(1);
	});

	it('splits one listening segment across hour and day boundaries', () => {
		const start = new Date(2026, 6, 24, 23, 50).getTime();
		const end = new Date(2026, 6, 25, 0, 10).getTime();
		const result = summarizeListening([session({
			started_at: start,
			ended_at: end,
			wall_clock_ms: 1_200_000,
			audio_listened_ms: 1_200_000,
			speed_saved_ms: 0,
			manual_skipped_ms: 0,
			silence_saved_ms: 0,
			speed_weighted_ms: 1_200_000
		})], []);
		expect(result.byDay.size).toBe(2);
		expect(result.hourTotals[23]).toBeCloseTo(600_000);
		expect(result.hourTotals[0]).toBeCloseTo(600_000);
	});
});
