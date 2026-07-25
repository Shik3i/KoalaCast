// Catalogue integrity tests.
//
// These run over *every* registered locale, so a language added later is
// automatically held to the same standard without touching this file. The point
// is to make a broken or half-pasted translation fail in CI rather than ship as
// a blank label, a stray "TODO", or a placeholder that silently renders as
// literal "{count}".

import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LOCALES, DEFAULT_LOCALE } from './registry';

const MESSAGES_DIR = join(dirname(fileURLToPath(import.meta.url)), 'messages');

const PLURAL_CATEGORIES = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);

/** Markers that mean "not actually translated yet". */
const PLACEHOLDER_MARKERS = [
	'TODO',
	'FIXME',
	'XXX',
	'TBD',
	'???',
	'PLACEHOLDER',
	'LOREM IPSUM',
	'UNTRANSLATED'
];

type Node = string | Record<string, unknown>;

function isPluralNode(value: unknown): value is Record<string, string> {
	if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
	const keys = Object.keys(value);
	return keys.length > 0 && keys.every((k) => PLURAL_CATEGORIES.has(k));
}

/** Flattens to `{ "a.b": value }`, treating plural nodes as leaves. */
function flatten(obj: Record<string, unknown>, prefix = '', out: Record<string, Node> = {}) {
	for (const [key, value] of Object.entries(obj)) {
		const path = prefix ? `${prefix}.${key}` : key;
		if (isPluralNode(value)) out[path] = value;
		else if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
			flatten(value as Record<string, unknown>, path, out);
		} else out[path] = value as Node;
	}
	return out;
}

/** Every string carried by a leaf (one string, or every plural form). */
function stringsOf(value: Node): string[] {
	return typeof value === 'string' ? [value] : Object.values(value as Record<string, string>);
}

function placeholdersOf(value: Node): Set<string> {
	const found = new Set<string>();
	for (const text of stringsOf(value)) {
		for (const m of text.matchAll(/\{(\w+)\}/g)) found.add(m[1]);
	}
	return found;
}

function readRaw(locale: string): string {
	return readFileSync(join(MESSAGES_DIR, `${locale}.json`), 'utf8');
}

function readCatalogue(locale: string): Record<string, unknown> {
	return JSON.parse(readRaw(locale));
}

const localeCodes = LOCALES.map((l) => l.code);
const source = flatten(readCatalogue(DEFAULT_LOCALE));
const sourceKeys = Object.keys(source);

describe('registry', () => {
	it('registers the default locale', () => {
		expect(localeCodes).toContain(DEFAULT_LOCALE);
	});

	it('has a catalogue file for every registered locale', () => {
		for (const code of localeCodes) {
			expect(() => readCatalogue(code), `${code}.json should exist and be valid JSON`).not.toThrow();
		}
	});

	it('registers every catalogue file that exists', () => {
		const files = readdirSync(MESSAGES_DIR)
			.filter((f) => f.endsWith('.json'))
			.map((f) => f.replace(/\.json$/, ''));
		// A stray file would be dead weight in the repo and invisible in Settings.
		expect(files.sort()).toEqual([...localeCodes].sort());
	});

	it('has no duplicate locale codes', () => {
		expect(new Set(localeCodes).size).toBe(localeCodes.length);
	});

	it('lazy-loads every locale except the bundled fallback', () => {
		for (const locale of LOCALES) {
			if (locale.code === DEFAULT_LOCALE) {
				expect(locale.load, 'the fallback locale is bundled, not loaded').toBeUndefined();
			} else {
				expect(locale.load, `${locale.code} must declare a loader`).toBeTypeOf('function');
			}
		}
	});

	it('names every locale in its own language', () => {
		for (const locale of LOCALES) {
			expect(locale.name.trim(), `${locale.code} needs a name`).not.toBe('');
			expect(locale.flag.trim(), `${locale.code} needs a flag`).not.toBe('');
		}
	});
});

describe('source catalogue', () => {
	it('is not empty', () => {
		expect(sourceKeys.length).toBeGreaterThan(0);
	});

	it('declares no duplicate keys', () => {
		// JSON.parse silently keeps the last of a duplicated key, so this has to be
		// checked against the raw text or a copy/paste error goes unnoticed.
		const raw = readRaw(DEFAULT_LOCALE);
		const seen = new Set<string>();
		const duplicates: string[] = [];
		let depthPath: string[] = [];
		for (const line of raw.split('\n')) {
			const m = line.match(/^\s*"([^"]+)"\s*:/);
			const indent = line.match(/^\t*/)?.[0].length ?? 0;
			if (!m) continue;
			depthPath = depthPath.slice(0, indent - 1);
			depthPath[indent - 1] = m[1];
			const path = depthPath.slice(0, indent).join('.');
			if (seen.has(path)) duplicates.push(path);
			seen.add(path);
		}
		expect(duplicates).toEqual([]);
	});
});

// Every locale is held to the same rules. `describe.each` names the failing
// language in the test output, so a contributor sees exactly which file is wrong.
describe.each(localeCodes)('catalogue: %s', (locale) => {
	const catalogue = flatten(readCatalogue(locale));

	it('has every key from the source catalogue', () => {
		const missing = sourceKeys.filter((k) => !(k in catalogue));
		expect(missing, `untranslated keys in ${locale}.json`).toEqual([]);
	});

	it('has no keys the source catalogue does not define', () => {
		const extra = Object.keys(catalogue).filter((k) => !(k in source));
		expect(extra, `unknown keys in ${locale}.json (typo, or removed from en.json?)`).toEqual([]);
	});

	it('has no empty or whitespace-only values', () => {
		const empty = Object.entries(catalogue)
			.filter(([, v]) => stringsOf(v).some((s) => typeof s !== 'string' || s.trim() === ''))
			.map(([k]) => k);
		expect(empty).toEqual([]);
	});

	it('has no untranslated placeholder markers', () => {
		const flagged: string[] = [];
		for (const [key, value] of Object.entries(catalogue)) {
			for (const text of stringsOf(value)) {
				const upper = String(text).toUpperCase();
				if (PLACEHOLDER_MARKERS.some((marker) => upper.includes(marker))) flagged.push(key);
			}
		}
		expect(flagged, `placeholder text left in ${locale}.json`).toEqual([]);
	});

	it('has no values that are only the key echoed back', () => {
		// A value equal to its own key means someone pasted the key column.
		const echoed = Object.entries(catalogue)
			.filter(([key, value]) => stringsOf(value).some((s) => s === key))
			.map(([k]) => k);
		expect(echoed).toEqual([]);
	});

	it('has no leading or trailing whitespace', () => {
		const padded = Object.entries(catalogue)
			.filter(([, v]) => stringsOf(v).some((s) => s !== s.trim()))
			.map(([k]) => k);
		expect(padded).toEqual([]);
	});

	it('uses exactly the placeholders the source defines', () => {
		const problems: string[] = [];
		for (const key of sourceKeys) {
			if (!(key in catalogue)) continue;
			const want = placeholdersOf(source[key]);
			const got = placeholdersOf(catalogue[key]);
			for (const p of want) if (!got.has(p)) problems.push(`${key}: missing {${p}}`);
			for (const p of got) if (!want.has(p)) problems.push(`${key}: unknown {${p}}`);
		}
		expect(problems).toEqual([]);
	});

	it('keeps plural entries plural and singular entries singular', () => {
		const problems: string[] = [];
		for (const key of sourceKeys) {
			if (!(key in catalogue)) continue;
			const wantPlural = isPluralNode(source[key]);
			const gotPlural = isPluralNode(catalogue[key]);
			if (wantPlural && !gotPlural) problems.push(`${key}: must provide plural forms`);
			if (!wantPlural && gotPlural) problems.push(`${key}: must be a plain string`);
		}
		expect(problems).toEqual([]);
	});

	it('provides the "other" plural form on every plural entry', () => {
		// Intl.PluralRules can return any category; "other" is the guaranteed
		// fallback, so a catalogue without it can render nothing at all.
		const missing = Object.entries(catalogue)
			.filter(([, v]) => isPluralNode(v) && !('other' in v))
			.map(([k]) => k);
		expect(missing).toEqual([]);
	});

	it('uses only valid CLDR plural categories', () => {
		const invalid: string[] = [];
		for (const [key, value] of Object.entries(catalogue)) {
			if (!isPluralNode(value)) continue;
			for (const category of Object.keys(value)) {
				if (!PLURAL_CATEGORIES.has(category)) invalid.push(`${key}.${category}`);
			}
		}
		expect(invalid).toEqual([]);
	});

	it('declares plural forms this language actually uses', () => {
		// A German catalogue carrying a "few" form is dead weight: Intl.PluralRules
		// will never select it. Catches copy/paste from another language.
		const categories = new Set<string>();
		for (let i = 0; i <= 200; i++) categories.add(new Intl.PluralRules(locale).select(i));
		for (const n of [0.5, 1.5, 2.5]) categories.add(new Intl.PluralRules(locale).select(n));

		const unusable: string[] = [];
		for (const [key, value] of Object.entries(catalogue)) {
			if (!isPluralNode(value)) continue;
			for (const category of Object.keys(value)) {
				if (category !== 'other' && !categories.has(category)) unusable.push(`${key}.${category}`);
			}
		}
		expect(unusable, `plural forms ${locale} never selects`).toEqual([]);
	});
});

describe('translation coverage', () => {
	it.each(localeCodes.filter((c) => c !== DEFAULT_LOCALE))(
		'%s is fully translated',
		(locale) => {
			const catalogue = flatten(readCatalogue(locale));
			const translated = sourceKeys.filter((k) => k in catalogue).length;
			expect(translated / sourceKeys.length).toBe(1);
		}
	);
});
