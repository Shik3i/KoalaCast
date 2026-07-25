#!/usr/bin/env node
// Validates every translation catalogue against the English source of truth.
//
// Runs in CI so a pull request that adds a key without translating it, misspells
// a placeholder, or leaves a plural form out fails visibly instead of silently
// falling back to English in production.
//
//   node scripts/check-translations.mjs          # report problems, exit 1 on error
//   node scripts/check-translations.mjs --strict # also fail on missing keys
//
// Missing keys are warnings by default: a partially translated language is
// useful (it falls back to English per key), and demanding 100% coverage would
// block contributors from landing a first pass at a new language.

import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const MESSAGES_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'lib', 'i18n', 'messages');
const SOURCE = 'en';
const strict = process.argv.includes('--strict');

/** CLDR plural categories. A node whose keys are all of these is a leaf. */
const PLURAL_CATEGORIES = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);

function isPluralNode(value) {
	if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
	const keys = Object.keys(value);
	return keys.length > 0 && keys.every((k) => PLURAL_CATEGORIES.has(k));
}

/** Flattens a catalogue to `{ "a.b": value }`, treating plural nodes as leaves. */
function flatten(obj, prefix = '', out = {}) {
	for (const [key, value] of Object.entries(obj)) {
		const path = prefix ? `${prefix}.${key}` : key;
		if (isPluralNode(value)) {
			out[path] = value;
		} else if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
			flatten(value, path, out);
		} else {
			out[path] = value;
		}
	}
	return out;
}

/** All {placeholders} used in a string or in every form of a plural node. */
function placeholders(value) {
	const texts = typeof value === 'string' ? [value] : Object.values(value);
	const found = new Set();
	for (const text of texts) {
		for (const match of String(text).matchAll(/\{(\w+)\}/g)) found.add(match[1]);
	}
	return found;
}

function loadCatalogue(locale) {
	return JSON.parse(readFileSync(join(MESSAGES_DIR, `${locale}.json`), 'utf8'));
}

const locales = readdirSync(MESSAGES_DIR)
	.filter((f) => f.endsWith('.json'))
	.map((f) => f.replace(/\.json$/, ''))
	.filter((l) => l !== SOURCE)
	.sort();

const source = flatten(loadCatalogue(SOURCE));
const sourceKeys = Object.keys(source);

let errors = 0;
let warnings = 0;

console.log(`Checking ${locales.length} translation(s) against ${sourceKeys.length} English keys\n`);

for (const locale of locales) {
	const target = flatten(loadCatalogue(locale));
	const problems = [];

	const missing = sourceKeys.filter((k) => !(k in target));
	const extra = Object.keys(target).filter((k) => !(k in source));

	for (const key of extra) {
		problems.push({ level: 'error', text: `unknown key not present in ${SOURCE}.json: ${key}` });
	}

	for (const key of sourceKeys) {
		if (!(key in target)) continue;

		const expectedPlural = isPluralNode(source[key]);
		const actualPlural = isPluralNode(target[key]);
		if (expectedPlural && !actualPlural) {
			problems.push({ level: 'error', text: `${key} must provide plural forms` });
			continue;
		}
		if (!expectedPlural && actualPlural) {
			problems.push({ level: 'error', text: `${key} must be a plain string` });
			continue;
		}
		// A locale may legitimately need more plural categories than English
		// (Polish, Arabic), so only "other" is required — Intl.PluralRules picks
		// the right one and the runtime falls back to "other".
		if (expectedPlural && !('other' in target[key])) {
			problems.push({ level: 'error', text: `${key} is missing the "other" plural form` });
			continue;
		}

		const want = placeholders(source[key]);
		const got = placeholders(target[key]);
		for (const p of want) {
			if (!got.has(p)) problems.push({ level: 'error', text: `${key} is missing placeholder {${p}}` });
		}
		for (const p of got) {
			if (!want.has(p)) problems.push({ level: 'error', text: `${key} has unknown placeholder {${p}}` });
		}
	}

	const level = strict ? 'error' : 'warning';
	for (const key of missing) {
		problems.push({ level, text: `untranslated (falls back to English): ${key}` });
	}

	const errs = problems.filter((p) => p.level === 'error');
	const warns = problems.filter((p) => p.level === 'warning');
	errors += errs.length;
	warnings += warns.length;

	const coverage = Math.round(((sourceKeys.length - missing.length) / sourceKeys.length) * 100);
	const status = errs.length ? 'FAIL' : 'ok';
	console.log(`${locale}.json — ${coverage}% translated — ${status}`);
	for (const p of problems) console.log(`  ${p.level === 'error' ? 'error' : 'warn '}  ${p.text}`);
	if (problems.length) console.log('');
}

console.log(`\n${errors} error(s), ${warnings} warning(s)`);
process.exit(errors > 0 ? 1 : 0);
