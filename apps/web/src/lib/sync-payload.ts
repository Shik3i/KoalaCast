import type { LocalListeningSession } from '$lib/idb/db';

function integerMilliseconds(value: number): number {
	return Number.isFinite(value) ? Math.max(0, Math.round(value)) : 0;
}

function timestampMilliseconds(value: number): number {
	return Number.isFinite(value) ? Math.max(0, Math.ceil(value)) : 0;
}

export function normalizeListeningSessionForSync(
	session: LocalListeningSession
): LocalListeningSession {
	const startedAt = timestampMilliseconds(session.started_at);
	return {
		...session,
		started_at: startedAt,
		ended_at: Math.max(startedAt, timestampMilliseconds(session.ended_at)),
		wall_clock_ms: integerMilliseconds(session.wall_clock_ms),
		audio_listened_ms: integerMilliseconds(session.audio_listened_ms),
		speed_saved_ms: integerMilliseconds(session.speed_saved_ms),
		silence_saved_ms: integerMilliseconds(session.silence_saved_ms),
		manual_skipped_ms: integerMilliseconds(session.manual_skipped_ms),
		intro_outro_skipped_ms: integerMilliseconds(session.intro_outro_skipped_ms),
		speed_weighted_ms: integerMilliseconds(session.speed_weighted_ms)
	};
}
