import { describe, expect, it } from 'vitest';
import { normalizePlaybackSpeed, parsePlaybackSpeed } from './playback-speed';

describe('playback speed', () => {
	it('accepts decimal comma and decimal point custom values', () => {
		expect(parsePlaybackSpeed('1,15')).toBe(1.15);
		expect(parsePlaybackSpeed('1.15')).toBe(1.15);
	});

	it('keeps custom values deterministic to two decimals', () => {
		expect(parsePlaybackSpeed('1.157')).toBe(1.16);
		expect(normalizePlaybackSpeed(1.149)).toBe(1.15);
	});

	it('rejects custom values outside the supported range', () => {
		expect(parsePlaybackSpeed('0,24')).toBeNull();
		expect(parsePlaybackSpeed('4.01')).toBeNull();
		expect(parsePlaybackSpeed('fast')).toBeNull();
	});
});
