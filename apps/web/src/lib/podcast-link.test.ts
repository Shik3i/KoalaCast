import { describe, expect, it } from 'vitest';
import { podcastHref } from './podcast-link';
import type { LocalSubscription } from '$lib/idb/db';

function subscription(overrides: Partial<LocalSubscription> = {}): LocalSubscription {
	return {
		podcast_id: 'podcast-id',
		feed_url: 'https://example.com/feed.xml',
		title: 'Podcast',
		artwork_url: '',
		added_at: 1,
		...overrides
	};
}

describe('podcastHref', () => {
	it('uses the canonical podcast id when available', () => {
		expect(podcastHref(subscription())).toBe('/podcast/podcast-id');
	});

	it('routes unresolved OPML feeds through lazy resolution', () => {
		const feedUrl = 'https://example.com/feed.xml?category=news&lang=de';
		expect(podcastHref(subscription({ podcast_id: feedUrl, feed_url: feedUrl }))).toBe(
			`/podcast/imported?feed_url=${encodeURIComponent(feedUrl)}`
		);
	});
});
