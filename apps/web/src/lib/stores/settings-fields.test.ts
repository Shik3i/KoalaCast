import { describe, expect, it } from 'vitest';
import { decideFields, parseFieldTimestamps } from './settings-fields';

const FIELDS = ['languages', 'palette', 'playback_speed'];

describe('parseFieldTimestamps', () => {
	it('keeps only positive finite numbers', () => {
		expect(parseFieldTimestamps({ a: 5, b: '7', c: 0, d: -1, e: 'x', f: null })).toEqual({
			a: 5,
			b: 7
		});
	});

	it('treats anything that is not a plain object as no information', () => {
		expect(parseFieldTimestamps(undefined)).toEqual({});
		expect(parseFieldTimestamps([1, 2])).toEqual({});
		expect(parseFieldTimestamps('nope')).toEqual({});
	});
});

describe('decideFields', () => {
	it('keeps both halves of two concurrent edits', () => {
		// The phone changed the language at 10:00, the browser the palette at 10:01.
		// The browser's blob is newer, and used to revert the language wholesale.
		const decision = decideFields(
			FIELDS,
			{ stamps: { palette: 1_001, languages: 900 }, updatedAt: 1_001 },
			{ stamps: { languages: 1_000, palette: 900 }, updatedAt: 1_000 }
		);
		expect(decision.accepted.has('palette')).toBe(true);
		expect(decision.accepted.has('languages')).toBe(false);
	});

	it('accepts an older blob that still carries a newer field', () => {
		const decision = decideFields(
			FIELDS,
			{ stamps: { languages: 2_000 }, updatedAt: 500 },
			{ stamps: {}, updatedAt: 1_000 }
		);
		expect(decision.accepted.has('languages')).toBe(true);
		expect(decision.stamps.languages).toBe(2_000);
	});

	it('reads a payload without stamps the old way', () => {
		const newer = decideFields(FIELDS, { stamps: {}, updatedAt: 2_000 }, { stamps: {}, updatedAt: 1_000 });
		expect([...newer.accepted].sort()).toEqual([...FIELDS].sort());

		const older = decideFields(FIELDS, { stamps: {}, updatedAt: 500 }, { stamps: {}, updatedAt: 1_000 });
		expect(older.accepted.size).toBe(0);
	});

	it('does not let an upgrading installation lose its untouched fields', () => {
		// No local per-field history yet: every field is as of the local blob, so an
		// older payload must not overwrite any of them.
		const decision = decideFields(
			FIELDS,
			{ stamps: { languages: 10 }, updatedAt: 10 },
			{ stamps: {}, updatedAt: 5_000 }
		);
		expect(decision.accepted.size).toBe(0);
	});

	it('takes everything when the caller is authoritative', () => {
		const decision = decideFields(
			FIELDS,
			{ stamps: {}, updatedAt: 1 },
			{ stamps: {}, updatedAt: 9_999 },
			{ authoritative: true }
		);
		expect(decision.accepted.size).toBe(FIELDS.length);
	});

	it('never accepts on an exact tie, so a re-pulled own write is a no-op', () => {
		const decision = decideFields(
			FIELDS,
			{ stamps: { palette: 1_000 }, updatedAt: 1_000 },
			{ stamps: { palette: 1_000 }, updatedAt: 1_000 }
		);
		expect(decision.accepted.size).toBe(0);
	});
});
