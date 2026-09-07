import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = resolve(import.meta.dirname, '..');
const buildPath = 'apps/android/app/build.gradle.kts';

export function parseVersion(source) {
	const code = Number(source.match(/^\s*versionCode\s*=\s*(\d+)\s*$/m)?.[1]);
	const name = source.match(/^\s*versionName\s*=\s*"(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)"\s*$/m)?.[1];
	if (!Number.isSafeInteger(code) || code < 1 || code > 2100000000 || !name) {
		throw new Error('Android versionCode/versionName is missing or invalid');
	}
	return { code, name };
}

export function validateRelease(current, previous, tag) {
	if (!/^\d+\.\d+\.\d+$/.test(current.name)) {
		throw new Error('The Play release candidate must have a stable versionName');
	}
	if (tag && tag !== `android-v${current.name}`) {
		throw new Error(`Tag ${tag} does not match versionName ${current.name}`);
	}
	for (const release of previous) {
		if (current.code <= release.code) {
			throw new Error(`versionCode ${current.code} must exceed ${release.tag} (${release.code})`);
		}
		const a = current.name.split('.').map(Number);
		const b = release.name.split('-')[0].split('.').map(Number);
		const firstDifference = a.findIndex((value, index) => value !== b[index]);
		if ((firstDifference < 0 && !release.name.includes('-')) || (firstDifference >= 0 && a[firstDifference] < b[firstDifference])) {
			throw new Error(`versionName ${current.name} must exceed ${release.tag} (${release.name})`);
		}
	}
}

export function checkRelease(env = process.env) {
	const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
	if (git('rev-parse', '--is-shallow-repository') !== 'false') {
		throw new Error('Release version validation requires full Git history and tags (fetch-depth: 0)');
	}
	const current = parseVersion(readFileSync(resolve(root, buildPath), 'utf8'));
	const tag = env.GITHUB_REF_TYPE === 'tag' ? env.GITHUB_REF_NAME : '';
	const tags = git('tag', '--list', 'android-v*').split('\n').filter(Boolean);
	const previous = tags.filter((ref) => ref !== tag).map((ref) => ({
		tag: ref,
		...parseVersion(git('show', `${ref}:${buildPath}`))
	}));
	validateRelease(current, previous, tag);
	console.log(`Android release version valid: ${current.name} (${current.code}); checked ${previous.length} prior tags`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
	try { checkRelease(); } catch (error) {
		console.error(error.message);
		process.exitCode = 1;
	}
}
