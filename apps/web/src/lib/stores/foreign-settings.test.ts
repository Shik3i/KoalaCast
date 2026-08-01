import { describe, expect, it } from 'vitest';
import { foreignSettingsOf, mergeForeignSettings } from './settings-merge';

describe('foreignSettingsOf', () => {
	it('recognizes every setting shared with Android as owned', () => {
		const foreign = foreignSettingsOf({
			date_format: 'relative',
			ui_language: 'de',
			// Appearance is shared: both clients write it, so neither may treat it
			// as somebody else's and write it twice.
			theme_mode: 'dark',
			palette: 'fjord',
			start_screen: 'inbox',
			visualizer: 'waveform',
			proxy_images: false,
			download_wifi_only: false,
			auto_download_count: 5,
			download_retention: '14d',
			download_concurrency: 3,
			download_budget_bytes: 1024,
			interests: ['Technology'],
			hidden_genres: [],
			hidden_podcasts: [],
			default_inbox_mode: 'latest',
			languages: ['en'],
			volume_boost: true,
			skip_silence: true,
			playback_speed: 1.25,
			updated_at: 1000
		});

		expect(foreign).toEqual({});
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
	it('hands future clients their unknown keys back on push', () => {
		const merged = mergeForeignSettings(
			{ date_format: 'absolute', updated_at: 2000 },
			{ future_download_rule: 'night-only' }
		);

		expect(merged).toEqual({
			date_format: 'absolute',
			updated_at: 2000,
			future_download_rule: 'night-only'
		});
	});

	it('lets our own value win when a foreign snapshot claims one of our keys', () => {
		const merged = mergeForeignSettings(
			{ date_format: 'absolute' },
			{ date_format: 'relative', future_setting: true }
		);

		expect(merged.date_format).toBe('absolute');
		expect(merged.future_setting).toBe(true);
	});

	it('changes nothing when there are no foreign keys', () => {
		const owned = { date_format: 'relative', updated_at: 7 };

		expect(mergeForeignSettings(owned, {})).toEqual(owned);
	});
});
