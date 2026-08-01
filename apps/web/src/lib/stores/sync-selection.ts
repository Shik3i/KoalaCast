export function shouldUploadListeningSession(
	endedAt: number,
	lastUploadedAt: number | undefined
): boolean {
	return Number.isFinite(endedAt) && endedAt > 0 && (lastUploadedAt ?? 0) < endedAt;
}
