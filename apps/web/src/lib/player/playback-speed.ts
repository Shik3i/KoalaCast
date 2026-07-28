export const MIN_PLAYBACK_SPEED = 0.25;
export const MAX_PLAYBACK_SPEED = 4;

export function normalizePlaybackSpeed(speed: number): number {
	const finite = Number.isFinite(speed) ? speed : 1;
	return Math.max(MIN_PLAYBACK_SPEED, Math.min(MAX_PLAYBACK_SPEED, Math.round(finite * 100) / 100));
}

export function parsePlaybackSpeed(value: string): number | null {
	const parsed = Number(value.trim().replace(',', '.'));
	if (!Number.isFinite(parsed) || parsed < MIN_PLAYBACK_SPEED || parsed > MAX_PLAYBACK_SPEED) {
		return null;
	}
	return normalizePlaybackSpeed(parsed);
}
