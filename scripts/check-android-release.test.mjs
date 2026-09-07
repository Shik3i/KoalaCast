import test from 'node:test';
import assert from 'node:assert/strict';
import { parseVersion, validateRelease } from './check-android-release.mjs';

const previous = [{ tag: 'android-v0.11.5', code: 45, name: '0.11.5' }];
test('accepts historical alpha tags and a stable successor', () => {
	const alpha = parseVersion('versionCode = 1\nversionName = "0.1.0-alpha01"');
	assert.doesNotThrow(() => validateRelease({ code: 2, name: '0.1.0' }, [{ ...alpha, tag: 'android-v0.1.0-alpha01' }], ''));
});
test('accepts a new version and matching Android tag', () => {
	assert.doesNotThrow(() => validateRelease({ code: 46, name: '0.11.6' }, previous, 'android-v0.11.6'));
});
test('rejects reused version code, even when the version name increases', () => {
	assert.throws(() => validateRelease({ code: 45, name: '0.11.6' }, previous, ''), /must exceed/);
});
test('rejects a mismatched or non-Android tag', () => {
	for (const tag of ['android-v0.11.7', 'v0.11.6']) {
		assert.throws(() => validateRelease({ code: 46, name: '0.11.6' }, previous, tag), /does not match/);
	}
});
test('rejects reused or regressing version names with a higher code', () => {
	for (const name of ['0.11.5', '0.10.99']) {
		assert.throws(() => validateRelease({ code: 46, name }, previous, ''), /must exceed/);
	}
});
test('compares version components numerically', () => {
	assert.doesNotThrow(() => validateRelease({ code: 50, name: '0.12.0' }, previous, ''));
});
test('rejects missing, nonpositive, and Play-incompatible version codes', () => {
	for (const source of ['', 'versionCode = 0\nversionName = "0.11.6"', 'versionCode = 2100000001\nversionName = "0.11.6"']) {
		assert.throws(() => parseVersion(source), /missing or invalid/);
	}
});
test('reads the Gradle values without accepting debug suffixes as the release name', () => {
	assert.deepEqual(parseVersion(' versionCode = 46\n versionName = "0.11.6"\n versionNameSuffix = "-debug"'), { code: 46, name: '0.11.6' });
});
