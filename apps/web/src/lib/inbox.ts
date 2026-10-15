export function dedupeInboxEpisodes<T extends { id?: string | null }>(episodes: T[]): T[] {
	const byId = new Map<string, T>();
	for (const episode of episodes) {
		const id = typeof episode.id === 'string' ? episode.id.trim() : '';
		if (!id) continue;
		byId.set(id, id === episode.id ? episode : ({ ...episode, id } as T));
	}
	return [...byId.values()];
}

export interface InboxBatchRequestFeed {
	podcast_id: string;
	/** Newest publication time (unix seconds) the caller already has; 0 for none. */
	since: number;
}

export interface InboxBatchEpisode {
	id: string;
	podcast_id?: string;
	title: string;
	description?: string;
	pub_date?: number;
	duration_ms?: number;
	enclosure_url: string;
	artwork_url?: string;
}

export interface InboxBatchFeed {
	podcast_id: string;
	found: boolean;
	episodes: InboxBatchEpisode[];
}

/** The server accepts at most this many feeds per request. */
export const INBOX_BATCH_MAX_FEEDS = 100;

function isBatchFeed(value: unknown): value is InboxBatchFeed {
	if (!value || typeof value !== 'object') return false;
	const feed = value as Record<string, unknown>;
	return (
		typeof feed.podcast_id === 'string' &&
		typeof feed.found === 'boolean' &&
		Array.isArray(feed.episodes)
	);
}

/**
 * Refreshes many feeds with one request per 100 feeds instead of one per feed.
 *
 * It is an optimisation only: a chunk that fails for any reason (network, status,
 * malformed body) simply contributes nothing, and the caller falls back to the
 * per-feed route for every feed missing from the returned map. Feeds the server
 * reports as `found: false` are left out for the same reason.
 */
export async function fetchInboxBatch(
	feeds: InboxBatchRequestFeed[],
	limitPerFeed: number,
	fetchImpl: typeof fetch = fetch
): Promise<Map<string, InboxBatchFeed>> {
	const result = new Map<string, InboxBatchFeed>();
	for (let start = 0; start < feeds.length; start += INBOX_BATCH_MAX_FEEDS) {
		const chunk = feeds.slice(start, start + INBOX_BATCH_MAX_FEEDS);
		try {
			const res = await fetchImpl('/api/v1/inbox/episodes', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ feeds: chunk, limit_per_feed: limitPerFeed }),
				cache: 'no-store'
			});
			if (!res.ok) continue;
			const data = (await res.json()) as { feeds?: unknown };
			if (!Array.isArray(data.feeds)) continue;
			const requested = new Set(chunk.map((feed) => feed.podcast_id));
			for (const feed of data.feeds) {
				if (isBatchFeed(feed) && feed.found && requested.has(feed.podcast_id)) {
					result.set(feed.podcast_id, feed);
				}
			}
		} catch {
			// Leave this chunk to the per-feed fallback.
		}
	}
	return result;
}
