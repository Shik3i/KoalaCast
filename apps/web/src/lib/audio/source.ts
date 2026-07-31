export function isCrossOriginAudio(source: string, pageOrigin: string): boolean {
	if (!source || source.startsWith('/offline/audio/')) return false;
	try {
		const url = new URL(source, pageOrigin);
		return (url.protocol === 'http:' || url.protocol === 'https:') && url.origin !== pageOrigin;
	} catch {
		return false;
	}
}

export function audioEffectsProxyUrl(source: string): string {
	return `/api/v1/proxy/audio?url=${encodeURIComponent(source)}`;
}

export interface ResolvedAudioSource {
	/** The URL to actually load. Same-origin, the publisher's, or the proxy's. */
	url: string;
	/** Whether the browser must be told `crossorigin="anonymous"` for this URL. */
	crossOrigin: boolean;
	/** How the decision was reached, for diagnostics and tests. */
	via: 'same-origin' | 'direct' | 'redirect-resolved' | 'proxy';
}

/**
 * Whether the publisher lets this origin read the audio.
 *
 * Web Audio — silence skipping, volume boost, any visualiser — needs sample
 * access, and the browser refuses to expose cross-origin media without CORS.
 * That is a security boundary, not an implementation gap: no client-side trick
 * gets around it, because being able to read bytes you were not granted is the
 * whole thing CORS exists to prevent.
 *
 * A ranged request rather than a plain GET: the old probe fetched an episode and
 * threw the body away, which cost a full download's worth of upstream traffic per
 * check. One byte answers the same question.
 */
export async function publisherAllowsAudioEffects(
	source: string,
	pageOrigin: string,
	fetcher: typeof fetch = fetch
): Promise<boolean> {
	if (!isCrossOriginAudio(source, pageOrigin)) return true;
	const controller = new AbortController();
	const timeout = setTimeout(() => controller.abort(), 8000);
	try {
		const response = await fetcher(source, {
			method: 'GET',
			headers: { Range: 'bytes=0-0' },
			mode: 'cors',
			credentials: 'omit',
			cache: 'no-store',
			signal: controller.signal
		});
		void response.body?.cancel();
		return response.ok;
	} catch {
		return false;
	} finally {
		clearTimeout(timeout);
	}
}

/**
 * Asks the server where an enclosure actually lives.
 *
 * Enclosures are routinely published behind prefix trackers (podtrac, chartable,
 * pdst.fm) that do not send CORS headers even though the CDN they redirect to
 * does. The browser cannot follow that chain and inspect it — a failed CORS
 * request tells it nothing about where it landed — so the server resolves it and
 * reports the final URL. The audio itself still streams from the publisher; only
 * the lookup goes through KoalaCast.
 */
export async function resolveAudioRedirect(
	source: string,
	pageOrigin: string,
	fetcher: typeof fetch = fetch
): Promise<{ url: string; corsAllowed: boolean } | null> {
	const controller = new AbortController();
	const timeout = setTimeout(() => controller.abort(), 9000);
	try {
		const response = await fetcher(
			`/api/v1/proxy/audio/resolve?url=${encodeURIComponent(source)}&origin=${encodeURIComponent(pageOrigin)}`,
			{ cache: 'no-store', signal: controller.signal }
		);
		if (!response.ok) return null;
		const body = await response.json();
		if (typeof body?.url !== 'string' || !body.url) return null;
		return { url: body.url, corsAllowed: body.cors_allowed === true };
	} catch {
		return null;
	} finally {
		clearTimeout(timeout);
	}
}

/**
 * Picks the source to play when audio effects are wanted, preferring in order:
 * the publisher directly, the publisher's real host behind a redirect, and only
 * then this server. Every step avoided keeps an episode off the instance.
 */
export async function resolveAudioSourceForEffects(
	source: string,
	pageOrigin: string,
	deps: {
		fetcher?: typeof fetch;
		allows?: typeof publisherAllowsAudioEffects;
		resolve?: typeof resolveAudioRedirect;
	} = {}
): Promise<ResolvedAudioSource> {
	const fetcher = deps.fetcher ?? fetch;
	const allows = deps.allows ?? publisherAllowsAudioEffects;
	const resolve = deps.resolve ?? resolveAudioRedirect;

	if (!isCrossOriginAudio(source, pageOrigin)) {
		return { url: source, crossOrigin: false, via: 'same-origin' };
	}
	if (await allows(source, pageOrigin, fetcher)) {
		return { url: source, crossOrigin: true, via: 'direct' };
	}

	const resolved = await resolve(source, pageOrigin, fetcher);
	if (resolved && resolved.corsAllowed && resolved.url !== source) {
		// Trust but verify: the server asked on our behalf, the browser is the one
		// that has to be satisfied.
		if (await allows(resolved.url, pageOrigin, fetcher)) {
			return { url: resolved.url, crossOrigin: true, via: 'redirect-resolved' };
		}
	}

	return { url: audioEffectsProxyUrl(source), crossOrigin: false, via: 'proxy' };
}
