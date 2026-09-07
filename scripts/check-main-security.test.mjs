import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { validateMainSecurity } from './check-main-security.mjs';

const sha = 'a'.repeat(40);
const clean = () => ['go', 'javascript-typescript', 'java-kotlin', 'actions'].map((language, id) => ({id, ref:'refs/heads/main', commit_sha:sha, category:`/language:${language}`, error:'', warning:''}));
test('accepts four full main analyses on the exact release commit with no open alerts', () => assert.doesNotThrow(() => validateMainSecurity(sha, clean(), [])));
test('rejects PR-only analysis even when its code tree is identical', () => assert.throws(() => validateMainSecurity(sha, clean().map((entry) => ({...entry, ref:'refs/pull/55/merge'})), [])));
test('rejects stale, missing, failed and warning-bearing analyses', () => {
	assert.throws(() => validateMainSecurity('b'.repeat(40), clean(), []));
	assert.throws(() => validateMainSecurity(sha, clean().slice(1), []));
	for (const field of ['error', 'warning']) assert.throws(() => validateMainSecurity(sha, clean().map((entry) => ({...entry, [field]:'incomplete extraction'})), []));
});
test('a newer failed scan cannot be hidden by an older successful scan', () => assert.throws(() => validateMainSecurity(sha, [...clean(), {...clean()[0], id:10, error:'failed'}], [])));
test('blocks every unresolved alert, including baseline findings', () => assert.throws(() => validateMainSecurity(sha, clean(), [{number:1}])));
test('rejects invalid release SHA', () => assert.throws(() => validateMainSecurity('main', clean(), [])));
test('cannot release an old commit after later main fixes close its alerts', () => assert.throws(() => validateMainSecurity(sha, clean(), [], 'b'.repeat(40))));

test('Android backups include only the non-credential preferences file', () => {
	const read = (name) => readFileSync(new URL(`../apps/android/app/src/main/${name}`, import.meta.url), 'utf8');
	const expected = '<include domain="file" path="datastore/koalacast_preferences.preferences_pb" />';
	assert.deepEqual(read('res/xml/backup_rules.xml').match(/<include\b[^>]*\/>/g), [expected]);
	assert.deepEqual(read('res/xml/data_extraction_rules.xml').match(/<include\b[^>]*\/>/g), [expected, expected]);
	const manifest = read('AndroidManifest.xml');
	assert.match(manifest, /android:fullBackupContent="@xml\/backup_rules"/);
	assert.match(manifest, /android:dataExtractionRules="@xml\/data_extraction_rules"/);
	assert.doesNotMatch(manifest, /android:backupAgent=/);
});

test('both publishing jobs enforce the full main gate before credentials or publication', () => {
	for (const [name, sensitiveStep] of [['android-release.yml', 'Configure release signing'], ['docker-release.yml', 'Log in to GHCR']]) {
		const workflow = readFileSync(new URL(`../.github/workflows/${name}`, import.meta.url), 'utf8');
		const gate = workflow.indexOf('      - name: Full default-branch security gate\n');
		const sensitive = workflow.indexOf(`      - name: ${sensitiveStep}\n`);
		assert.ok(gate >= 0 && sensitive > gate, `${name}: gate must precede credential use`);
		const step = workflow.slice(gate).split(/\n      - /)[0];
		assert.match(step, /GH_TOKEN: \$\{\{ github.token \}\}/);
		assert.match(step, /run: node scripts\/check-main-security\.mjs/);
		assert.doesNotMatch(step, /continue-on-error:|\bif:|\|\|\s*true/);
		assert.match(workflow.slice(0, gate), /security-events: read/);
	}
});
