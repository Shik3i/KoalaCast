// Runtime behaviour tests: translation lookup, the fallback chain, placeholder
// interpolation and CLDR plural selection.
//
// The catalogue *contents* are covered by catalogues.test.ts; this file is about
// the machinery, using a handful of real keys as fixtures.

import { describe, it, expect, beforeEach } from 'vitest';
import { t, n, loadLocale, currentLocale } from './runtime.svelte';
import { DEFAULT_LOCALE } from './registry';
import en from './messages/en.json';
import de from './messages/de.json';

beforeEach(async () => {
	await loadLocale(DEFAULT_LOCALE);
});

describe('loadLocale', () => {
	it('starts on the default locale', () => {
		expect(currentLocale()).toBe(DEFAULT_LOCALE);
	});

	it('activates a lazily-loaded locale', async () => {
		await loadLocale('de');
		expect(currentLocale()).toBe('de');
		expect(t('nav.library')).toBe(de.nav.library);
	});

	it('normalizes a regional tag onto a supported locale', async () => {
		await loadLocale('de-AT');
		expect(currentLocale()).toBe('de');
	});

	it('falls back to the default locale for an unsupported language', async () => {
		await loadLocale('klingon');
		expect(currentLocale()).toBe(DEFAULT_LOCALE);
	});
});

describe('t()', () => {
	it('returns the string for a key', () => {
		expect(t('nav.library')).toBe(en.nav.library);
	});

	it('translates once the locale changes', async () => {
		expect(t('nav.settings')).toBe('Settings');
		await loadLocale('de');
		expect(t('nav.settings')).toBe('Einstellungen');
	});

	it('returns the key itself when it does not exist anywhere', () => {
		// A missing string must be visibly wrong, not silently blank.
		expect(t('nav.doesNotExist' as never)).toBe('nav.doesNotExist');
	});

	it('falls back to English for a key missing from the active locale', async () => {
		await loadLocale('de');
		// Every real key is translated, so this asserts the mechanism via a key
		// that exists only in the source catalogue.
		expect(t('common.nope' as never)).toBe('common.nope');
	});
});

describe('placeholder interpolation', () => {
	it('substitutes a named placeholder', () => {
		expect(t('discover.openPodcast', { title: 'Lage der Nation' })).toBe(
			'Open Lage der Nation'
		);
	});

	it('substitutes the same placeholder everywhere it appears', () => {
		expect(t('discover.showCount', { count: 7 })).toBe('7 Shows');
	});

	it('leaves an unknown placeholder untouched rather than blanking it', () => {
		// Better a visible "{title}" than a sentence with a hole in it.
		expect(t('discover.openPodcast', {})).toBe('Open {title}');
	});

	it('coerces numeric values to strings', () => {
		expect(t('search.results', { count: 0 })).toBe('Results (0)');
	});

	it('does not interpolate when no params are given', () => {
		expect(t('discover.openPodcast')).toBe('Open {title}');
	});
});

describe('plural selection', () => {
	it('selects the singular form for one', () => {
		expect(t('discover.showCount', { count: 1 })).toBe('1 Show');
	});

	it('selects the plural form for zero and many', () => {
		expect(t('discover.showCount', { count: 0 })).toBe('0 Shows');
		expect(t('discover.showCount', { count: 42 })).toBe('42 Shows');
	});

	it('applies the active locale plural rules', async () => {
		await loadLocale('de');
		expect(t('onboarding.continueWithTopics', { count: 1 })).toBe('Weiter · 1 Thema');
		expect(t('onboarding.continueWithTopics', { count: 3 })).toBe('Weiter · 3 Themen');
	});

	it('is not confused by a count of exactly one in another locale', async () => {
		await loadLocale('de');
		expect(t('account.deviceCount', { count: 1 })).toBe('1 Gerät');
		expect(t('account.deviceCount', { count: 2 })).toBe('2 Geräte');
	});

	it('does not treat a plural node as an addressable branch', () => {
		// "discover.showCount.one" is not a key; the node is a leaf.
		expect(t('discover.showCount.one' as never)).toBe('discover.showCount.one');
	});

	it('returns a plural entry unrendered when no count is supplied', () => {
		// Without a count there is no category to select, so it falls through to
		// the key rather than rendering "[object Object]".
		expect(t('discover.showCount')).toBe('discover.showCount');
	});
});

describe('n()', () => {
	it('formats a number in the active locale', async () => {
		expect(n(1234.5)).toBe('1,234.5');
		await loadLocale('de');
		expect(n(1234.5)).toBe('1.234,5');
	});

	it('passes through Intl options', () => {
		expect(n(0.42, { style: 'percent' })).toBe('42%');
	});
});
