import { describe, expect, it } from 'vitest';
import {
	DEFAULT_SMART_QUEUE_RULES,
	evaluateSmartQueue,
	matchesSmartQueue,
	normalizeRules,
	totalDurationMs,
	type SmartQueueCandidate
} from './smart';

const NOW = Date.UTC(2026, 7, 10);
const DAY = 86_400_000;

function episode(overrides: Partial<SmartQueueCandidate> = {}): SmartQueueCandidate {
	return {
		id: 'e1',
		podcast_id: 'p1',
		podcast_title: 'Show',
		title: 'Episode',
		enclosure_url: 'https://cdn.example/e1.mp3',
		duration_ms: 10 * 60_000,
		pub_date: Math.floor((NOW - DAY) / 1000),
		...overrides
	};
}

const context = { completedIds: new Set<string>(), downloadedIds: new Set<string>(), now: NOW };

describe('matchesSmartQueue', () => {
	it('rejects an episode with no audio', () => {
		expect(matchesSmartQueue(episode({ enclosure_url: '' }), DEFAULT_SMART_QUEUE_RULES, context)).toBe(false);
	});

	it('applies the duration window', () => {
		const rules = normalizeRules({ maxDurationMs: 5 * 60_000 });
		expect(matchesSmartQueue(episode(), rules, context)).toBe(false);
		expect(matchesSmartQueue(episode({ duration_ms: 4 * 60_000 }), rules, context)).toBe(true);
	});

	it('keeps an episode of unknown length under a maximum but not under a minimum', () => {
		const withoutDuration = episode({ duration_ms: undefined });
		expect(matchesSmartQueue(withoutDuration, normalizeRules({ maxDurationMs: 60_000 }), context)).toBe(true);
		expect(matchesSmartQueue(withoutDuration, normalizeRules({ minDurationMs: 60_000 }), context)).toBe(false);
	});

	it('applies the age window and drops undated episodes from it', () => {
		const rules = normalizeRules({ withinDays: 7 });
		expect(matchesSmartQueue(episode(), rules, context)).toBe(true);
		expect(
			matchesSmartQueue(episode({ pub_date: Math.floor((NOW - 30 * DAY) / 1000) }), rules, context)
		).toBe(false);
		expect(matchesSmartQueue(episode({ pub_date: undefined }), rules, context)).toBe(false);
	});

	it('honours the unplayed and downloaded switches', () => {
		const played = { ...context, completedIds: new Set(['e1']) };
		expect(matchesSmartQueue(episode(), normalizeRules({ unplayedOnly: true }), played)).toBe(false);
		expect(matchesSmartQueue(episode(), normalizeRules({ unplayedOnly: false }), played)).toBe(true);
		expect(matchesSmartQueue(episode(), normalizeRules({ downloadedOnly: true }), context)).toBe(false);
	});

	it('restricts to the chosen shows when any are chosen', () => {
		expect(matchesSmartQueue(episode(), normalizeRules({ podcastIds: ['p2'] }), context)).toBe(false);
		expect(matchesSmartQueue(episode(), normalizeRules({ podcastIds: ['p1'] }), context)).toBe(true);
		expect(matchesSmartQueue(episode(), normalizeRules({ podcastIds: [] }), context)).toBe(true);
	});
});

describe('evaluateSmartQueue', () => {
	const episodes = [
		episode({ id: 'a', duration_ms: 30 * 60_000, pub_date: Math.floor((NOW - DAY) / 1000) }),
		episode({ id: 'b', duration_ms: 5 * 60_000, pub_date: Math.floor((NOW - 3 * DAY) / 1000) }),
		episode({ id: 'c', duration_ms: 12 * 60_000, pub_date: Math.floor((NOW - 2 * DAY) / 1000) })
	];

	it('sorts newest first by default', () => {
		expect(evaluateSmartQueue(episodes, normalizeRules({}), context).map((e) => e.id)).toEqual(['a', 'c', 'b']);
	});

	it('can sort oldest first and shortest first', () => {
		expect(evaluateSmartQueue(episodes, normalizeRules({ sort: 'oldest' }), context).map((e) => e.id)).toEqual(['b', 'c', 'a']);
		expect(evaluateSmartQueue(episodes, normalizeRules({ sort: 'shortest' }), context).map((e) => e.id)).toEqual(['b', 'c', 'a']);
	});

	it('applies the limit', () => {
		expect(evaluateSmartQueue(episodes, normalizeRules({ limit: 2 }), context)).toHaveLength(2);
	});

	it('drops duplicates that appear in more than one cached list', () => {
		const withDuplicate = [...episodes, episode({ id: 'a' })];
		expect(evaluateSmartQueue(withDuplicate, normalizeRules({}), context)).toHaveLength(3);
	});

	it('sums the listening time of what it returns', () => {
		expect(totalDurationMs(evaluateSmartQueue(episodes, normalizeRules({}), context))).toBe(47 * 60_000);
	});
});

describe('normalizeRules', () => {
	it('repairs nonsense instead of trusting stored input', () => {
		const rules = normalizeRules({
			maxDurationMs: -5,
			limit: 9_999,
			sort: 'sideways' as never,
			podcastIds: ['ok', '', 42 as never]
		});
		expect(rules.maxDurationMs).toBe(0);
		expect(rules.limit).toBe(200);
		expect(rules.sort).toBe('newest');
		expect(rules.podcastIds).toEqual(['ok']);
	});
});
