import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const markdownFiles = execFileSync(
	'git',
	['ls-files', '*.md', '*.MD'],
	{ cwd: root, encoding: 'utf8' }
)
	.trim()
	.split('\n')
	.filter(Boolean);

const failures = [];
const linkPattern = /!?\[[^\]]*]\(([^)\s]+)(?:\s+["'][^"']*["'])?\)/g;

for (const markdownFile of markdownFiles) {
	const absoluteFile = resolve(root, markdownFile);
	const source = readFileSync(absoluteFile, 'utf8');

	for (const match of source.matchAll(linkPattern)) {
		const rawTarget = match[1].replace(/^<|>$/g, '');
		if (
			rawTarget.startsWith('#') ||
			rawTarget.startsWith('http://') ||
			rawTarget.startsWith('https://') ||
			rawTarget.startsWith('mailto:')
		) {
			continue;
		}

		const targetWithoutFragment = rawTarget.split('#', 1)[0].split('?', 1)[0];
		if (!targetWithoutFragment) continue;

		let decodedTarget;
		try {
			decodedTarget = decodeURIComponent(targetWithoutFragment);
		} catch {
			failures.push(`${markdownFile}: invalid URL encoding in ${rawTarget}`);
			continue;
		}

		const target = resolve(dirname(absoluteFile), decodedTarget);
		if (!existsSync(target)) {
			failures.push(`${markdownFile}: missing local link target ${rawTarget}`);
			continue;
		}
		if (statSync(target).isDirectory() && !existsSync(resolve(target, 'README.md'))) {
			failures.push(`${markdownFile}: linked directory has no README.md: ${rawTarget}`);
		}
	}
}

const requiredStatements = [
	['README.md', 'optional account-backed cross-device sync'],
	['apps/README.md', 'adapter-static'],
	['docs/current-status.md', 'July 27, 2026'],
	['docs/privacy/privacy-policy.md', 'not encrypted at the application layer']
];
for (const [file, statement] of requiredStatements) {
	if (!readFileSync(resolve(root, file), 'utf8').includes(statement)) {
		failures.push(`${file}: required current-state statement is missing: ${statement}`);
	}
}

if (failures.length > 0) {
	for (const failure of failures) process.stderr.write(`Docs error: ${failure}\n`);
	process.exit(1);
}

process.stdout.write(
	`Docs: checked ${markdownFiles.length} tracked Markdown files and current-state assertions\n`
);
