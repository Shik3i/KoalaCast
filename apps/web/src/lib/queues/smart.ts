/**
 * Smart queues: a saved set of rules over the episodes the app already knows
 * about, rather than a hand-picked list that goes stale the moment a show
 * publishes.
 *
 * "Everything unplayed under twenty minutes from this week" is a question with a
 * different answer every day, and answering it by hand meant scrolling the Inbox
 * and adding episodes one at a time. The rules live in IndexedDB and the answer
 * is recomputed on every open.
 *
 * Deliberately evaluated over cached data only — the per-subscription episode
 * lists the Inbox already stores — so opening the Library does not fan out into
 * one request per show.
 */

export interface SmartQueueRules {
	/** Only episodes at most this long. 0 means no limit. */
	maxDurationMs: number;
	/** Only episodes at least this long, to filter out trailers. 0 means no limit. */
	minDurationMs: number;
	/** Published within this many days. 0 means any age. */
	withinDays: number;
	unplayedOnly: boolean;
	downloadedOnly: boolean;
	/** Empty means every subscribed show. */
	podcastIds: string[];
	/** How many episodes the queue yields at most. */
	limit: number;
	sort: 'newest' | 'oldest' | 'shortest';
}

export interface SmartQueue {
	id: string;
	name: string;
	rules: SmartQueueRules;
	updated_at: number;
}

export interface SmartQueueCandidate {
	id: string;
	podcast_id: string;
	podcast_title: string;
	title: string;
	artwork_url?: string;
	enclosure_url: string;
	duration_ms?: number;
	pub_date?: number;
}

export interface SmartQueueContext {
	completedIds: ReadonlySet<string>;
	downloadedIds: ReadonlySet<string>;
	now: number;
}

export const DEFAULT_SMART_QUEUE_RULES: SmartQueueRules = {
	maxDurationMs: 0,
	minDurationMs: 0,
	withinDays: 0,
	unplayedOnly: true,
	downloadedOnly: false,
	podcastIds: [],
	limit: 20,
	sort: 'newest'
};

export function normalizeRules(rules: Partial<SmartQueueRules> | undefined): SmartQueueRules {
	const source = rules ?? {};
	const sort = source.sort;
	return {
		maxDurationMs: positive(source.maxDurationMs),
		minDurationMs: positive(source.minDurationMs),
		withinDays: positive(source.withinDays),
		unplayedOnly: source.unplayedOnly ?? DEFAULT_SMART_QUEUE_RULES.unplayedOnly,
		downloadedOnly: source.downloadedOnly ?? DEFAULT_SMART_QUEUE_RULES.downloadedOnly,
		podcastIds: Array.isArray(source.podcastIds)
			? source.podcastIds.filter((id): id is string => typeof id === 'string' && id.length > 0)
			: [],
		limit: Math.min(200, Math.max(1, Math.round(positive(source.limit) || DEFAULT_SMART_QUEUE_RULES.limit))),
		sort: sort === 'oldest' || sort === 'shortest' ? sort : 'newest'
	};
}

function positive(value: unknown): number {
	const numeric = Number(value);
	return Number.isFinite(numeric) && numeric > 0 ? numeric : 0;
}

export function matchesSmartQueue(
	episode: SmartQueueCandidate,
	rules: SmartQueueRules,
	context: SmartQueueContext
): boolean {
	if (!episode.enclosure_url) return false;
	if (rules.podcastIds.length > 0 && !rules.podcastIds.includes(episode.podcast_id)) return false;
	if (rules.unplayedOnly && context.completedIds.has(episode.id)) return false;
	if (rules.downloadedOnly && !context.downloadedIds.has(episode.id)) return false;

	const duration = episode.duration_ms ?? 0;
	// A missing duration is common in feeds and is not a reason to drop an episode
	// from an "under 20 minutes" queue silently — but it cannot satisfy a *minimum*
	// either, because nothing is known about it.
	if (rules.maxDurationMs > 0 && duration > rules.maxDurationMs) return false;
	if (rules.minDurationMs > 0 && duration < rules.minDurationMs) return false;

	if (rules.withinDays > 0) {
		const published = (episode.pub_date ?? 0) * 1000;
		if (!published) return false;
		if (context.now - published > rules.withinDays * 86_400_000) return false;
	}
	return true;
}

export function evaluateSmartQueue(
	episodes: readonly SmartQueueCandidate[],
	rules: SmartQueueRules,
	context: SmartQueueContext
): SmartQueueCandidate[] {
	const matched = episodes.filter((episode) => matchesSmartQueue(episode, rules, context));
	const seen = new Set<string>();
	const unique = matched.filter((episode) => {
		if (seen.has(episode.id)) return false;
		seen.add(episode.id);
		return true;
	});
	unique.sort((a, b) => {
		if (rules.sort === 'shortest') return (a.duration_ms ?? 0) - (b.duration_ms ?? 0);
		if (rules.sort === 'oldest') return (a.pub_date ?? 0) - (b.pub_date ?? 0);
		return (b.pub_date ?? 0) - (a.pub_date ?? 0);
	});
	return unique.slice(0, rules.limit);
}

/** Total listening time of a result set, for the "this fills 2h 10m" hint. */
export function totalDurationMs(episodes: readonly SmartQueueCandidate[]): number {
	return episodes.reduce((sum, episode) => sum + (episode.duration_ms ?? 0), 0);
}
