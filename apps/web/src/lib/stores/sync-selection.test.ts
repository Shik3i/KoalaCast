import { describe, expect, it } from 'vitest';
import { shouldUploadListeningSession } from './sync-selection';

describe('listening-session sync selection', () => {
	it('backfills sessions older than the general sync watermark', () => {
		expect(shouldUploadListeningSession(100, undefined)).toBe(true);
	});

	it('does not upload a session whose exact version was already sent', () => {
		expect(shouldUploadListeningSession(100, 100)).toBe(false);
		expect(shouldUploadListeningSession(100, 101)).toBe(false);
	});
});
