import type { LocalSubscription } from '$lib/idb/db';

export function escapeXml(value: string): string {
	return (value || '').replace(
		/[<>&"']/g,
		(character) =>
			({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[character] as string
	);
}

/**
 * Serialises subscriptions as OPML 2.0.
 *
 * Extracted from the Settings screen so the manual export and the automatic
 * backup cannot drift apart: a backup that writes a slightly different document
 * than the one the listener tested by exporting is not a backup they can rely on.
 */
export function buildOpml(subscriptions: readonly LocalSubscription[]): string {
	const outlines = subscriptions
		.filter((subscription) => subscription.feed_url)
		.map(
			(subscription) =>
				`    <outline type="rss" text="${escapeXml(subscription.title)}" title="${escapeXml(subscription.title)}" xmlUrl="${escapeXml(subscription.feed_url)}" />`
		)
		.join('\n');
	return (
		'<?xml version="1.0" encoding="UTF-8"?>\n' +
		'<opml version="2.0">\n  <head>\n    <title>KoalaCast Subscriptions</title>\n  </head>\n  <body>\n' +
		`${outlines}\n  </body>\n</opml>\n`
	);
}
