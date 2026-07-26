import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
execFileSync(process.execPath, [resolve(import.meta.dirname, 'generate-seo.mjs')], {
	cwd: root,
	stdio: 'inherit',
	env: process.env
});

const sitemap = readFileSync(resolve(root, 'static/sitemap.xml'), 'utf8');
const robots = readFileSync(resolve(root, 'static/robots.txt'), 'utf8');
const llms = readFileSync(resolve(root, 'static/llms.txt'), 'utf8');
const app = readFileSync(resolve(root, 'src/app.html'), 'utf8');

const failures = [];
for (const path of ['/', '/global-stats', '/privacy']) {
	const escaped = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
	if (!new RegExp(`<loc>https://cast\\.koalastuff\\.net${escaped}</loc>`).test(sitemap)) {
		failures.push(`sitemap is missing ${path}`);
	}
}
if ((sitemap.match(/<lastmod>\d{4}-\d{2}-\d{2}<\/lastmod>/g) ?? []).length !== 3) {
	failures.push('sitemap must contain one valid lastmod date per public static route');
}
if (!robots.includes('Sitemap: https://cast.koalastuff.net/sitemap.xml')) {
	failures.push('robots.txt must advertise the canonical sitemap');
}
for (const path of ['/admin', '/account', '/settings', '/profile', '/library', '/inbox', '/search']) {
	if (!robots.includes(`Disallow: ${path}`)) failures.push(`robots.txt must disallow ${path}`);
}
if (!llms.startsWith('# KoalaCast') || !llms.includes('https://github.com/Shik3i/KoalaCast')) {
	failures.push('llms.txt must identify KoalaCast and its source repository');
}
if (!app.includes('application/ld+json') || !app.includes('WebApplication')) {
	failures.push('app shell must contain WebApplication structured data');
}

if (failures.length) {
	for (const failure of failures) process.stderr.write(`SEO error: ${failure}\n`);
	process.exit(1);
}
process.stdout.write('SEO: sitemap, robots, llms.txt and structured data checks passed\n');
