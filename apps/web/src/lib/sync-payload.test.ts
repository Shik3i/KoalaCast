import { describe, expect, it } from 'vitest';
import {
	BOOKMARK_MAX_LABEL_LENGTH,
	BOOKMARK_MAX_POSITION_MS,
	NAMED_QUEUE_MAX_ITEMS,
	bookmarkForSync,
	bookmarkIsUploadable,
	namedQueueIsUploadable,
	namedQueueSyncKey,
	normalizeBookmarkPayload,
	normalizeListeningSessionForSync,
	normalizeNamedQueuePayload
} from './sync-payload';

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

	it('clamps a session longer than the server accepts instead of shipping a 400', () => {
		const week = 7 * 24 * 60 * 60 * 1000;
		const endedAt = 1_800_000_000_000;
		const normalized = normalizeListeningSessionForSync({
			id: 'session-2',
			episode_id: 'episode-1',
			podcast_id: 'podcast-1',
			title: 'Episode',
			podcast_title: 'Podcast',
			// A tab left open with the player paused over a long holiday.
			started_at: endedAt - week * 3,
			ended_at: endedAt,
			wall_clock_ms: week * 2,
			audio_listened_ms: week * 9,
			speed_saved_ms: 0,
			silence_saved_ms: 0,
			manual_skipped_ms: 0,
			intro_outro_skipped_ms: 0,
			speed_weighted_ms: 0
		});

		expect(normalized.ended_at).toBe(endedAt);
		expect(normalized.ended_at - normalized.started_at).toBe(week);
		expect(normalized.wall_clock_ms).toBe(week);
		expect(normalized.audio_listened_ms).toBe(week * 4);
	});
});

describe('named queue sync identity', () => {
	it('derives one key per name regardless of case, spacing and unicode form', () => {
		expect(namedQueueSyncKey('  Commute ')).toBe('commute');
		expect(namedQueueSyncKey('COMMUTE')).toBe('commute');
		// "é" as one code point versus "e" + combining accent versus plain "e".
		expect(namedQueueSyncKey('Caf\u00e9')).toBe(namedQueueSyncKey('Cafe\u0301'));
		expect(namedQueueSyncKey('Caf\u00e9')).toBe('cafe');
	});

	it('does not depend on the locale (Turkish dotted capital I)', () => {
		expect(namedQueueSyncKey('I')).toBe('i');
	});

	it('accepts a payload filed under the key of its own name', () => {
		const result = normalizeNamedQueuePayload(
			{ id: 'q1', name: ' Commute ', items: [{ episode_id: 'e' }], updated_at: 1234.9 },
			'commute'
		);
		expect(result).toEqual({ id: 'q1', name: 'Commute', items: [{ episode_id: 'e' }], updated_at: 1234 });
	});

	it.each([
		['not an object', 'text'],
		['no name', { items: [], updated_at: 5 }],
		['blank name', { name: '  ', items: [], updated_at: 5 }],
		['items missing', { name: 'x', updated_at: 5 }],
		['items not an array', { name: 'x', items: {}, updated_at: 5 }],
		['no timestamp', { name: 'x', items: [] }],
		['zero timestamp', { name: 'x', items: [], updated_at: 0 }],
		['string timestamp', { name: 'x', items: [], updated_at: '5' }]
	])('rejects a payload with %s', (_label, payload) => {
		expect(normalizeNamedQueuePayload(payload, 'x')).toBeNull();
	});

	it('rejects a payload whose name does not match the key it arrived under', () => {
		expect(normalizeNamedQueuePayload({ name: 'Work', items: [], updated_at: 5 }, 'commute')).toBeNull();
	});

	it('keeps a queue inside the server ceilings', () => {
		const items = (n: number) => Array.from({ length: n }, () => ({}));
		expect(namedQueueIsUploadable({ name: 'ok', items: items(NAMED_QUEUE_MAX_ITEMS) })).toBe(true);
		expect(namedQueueIsUploadable({ name: 'ok', items: items(NAMED_QUEUE_MAX_ITEMS + 1) })).toBe(false);
		expect(namedQueueIsUploadable({ name: 'n'.repeat(200), items: [] })).toBe(true);
		expect(namedQueueIsUploadable({ name: 'n'.repeat(201), items: [] })).toBe(false);
		expect(namedQueueIsUploadable({ name: '   ', items: [] })).toBe(false);
	});
});

describe('time bookmark sync', () => {
	const bookmark = { id: 'b1', episode_id: 'e1', position_ms: 61000.4, label: 'nice', created_at: 1700000000000 };

	it('clamps what it sends to the server ceilings', () => {
		const sent = bookmarkForSync({
			...bookmark,
			position_ms: BOOKMARK_MAX_POSITION_MS * 2,
			label: 'l'.repeat(BOOKMARK_MAX_LABEL_LENGTH + 50)
		});
		expect(sent.position_ms).toBe(BOOKMARK_MAX_POSITION_MS);
		expect(sent.label).toHaveLength(BOOKMARK_MAX_LABEL_LENGTH);
		expect(bookmarkForSync(bookmark).position_ms).toBe(61000);
	});

	it('reports only records the server would accept as uploadable', () => {
		expect(bookmarkIsUploadable(bookmark)).toBe(true);
		expect(bookmarkIsUploadable({ ...bookmark, id: '' })).toBe(false);
		expect(bookmarkIsUploadable({ ...bookmark, episode_id: ' ' })).toBe(false);
		expect(bookmarkIsUploadable({ ...bookmark, created_at: 0 })).toBe(false);
		expect(bookmarkIsUploadable({ ...bookmark, position_ms: Number.NaN })).toBe(false);
	});

	it('reads a received payload and tolerates a missing label', () => {
		expect(normalizeBookmarkPayload({ id: 'b1', episode_id: 'e1', position_ms: 5, created_at: 9 }, 'b1')).toEqual({
			id: 'b1', episode_id: 'e1', position_ms: 5, label: '', created_at: 9
		});
	});

	it.each([
		['id differs from the entity id', { id: 'other', episode_id: 'e', position_ms: 1, created_at: 1 }],
		['no episode', { id: 'b1', position_ms: 1, created_at: 1 }],
		['negative position', { id: 'b1', episode_id: 'e', position_ms: -1, created_at: 1 }],
		['no timestamp', { id: 'b1', episode_id: 'e', position_ms: 1 }],
		['not an object', null]
	])('rejects a payload where %s', (_label, payload) => {
		expect(normalizeBookmarkPayload(payload, 'b1')).toBeNull();
	});
});
