/**
 * Optimizes podcast artwork image URLs by requesting appropriate dimensions
 * from CDN providers (e.g. Apple Podcasts, Spotify) to prevent downloading
 * massive uncompressed 3000x3000px JPEGs on mobile network connections.
 */
export function optimizeArtwork(url: string | null | undefined, targetSize = 300): string {
	if (!url) return '/placeholder.svg';

	try {
		// Apple Podcasts / iTunes CDN dynamic resizing (e.g. 3000x3000bb -> 300x300bb)
		if (url.includes('mzstatic.com')) {
			return url.replace(/\/\d+x\d+bb\./, `/${targetSize}x${targetSize}bb.`);
		}
		// Spotify CDN image resizing (ba8a = 640px -> f68d = 300px)
		if (url.includes('i.scdn.co/image/ab6765630000ba8a') && targetSize <= 300) {
			return url.replace('ab6765630000ba8a', 'ab6765630000f68d');
		}
	} catch (_) {}

	return url;
}
