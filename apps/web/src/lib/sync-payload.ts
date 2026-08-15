import type { LocalListeningSession } from '$lib/idb/db';

/**
 * The server's own ceilings for a listening session (see
 * `validateListeningSession` in services/api). A payload past them is rejected
 * with a 400, so clamping here is not cosmetic: an unclamped session is a
 * record that can never be uploaded.
 *
 * They are reachable in normal use. A session's span is measured from when
 * playback started to when it was flushed, and a tab left open over a holiday
 * with the player paused produces exactly one such span.
 */
const MAX_SESSION_SPAN_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_SESSION_METRIC_MS = MAX_SESSION_SPAN_MS * 4;

function integerMilliseconds(value: number, ceiling = MAX_SESSION_METRIC_MS): number {
	return Number.isFinite(value) ? Math.min(ceiling, Math.max(0, Math.round(value))) : 0;
}

function timestampMilliseconds(value: number): number {
	return Number.isFinite(value) ? Math.max(0, Math.ceil(value)) : 0;
}

export function normalizeListeningSessionForSync(
	session: LocalListeningSession
): LocalListeningSession {
	const endedAt = Math.max(timestampMilliseconds(session.started_at), timestampMilliseconds(session.ended_at));
	// Hold the span, not the end: the end is when listening actually stopped and
	// is what every last-writer-wins comparison keys on.
	const startedAt = Math.max(timestampMilliseconds(session.started_at), endedAt - MAX_SESSION_SPAN_MS);
	return {
		...session,
		started_at: startedAt,
		ended_at: endedAt,
		wall_clock_ms: integerMilliseconds(session.wall_clock_ms, MAX_SESSION_SPAN_MS),
		audio_listened_ms: integerMilliseconds(session.audio_listened_ms),
		speed_saved_ms: integerMilliseconds(session.speed_saved_ms),
		silence_saved_ms: integerMilliseconds(session.silence_saved_ms),
		manual_skipped_ms: integerMilliseconds(session.manual_skipped_ms),
		intro_outro_skipped_ms: integerMilliseconds(session.intro_outro_skipped_ms),
		speed_weighted_ms: integerMilliseconds(session.speed_weighted_ms)
	};
}
