import type { LocalSubscription } from '$lib/idb/db';

export function podcastHref(subscription: LocalSubscription): string {
	if (subscription.podcast_id === subscription.feed_url) {
		return `/podcast/imported?feed_url=${encodeURIComponent(subscription.feed_url)}`;
	}
	return `/podcast/${encodeURIComponent(subscription.podcast_id)}`;
}
