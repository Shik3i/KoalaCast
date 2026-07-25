#!/usr/bin/env node
// Reports user-facing strings that are still hardcoded in components.
//
// A heuristic auditing aid, not a gate: it flags literal text in markup, in
// aria-label/title/placeholder attributes, and in toast calls. Run it after
// touching the UI to see what a translator would not be able to reach.
//
//   node scripts/find-untranslated.mjs
//   node scripts/find-untranslated.mjs --verbose   # show every hit, not a summary

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const verbose = process.argv.includes('--verbose');

function walk(dir, out = []) {
	for (const entry of readdirSync(dir)) {
		const full = join(dir, entry);
		if (statSync(full).isDirectory()) walk(full, out);
		else if (entry.endsWith('.svelte')) out.push(full);
	}
	return out;
}

/** Strips the <script> and <style> blocks so only markup remains. */
function markupOnly(source) {
	return source
		.replace(/<script[\s\S]*?<\/script>/g, '')
		.replace(/<style[\s\S]*?<\/style>/g, '');
}

/** Text that is not really a translatable phrase. */
function isNoise(text) {
	const t = text.trim();
	if (t.length < 3) return true;
	if (!/[a-z]/i.test(t)) return true; // punctuation, numbers, symbols
	if (!/\s/.test(t) && t.length < 5) return true; // single short token
	if (/^(https?:|\/|#|\d)/.test(t)) return true;
	if (/^[A-Z][a-z]+\.(svg|png|json|xml)$/.test(t)) return true;
	return false;
}

const findings = [];

for (const file of walk(ROOT)) {
	const source = readFileSync(file, 'utf8');
	const rel = relative(ROOT, file).replace(/\\/g, '/');
	const markup = markupOnly(source);

	// Literal text between tags, ignoring anything containing a {…} expression.
	for (const match of markup.matchAll(/>([^<>{}]+)</g)) {
		const text = match[1].replace(/\s+/g, ' ').trim();
		if (isNoise(text)) continue;
		findings.push({ file: rel, kind: 'markup', text });
	}

	// Human-readable attributes with a literal value.
	for (const match of markup.matchAll(/\b(aria-label|title|placeholder|alt)="([^"{}]+)"/g)) {
		const text = match[2].trim();
		if (isNoise(text)) continue;
		findings.push({ file: rel, kind: match[1], text });
	}

	// Toast / alert / confirm calls with a literal argument.
	for (const match of source.matchAll(/\b(?:toast\.\w+|alert|confirm)\(\s*(['"`])([^'"`]{4,})\1/g)) {
		findings.push({ file: rel, kind: 'message', text: match[2].trim() });
	}
}

if (findings.length === 0) {
	console.log('No hardcoded user-facing strings found.');
	process.exit(0);
}

const byFile = new Map();
for (const f of findings) {
	if (!byFile.has(f.file)) byFile.set(f.file, []);
	byFile.get(f.file).push(f);
}

console.log(`${findings.length} possible hardcoded string(s) in ${byFile.size} file(s):\n`);
for (const [file, items] of [...byFile].sort((a, b) => b[1].length - a[1].length)) {
	console.log(`  ${file} — ${items.length}`);
	if (verbose) for (const i of items) console.log(`      [${i.kind}] ${i.text}`);
}
console.log('\nHeuristic only — brand names and untranslatable labels will show up here.');
