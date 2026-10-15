import type { LocalListeningSession } from '#lib/idb/db.ts';

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

/**
 * Ceilings the server enforces for the two library entities below (see
 * `validateSyncOperation` in services/api). A record past them is rejected with a
 * 400, so each helper either keeps a record inside them or reports it as not
 * uploadable instead of letting it fail on every sync.
 */
export const NAMED_QUEUE_MAX_ITEMS = 500;
export const NAMED_QUEUE_MAX_NAME_LENGTH = 200;
export const BOOKMARK_MAX_LABEL_LENGTH = 2000;
export const BOOKMARK_MAX_POSITION_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * A named queue is identified by its name, case-insensitively — that is what the
 * interface does when you save over an existing one. The sync key follows suit, so
 * two devices that independently save "Commute" converge on one queue instead of
 * ending up with two of the same name. `toLowerCase` rather than a locale variant:
 * the key must come out identical on every device.
 */
export function namedQueueSyncKey(name: string): string {
	// Fold accents too: the interface has always treated "Cafe" and "Café" as the
	// same queue (base-letter comparison), and the key must agree with that.
	return name.normalize('NFD').replace(/\p{M}/gu, '').trim().toLowerCase();
}

export interface SyncedNamedQueue {
	/** The sender's local id; the receiver keeps its own id for a queue it already has. */
	id: string;
	name: string;
	items: unknown[];
	updated_at: number;
}

export function namedQueueIsUploadable(queue: { name: string; items: unknown[] }): boolean {
	const key = namedQueueSyncKey(queue.name);
	return (
		key.length > 0 &&
		key.length <= NAMED_QUEUE_MAX_NAME_LENGTH &&
		queue.name.trim().length <= NAMED_QUEUE_MAX_NAME_LENGTH &&
		queue.items.length <= NAMED_QUEUE_MAX_ITEMS
	);
}

/** Reads a received named-queue payload; null when it is not usable. */
export function normalizeNamedQueuePayload(
	payload: unknown,
	entityId: string
): SyncedNamedQueue | null {
	if (!payload || typeof payload !== 'object') return null;
	const p = payload as Record<string, unknown>;
	if (typeof p.name !== 'string') return null;
	const name = p.name.trim();
	if (!name || !Array.isArray(p.items)) return null;
	if (typeof p.updated_at !== 'number' || !Number.isFinite(p.updated_at) || p.updated_at <= 0) {
		return null;
	}
	// The key is derived from the name; a payload filed under another key would
	// overwrite an unrelated queue on this device.
	if (namedQueueSyncKey(name) !== entityId) return null;
	return {
		id: typeof p.id === 'string' ? p.id : '',
		name,
		items: p.items,
		updated_at: Math.trunc(p.updated_at)
	};
}

export interface SyncedTimeBookmark {
	id: string;
	episode_id: string;
	position_ms: number;
	label: string;
	created_at: number;
}

/** The wire form of a local bookmark: the label is clamped to what the server accepts. */
export function bookmarkForSync(bookmark: SyncedTimeBookmark): SyncedTimeBookmark {
	return {
		id: bookmark.id,
		episode_id: bookmark.episode_id,
		position_ms: Math.min(BOOKMARK_MAX_POSITION_MS, Math.max(0, Math.round(bookmark.position_ms))),
		label: bookmark.label.slice(0, BOOKMARK_MAX_LABEL_LENGTH),
		created_at: Math.trunc(bookmark.created_at)
	};
}

export function bookmarkIsUploadable(bookmark: SyncedTimeBookmark): boolean {
	return (
		bookmark.id.length > 0 &&
		bookmark.id.length <= 128 &&
		bookmark.episode_id.trim().length > 0 &&
		bookmark.episode_id.length <= 512 &&
		Number.isFinite(bookmark.position_ms) &&
		Number.isFinite(bookmark.created_at) &&
		bookmark.created_at > 0
	);
}

/** Reads a received bookmark payload; null when it is not usable. */
export function normalizeBookmarkPayload(
	payload: unknown,
	entityId: string
): SyncedTimeBookmark | null {
	if (!payload || typeof payload !== 'object') return null;
	const p = payload as Record<string, unknown>;
	if (p.id !== entityId || typeof p.episode_id !== 'string' || !p.episode_id.trim()) return null;
	if (typeof p.position_ms !== 'number' || !Number.isFinite(p.position_ms) || p.position_ms < 0) {
		return null;
	}
	if (typeof p.created_at !== 'number' || !Number.isFinite(p.created_at) || p.created_at <= 0) {
		return null;
	}
	return {
		id: entityId,
		episode_id: p.episode_id,
		position_ms: Math.min(BOOKMARK_MAX_POSITION_MS, Math.round(p.position_ms)),
		label: typeof p.label === 'string' ? p.label.slice(0, BOOKMARK_MAX_LABEL_LENGTH) : '',
		created_at: Math.trunc(p.created_at)
	};
}
