import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = resolve(webRoot, '../..');
const output = resolve(webRoot, 'static/sitemap.xml');
const origin = (process.env.PUBLIC_BASE_URL || 'https://cast.koalastuff.net').replace(/\/+$/, '');

const routes = [
	{
		path: '/',
		files: [
			'apps/web/src/routes/+page.svelte',
			'apps/web/src/lib/data/featured.ts',
			'apps/web/src/lib/components/Seo.svelte'
		],
		changefreq: 'daily',
		priority: '1.0'
	},
	{
		path: '/global-stats',
		files: [
			'apps/web/src/routes/global-stats/+page.svelte',
			'apps/web/src/lib/components/Seo.svelte',
			'services/api/internal/server/handlers/global_stats.go'
		],
		changefreq: 'daily',
		priority: '0.8'
	},
	{
		path: '/privacy',
		files: [
			'apps/web/src/routes/privacy/+page.svelte',
			'apps/web/src/lib/data/privacy.ts',
			'docs/privacy/privacy-policy.md'
		],
		changefreq: 'monthly',
		priority: '0.4'
	}
];

function gitDate(files) {
	try {
		const dirty = execFileSync('git', ['status', '--porcelain', '--', ...files], {
			cwd: repoRoot,
			encoding: 'utf8',
			stdio: ['ignore', 'pipe', 'ignore']
		}).trim();
		if (dirty) return new Date().toISOString().slice(0, 10);
		const date = execFileSync('git', ['log', '-1', '--format=%cs', '--', ...files], {
			cwd: repoRoot,
			encoding: 'utf8',
			stdio: ['ignore', 'pipe', 'ignore']
		}).trim();
		if (/^\d{4}-\d{2}-\d{2}$/.test(date)) return date;
	} catch {
		// The release workflow puts a Git-derived sitemap into the Docker context.
	}
	return '';
}

const entries = routes.map((route) => ({ ...route, lastmod: gitDate(route.files) }));

if (entries.some((entry) => !entry.lastmod)) {
	if (existsSync(output)) {
		process.stdout.write('SEO: preserving pre-generated sitemap (Git metadata unavailable)\n');
		process.exit(0);
	}
	const fallback = process.env.SEO_SOURCE_DATE?.slice(0, 10);
	if (!fallback || !/^\d{4}-\d{2}-\d{2}$/.test(fallback)) {
		throw new Error('Cannot generate real sitemap lastmod values: Git metadata and SEO_SOURCE_DATE are unavailable');
	}
	for (const entry of entries) entry.lastmod = fallback;
}

const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${entries.map((entry) => `  <url>
    <loc>${origin}${entry.path}</loc>
    <lastmod>${entry.lastmod}</lastmod>
    <changefreq>${entry.changefreq}</changefreq>
    <priority>${entry.priority}</priority>
  </url>`).join('\n')}
</urlset>
`;

mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, xml);
process.stdout.write(`SEO: generated sitemap with ${entries.length} Git-derived lastmod values\n`);
