import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const languages = ['go', 'javascript-typescript', 'java-kotlin', 'actions'];

export function validateMainSecurity(sha, analyses, alerts, mainSha = sha) {
	if (!/^[a-f0-9]{40}$/.test(sha)) throw new Error('Invalid release commit SHA');
	if (mainSha !== sha) throw new Error('Release commit is not the current main commit');
	for (const language of languages) {
		const analysis = analyses.filter((entry) => entry.ref === 'refs/heads/main' && entry.commit_sha === sha && entry.category === `/language:${language}`)
			.sort((a, b) => b.id - a.id)[0];
		if (!analysis || analysis.error || analysis.warning) throw new Error(`Missing or unsuccessful full main CodeQL analysis: ${language}`);
	}
	if (alerts.length) throw new Error(`${alerts.length} open CodeQL alert(s) on main; release blocked`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
	try {
		const repo = process.env.GITHUB_REPOSITORY;
		if (!/^[\w.-]+\/[\w.-]+$/.test(repo ?? '')) throw new Error('GITHUB_REPOSITORY is required');
		const sha = execFileSync('git', ['rev-parse', 'HEAD'], {encoding: 'utf8'}).trim();
		const mainSha = execFileSync('gh', ['api', `repos/${repo}/git/ref/heads/main`, '--jq', '.object.sha'], {encoding: 'utf8'}).trim();
		const fetchPages = (endpoint) => JSON.parse(execFileSync('gh', ['api', '--paginate', '--slurp', `repos/${repo}/${endpoint}`], {encoding: 'utf8', maxBuffer: 16 * 1024 * 1024})).flat();
		const analyses = fetchPages('code-scanning/analyses?ref=refs/heads/main&per_page=100');
		const alerts = fetchPages('code-scanning/alerts?state=open&ref=refs/heads/main&per_page=100');
		validateMainSecurity(sha, analyses, alerts, mainSha);
		console.log(`Full main CodeQL gate passed for ${sha}: four languages, no open alerts`);
	} catch (error) {
		console.error(error.message);
		process.exitCode = 1;
	}
}
