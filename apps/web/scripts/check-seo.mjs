import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
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
const llmsFull = readFileSync(resolve(root, 'static/llms-full.txt'), 'utf8');
const app = readFileSync(resolve(root, 'src/app.html'), 'utf8');
const socialImage = resolve(root, 'static/og/koalacast-social.jpg');

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
if (!llms.includes('https://cast.koalastuff.net/llms-full.txt')) {
	failures.push('llms.txt must link to llms-full.txt');
}
if (!llmsFull.includes('## Direct answers') || !llmsFull.includes('## Feature inventory')) {
	failures.push('llms-full.txt must contain direct answers and a feature inventory');
}
if (!app.includes('application/ld+json') || !app.includes('SoftwareApplication') || !app.includes('WebSite')) {
	failures.push('app shell must contain WebSite and SoftwareApplication structured data');
}
for (const required of [
	'property="og:image"',
	'property="og:image:width" content="1200"',
	'property="og:image:height" content="630"',
	'name="twitter:card" content="summary_large_image"',
	'https://cast.koalastuff.net/og/koalacast-social.jpg'
]) {
	if (!app.includes(required)) failures.push(`app shell is missing social metadata: ${required}`);
}
try {
	const { size } = statSync(socialImage);
	if (size < 10_000 || size > 1_000_000) failures.push('social image must be between 10 KB and 1 MB');
} catch {
	failures.push('social image is missing');
}

if (failures.length) {
	for (const failure of failures) process.stderr.write(`SEO error: ${failure}\n`);
	process.exit(1);
}
process.stdout.write('SEO: sitemap, robots, llms.txt and structured data checks passed\n');
