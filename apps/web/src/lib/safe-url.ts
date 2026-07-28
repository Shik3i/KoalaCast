/**
 * Podcast metadata is publisher-controlled. Only expose absolute web URLs as
 * clickable links; schemes such as javascript:, data: and intent: stay inert.
 */
export function safeExternalHref(value: unknown): string | null {
	if (typeof value !== 'string') return null;
	const candidate = value.trim();
	if (!candidate) return null;

	try {
		const parsed = new URL(candidate);
		if (!['http:', 'https:'].includes(parsed.protocol)) return null;
		if (!parsed.hostname || parsed.username || parsed.password) return null;
		return candidate;
	} catch {
		return null;
	}
}
