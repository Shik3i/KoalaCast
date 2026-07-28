import { getCachedContent, putCachedContent } from '$lib/idb/db';

export const CONTENT_TTL = {
	discover: 4 * 60 * 60_000,
	search: 30 * 60_000,
	inbox: 5 * 60_000,
	podcast: 5 * 60 * 60_000,
	episodeList: 10 * 60_000,
	episode: 24 * 60 * 60_000,
	auxiliary: 24 * 60 * 60_000
} as const;

export interface CachedContent<T> {
	value: T;
	storedAt: number;
	fresh: boolean;
}

const revalidations = new Map<string, Promise<unknown>>();
const retryAfter = new Map<string, number>();

/**
 * One background request per cache key, shared by every mounted view. Failures
 * back off briefly so focus/online/navigation events cannot create a retry storm.
 */
export async function revalidateOnce<T>(
	key: string,
	fetchFresh: () => Promise<T>,
	backoffMs = 30_000
): Promise<T | null> {
	if ((retryAfter.get(key) ?? 0) > Date.now()) return null;
	const existing = revalidations.get(key) as Promise<T> | undefined;
	if (existing) return existing;
	const request = fetchFresh()
		.then((value) => {
			retryAfter.delete(key);
			return value;
		})
		.catch(() => {
			retryAfter.set(key, Date.now() + backoffMs);
			return null;
		})
		.finally(() => revalidations.delete(key));
	revalidations.set(key, request);
	return request;
}

export async function readCachedContent<T>(
	key: string,
	ttlMs: number
): Promise<CachedContent<T> | null> {
	try {
		const entry = await getCachedContent<T>(key);
		if (!entry) return null;
		return {
			value: entry.value,
			storedAt: entry.stored_at,
			fresh: Date.now() - entry.stored_at < ttlMs
		};
	} catch {
		return null;
	}
}

export async function cacheContent<T>(key: string, value: T): Promise<void> {
	try {
		await putCachedContent(key, value);
	} catch {
		// A full or unavailable IndexedDB must not turn a successful request into
		// a failed screen.
	}
}

export function contentCacheKey(path: string): string {
	const url = new URL(path, window.location.origin);
	url.searchParams.sort();
	return `${url.pathname}?${url.searchParams.toString()}`;
}
