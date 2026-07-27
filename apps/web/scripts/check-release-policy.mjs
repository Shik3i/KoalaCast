import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../../..');
const workflowDir = resolve(root, '.github/workflows');
const androidRelease = 'android-release.yml';
const releaseCreation = /\bgh\s+release\s+create\b|action-gh-release|releases\/create|createRelease/i;
const errors = [];

for (const name of readdirSync(workflowDir).filter((entry) => /\.ya?ml$/.test(entry))) {
	const source = readFileSync(resolve(workflowDir, name), 'utf8');
	if (name !== androidRelease && releaseCreation.test(source)) {
		errors.push(`${name}: GitHub Releases may only be created by ${androidRelease}`);
	}
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

if (errors.length > 0) {
	console.error(errors.map((error) => `- ${error}`).join('\n'));
	process.exit(1);
}

console.log('Release policy check passed: website tags publish only Docker images; GitHub Releases are Android-only.');
