import { describe, expect, it, vi } from 'vitest';
import { INBOX_BATCH_MAX_FEEDS, dedupeInboxEpisodes, fetchInboxBatch } from './inbox';

describe('dedupeInboxEpisodes', () => {
	it('removes invalid and duplicate episode ids while keeping the newest value', () => {
		const result = dedupeInboxEpisodes([
			{ id: 'episode-1', title: 'old' },
			{ id: '', title: 'invalid' },
			{ id: null, title: 'invalid' },
			{ id: ' episode-1 ', title: 'new' },
			{ id: 'episode-2', title: 'other' }
		]);

		expect(result).toEqual([
			{ id: 'episode-1', title: 'new' },
			{ id: 'episode-2', title: 'other' }
		]);
	});
});

describe('fetchInboxBatch', () => {
	const episode = (id: string) => ({ id, title: id, enclosure_url: `https://cdn.test/${id}.mp3` });
	const respond = (body: unknown, status = 200) =>
		new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

	it('posts the watermarks once and keys the result by podcast id', async () => {
		const fetchImpl = vi.fn(async () =>
			respond({
				feeds: [
					{ podcast_id: 'a', found: true, episodes: [episode('a1')] },
					{ podcast_id: 'b', found: true, episodes: [] }
				]
			})
		);
		const result = await fetchInboxBatch(
			[
				{ podcast_id: 'a', since: 10 },
				{ podcast_id: 'b', since: 0 }
			],
			15,
			fetchImpl as unknown as typeof fetch
		);
		expect(fetchImpl).toHaveBeenCalledTimes(1);
		const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
		expect(url).toBe('/api/v1/inbox/episodes');
		expect(init.method).toBe('POST');
		expect(JSON.parse(init.body as string)).toEqual({
			feeds: [
				{ podcast_id: 'a', since: 10 },
				{ podcast_id: 'b', since: 0 }
			],
			limit_per_feed: 15
		});
		expect([...result.keys()]).toEqual(['a', 'b']);
		expect(result.get('a')?.episodes[0].id).toBe('a1');
	});

	it('leaves unknown feeds and unrequested ids to the per-feed fallback', async () => {
		const fetchImpl = vi.fn(async () =>
			respond({
				feeds: [
					{ podcast_id: 'known', found: true, episodes: [episode('k1')] },
					{ podcast_id: 'missing', found: false, episodes: [] },
					{ podcast_id: 'stranger', found: true, episodes: [episode('s1')] }
				]
			})
		);
		const result = await fetchInboxBatch(
			[
				{ podcast_id: 'known', since: 0 },
				{ podcast_id: 'missing', since: 0 }
			],
			15,
			fetchImpl as unknown as typeof fetch
		);
		expect([...result.keys()]).toEqual(['known']);
	});

	it.each([
		['a failing status', () => respond({ error: 'x' }, 500)],
		['a body that is not the contract', () => respond({ feeds: 'nope' })],
		['malformed feed entries', () => respond({ feeds: [{ podcast_id: 'a' }, null, 7] })]
	])('returns nothing for %s so every feed falls back', async (_name, reply) => {
		const result = await fetchInboxBatch([{ podcast_id: 'a', since: 0 }], 15, (async () =>
			reply()) as unknown as typeof fetch);
		expect(result.size).toBe(0);
	});

	it('survives a network error', async () => {
		const result = await fetchInboxBatch([{ podcast_id: 'a', since: 0 }], 15, (async () => {
			throw new TypeError('offline');
		}) as unknown as typeof fetch);
		expect(result.size).toBe(0);
	});

	it('splits large lists into chunks the server accepts and keeps good chunks when one fails', async () => {
		const feeds = Array.from({ length: INBOX_BATCH_MAX_FEEDS + 5 }, (_, i) => ({
			podcast_id: `p${i}`,
			since: 0
		}));
		let call = 0;
		const fetchImpl = vi.fn(async (_url: string, init: RequestInit) => {
			call += 1;
			const sent = JSON.parse(init.body as string).feeds as { podcast_id: string }[];
			expect(sent.length).toBeLessThanOrEqual(INBOX_BATCH_MAX_FEEDS);
			if (call === 2) return respond({}, 503);
			return respond({
				feeds: sent.map((feed) => ({ podcast_id: feed.podcast_id, found: true, episodes: [] }))
			});
		});
		const result = await fetchInboxBatch(feeds, 15, fetchImpl as unknown as typeof fetch);
		expect(fetchImpl).toHaveBeenCalledTimes(2);
		expect(result.size).toBe(INBOX_BATCH_MAX_FEEDS);
		expect(result.has('p0')).toBe(true);
		expect(result.has(`p${INBOX_BATCH_MAX_FEEDS}`)).toBe(false);
	});
});
