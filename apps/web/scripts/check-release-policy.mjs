import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../../..');
const workflowDir = resolve(root, '.github/workflows');
const androidRelease = 'android-release.yml';
const releaseCreation = /\bgh\s+release\s+create\b|action-gh-release|releases\/create|createRelease/i;
const androidBuildReference = /apps\/android|\bgradlew\b/i;
const errors = [];

for (const name of readdirSync(workflowDir).filter((entry) => /\.ya?ml$/.test(entry))) {
	const source = readFileSync(resolve(workflowDir, name), 'utf8');
	for (const match of source.matchAll(/uses:\s+([^\s@]+)@([^\s#]+)/g)) {
		if (!match[1].startsWith('./') && !/^[0-9a-f]{40}$/.test(match[2])) {
			errors.push(`${name}: ${match[1]} must be pinned to a full commit SHA`);
		}
	}
	if (name !== androidRelease && releaseCreation.test(source)) {
		errors.push(`${name}: GitHub Releases may only be created by ${androidRelease}`);
	}
	if (name !== androidRelease && androidBuildReference.test(source)) {
		errors.push(`${name}: Android tests and builds may only run from ${androidRelease} after an android-v* tag`);
	}
}

const dockerfile = readFileSync(resolve(root, 'Dockerfile'), 'utf8');
for (const match of dockerfile.matchAll(/^FROM(?:\s+--platform=\S+)?\s+(\S+)/gm)) {
	if (!/@sha256:[0-9a-f]{64}$/.test(match[1])) {
		errors.push(`Dockerfile: base image ${match[1]} must be pinned to a sha256 digest`);
	}
}

const composeSource = readFileSync(resolve(root, 'docker-compose.yml'), 'utf8');
if (!/SECURE_COOKIES=\$\{SECURE_COOKIES:-true\}/.test(composeSource)) {
	errors.push('docker-compose.yml: SECURE_COOKIES must default to true');
}

const androidBuild = readFileSync(resolve(root, 'apps/android/app/build.gradle.kts'), 'utf8');
if (/signingConfig\s*=\s*signingConfigs\.getByName\(["']debug["']\)/.test(androidBuild)) {
	errors.push('apps/android/app/build.gradle.kts: release builds must never use the debug signing key');
}
if (!androidBuild.includes('verifyReleaseSigning') || !androidBuild.includes('explicitReleasePackagingRequested')) {
	errors.push('apps/android/app/build.gradle.kts: release signing must fail before packaging work starts');
}

const dockerSource = readFileSync(resolve(workflowDir, 'docker-release.yml'), 'utf8');
if (!/permissions:\s*\n\s+contents:\s+read/m.test(dockerSource)) {
	errors.push('docker-release.yml: top-level contents permission must remain read-only');
}
if (!/packages:\s+write/.test(dockerSource)) {
	errors.push('docker-release.yml: Docker publication must keep packages: write');
}

const androidSource = readFileSync(resolve(workflowDir, androidRelease), 'utf8');
if (!/tags:\s*\n\s+-\s+['"]android-v\*['"]/m.test(androidSource)) {
	errors.push(`${androidRelease}: release trigger must remain restricted to android-v* tags`);
}
if (!releaseCreation.test(androidSource)) {
	errors.push(`${androidRelease}: Android GitHub Release creation is missing`);
}
if (!/dist\/\*\.apk|dist\/\*\.aab/.test(androidSource)) {
	errors.push(`${androidRelease}: Android Release must include an APK or AAB asset`);
}
if (/\bzip_name\b|\bzip\s+-r\b|outputs\.zip/.test(androidSource)) {
	errors.push(`${androidRelease}: do not wrap APK/AAB assets in a redundant Android ZIP`);
}

if (errors.length > 0) {
	console.error(errors.map((error) => `- ${error}`).join('\n'));
	process.exit(1);
}

console.log('Release policy check passed: Android tests/builds require android-v*; website tags publish only Docker images.');
