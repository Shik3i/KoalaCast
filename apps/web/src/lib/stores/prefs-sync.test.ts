import { beforeEach, describe, expect, it } from 'vitest';
import { prefs } from './prefs.svelte';

describe('shared preference sync', () => {
	beforeEach(() => prefs.resetSynced());

	it('applies every newly shared cross-client setting', () => {
		prefs.applySynced(
			{
				updated_at: 100,
				start_screen: 'library',
				visualizer: 'waveform',
				proxy_images: false,
				download_wifi_only: false,
				auto_download_count: 10,
				download_retention: '30d',
				download_concurrency: 4,
				download_budget_bytes: 0
			},
			{ authoritative: true }
		);

		expect(prefs.startScreen).toBe('library');
		expect(prefs.visualizer).toBe('waveform');
		expect(prefs.proxyImages).toBe(false);
		expect(prefs.downloadWifiOnly).toBe(false);
		expect(prefs.autoDownloadCount).toBe(10);
		expect(prefs.downloadRetention).toBe('30d');
		expect(prefs.downloadConcurrency).toBe(4);
		expect(prefs.downloadBudgetBytes).toBe(0);
	});

	it('clamps hostile numeric settings and preserves unlimited budget', () => {
		prefs.applySynced(
			{
				updated_at: 200,
				auto_download_count: 999,
				download_concurrency: -10,
				download_budget_bytes: Number.POSITIVE_INFINITY
			},
			{ authoritative: true }
		);

		expect(prefs.autoDownloadCount).toBe(10);
		expect(prefs.downloadConcurrency).toBe(1);
		expect(prefs.downloadBudgetBytes).toBe(2_048 * 1024 * 1024);

		prefs.setDownloadBudgetBytes(0);
		expect(prefs.syncPayload().download_budget_bytes).toBe(0);
	});

	it.each(['off', 'level', 'waveform', 'bars', 'pulse', 'spectrum', 'ribbon', 'vu', 'constellation'] as const)(
		'accepts and syncs the %s visualizer',
		(visualizer) => {
			prefs.applySynced({ updated_at: 300, visualizer }, { authoritative: true });

			expect(prefs.visualizer).toBe(visualizer);
			expect(prefs.syncPayload().visualizer).toBe(visualizer);
		}
	);

	it('maps the retired dots visualizer onto bars rather than off', () => {
		// A peer still running an older build syncs "dots". Falling through to the
		// default would silently switch the listener's visualiser off.
		prefs.applySynced({ updated_at: 400, visualizer: 'dots' }, { authoritative: true });

		expect(prefs.visualizer).toBe('bars');
	});
});
