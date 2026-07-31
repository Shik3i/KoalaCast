import { describe, expect, it } from 'vitest';
import { foreignSettingsOf, mergeForeignSettings } from './settings-merge';

describe('foreignSettingsOf', () => {
	it('treats the Android client’s keys as foreign', () => {
		const foreign = foreignSettingsOf({
			date_format: 'relative',
			ui_language: 'de',
			// Appearance is shared: both clients write it, so neither may treat it
			// as somebody else's and write it twice.
			theme_mode: 'dark',
			palette: 'fjord',
			start_screen: 'inbox',
			visualizer: 'waveform',
			download_budget_bytes: 1024,
			updated_at: 1000
		});

		expect(Object.keys(foreign).sort()).toEqual([
			'download_budget_bytes',
			'start_screen',
			'visualizer'
		]);
	});

	it('treats a key neither client knows yet as foreign rather than dropping it', () => {
		expect(foreignSettingsOf({ something_added_later: true })).toEqual({
			something_added_later: true
		});
	});

	it('keeps updated_at as ours', () => {
		expect(foreignSettingsOf({ updated_at: 5 })).toEqual({});
	});
});

describe('mergeForeignSettings', () => {
	// The regression: a push rebuilt from known fields deleted the other client's
	// keys from the server, so a fresh install restored only half its settings.
	it('hands the other client its keys back on push', () => {
		const merged = mergeForeignSettings(
			{ date_format: 'absolute', updated_at: 2000 },
			{ start_screen: 'inbox', visualizer: 'waveform' }
		);

		expect(merged).toEqual({
			date_format: 'absolute',
			updated_at: 2000,
			start_screen: 'inbox',
			visualizer: 'waveform'
		});
	});

	it('lets our own value win when a foreign snapshot claims one of our keys', () => {
		const merged = mergeForeignSettings(
			{ date_format: 'absolute' },
			{ date_format: 'relative', start_screen: 'inbox' }
		);

		expect(merged.date_format).toBe('absolute');
		expect(merged.start_screen).toBe('inbox');
	});

	it('changes nothing when there are no foreign keys', () => {
		const owned = { date_format: 'relative', updated_at: 7 };

		expect(mergeForeignSettings(owned, {})).toEqual(owned);
	});
});
