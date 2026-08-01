/**
 * Routes podcast artwork through the backend image proxy by default. The user
 * may opt into direct publisher-host requests from Privacy settings.
 */
let proxyImagesEnabled = true;

export function setArtworkProxyEnabled(enabled: boolean): void {
	proxyImagesEnabled = enabled;
}

export function optimizeArtwork(
	url: string | null | undefined,
	targetSize = 300,
	proxyImages = proxyImagesEnabled
): string {
	if (!url) return '/cover-placeholder.webp';

	// A protocol-relative URL (`//cdn.example/cover.jpg`) is remote, not a local
	// path. Treating every leading slash as local would silently bypass the
	// privacy proxy.
	if ((url.startsWith('/') && !url.startsWith('//')) || url.startsWith('data:')) {
		return url;
	}
	const normalized = url.startsWith('//')
		? `${typeof location === 'undefined' ? 'https:' : location.protocol}${url}`
		: url;
	if (!proxyImages) return normalized;

	return `/api/v1/proxy/image?url=${encodeURIComponent(normalized)}&w=${targetSize}`;
}

const preloadRequests = new Map<string, Promise<void>>();
export const SUBSCRIPTION_ARTWORK_SIZE = 220;

/**
 * Warms the exact browser-cache entry used by a destination view. Artwork proxy
 * URLs include their requested width, so a cached 96px list thumbnail is not a
 * cache hit for a 300px detail cover.
 */
export function preloadArtwork(url: string | null | undefined, targetSize = 300): Promise<void> {
	if (typeof Image === 'undefined' || !url) return Promise.resolve();
	const src = optimizeArtwork(url, targetSize);
	const existing = preloadRequests.get(src);
	if (existing) return existing;

	const request = new Promise<void>((resolve) => {
		const image = new Image();
		image.onload = () => resolve();
		image.onerror = () => resolve();
		image.src = src;
	}).finally(() => preloadRequests.delete(src));

	preloadRequests.set(src, request);
	return request;
}

/** Keep every subscribed show's canonical library cover in the browser/SW cache. */
export async function preloadSubscriptionArtwork(
	subscriptions: Array<{ artwork_url?: string | null }>
): Promise<void> {
	await Promise.all(
		subscriptions.map((subscription) =>
			preloadArtwork(subscription.artwork_url, SUBSCRIPTION_ARTWORK_SIZE)
		)
	);
}
