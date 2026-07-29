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
