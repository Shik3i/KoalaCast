import { describe, expect, it } from 'vitest';
import { blocksWifiOnlyAutoDownload } from './policy';

describe('automatic download network policy', () => {
	it('blocks when the browser cannot prove the connection is unmetered', () => {
		expect(blocksWifiOnlyAutoDownload()).toBe(true);
		expect(blocksWifiOnlyAutoDownload({ saveData: true, type: 'wifi' })).toBe(true);
		expect(blocksWifiOnlyAutoDownload({ type: 'cellular' })).toBe(true);
		expect(blocksWifiOnlyAutoDownload({ effectiveType: 'slow-2g' })).toBe(true);
	});

	it('allows a connection identified as Wi-Fi', () => {
		expect(blocksWifiOnlyAutoDownload({ type: 'wifi' })).toBe(false);
	});
});
