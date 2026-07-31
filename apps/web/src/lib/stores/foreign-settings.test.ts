import { describe, expect, it } from 'vitest';
import { foreignSettingsOf, mergeForeignSettings } from './settings-merge';

describe('foreignSettingsOf', () => {
	it('treats the Android client’s keys as foreign', () => {
		const foreign = foreignSettingsOf({
			date_format: 'relative',
			ui_language: 'de',
			theme_mode: 'dark',
			palette: 'fjord',
			start_screen: 'inbox',
			download_budget_bytes: 1024,
			updated_at: 1000
		});

		expect(Object.keys(foreign).sort()).toEqual([
			'download_budget_bytes',
			'palette',
			'start_screen',
			'theme_mode'
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
			{ theme_mode: 'dark', palette: 'fjord' }
		);

		expect(merged).toEqual({
			date_format: 'absolute',
			updated_at: 2000,
			theme_mode: 'dark',
			palette: 'fjord'
		});
	});

	it('lets our own value win when a foreign snapshot claims one of our keys', () => {
		const merged = mergeForeignSettings(
			{ date_format: 'absolute' },
			{ date_format: 'relative', theme_mode: 'dark' }
		);

		expect(merged.date_format).toBe('absolute');
		expect(merged.theme_mode).toBe('dark');
	});

	it('changes nothing when there are no foreign keys', () => {
		const owned = { date_format: 'relative', updated_at: 7 };

		expect(mergeForeignSettings(owned, {})).toEqual(owned);
	});
});
