<script lang="ts">
	import { PRIVACY_SECTIONS } from '$lib/data/privacy';

	// NOTE: This page is intentionally NOT translated. The privacy policy is a
	// legal document — a mistranslated clause is a legal problem, not a cosmetic
	// one — so it is maintained in English only and reviewed as English.
	// See docs/i18n.md ("Legal content is not translated").
</script>

<div class="privacy-container">
	<header class="page-header">
		<div class="header-icon">
			<i class="ph ph-shield-check" aria-hidden="true"></i>
		</div>
		<div>
			<h1>Privacy Policy</h1>
			<p class="page-sub">How KoalaCast processes connection data, local browser storage, server proxying, accounts, and audio streams.</p>
			<p class="updated">Last updated: 1 August 2026</p>
		</div>
	</header>

	<div class="content-grid">
		{#each PRIVACY_SECTIONS as section (section.id)}
			<section class="card" class:highlight-card={section.highlight} id={section.id}>
				<h2>
					<i class="ph {section.icon}" aria-hidden="true"></i>
					{section.title}
				</h2>

				{#if section.paragraphs}
					{#each section.paragraphs as paragraph}
						<p>{paragraph}</p>
					{/each}
				{/if}

				{#if section.items}
					<ul>
						{#each section.items as item}
							<li>
								{#if item.label}
									<strong>{item.label}:</strong>{' '}
								{/if}
								{item.text}
							</li>
						{/each}
					</ul>
				{/if}

				{#if section.proxyComparison}
					<div class="proxy-comparison">
						<div class="proxy-box proxied">
							<h3>
								<i class="ph ph-check-circle" aria-hidden="true"></i>
								{section.proxyComparison.proxied.title}
							</h3>
							<ul>
								{#each section.proxyComparison.proxied.items as item}
									<li>
										<strong>{item.label}:</strong> {item.text}
									</li>
								{/each}
							</ul>
						</div>

						<div class="proxy-box direct">
							<h3>
								<i class="ph ph-broadcast" aria-hidden="true"></i>
								{section.proxyComparison.direct.title}
							</h3>
							<ul>
								{#each section.proxyComparison.direct.items as item}
									<li>
										<strong>{item.label}:</strong> {item.text}
									</li>
								{/each}
							</ul>
						</div>
					</div>
				{/if}

				{#if section.legalBasis}
					<p class="legal-basis">
						<em>Legal basis: {section.legalBasis}</em>
					</p>
				{/if}

				{#if section.legalLinks}
					<div class="legal-links">
						{#each section.legalLinks as link}
							<a href={link.href} target="_blank" rel="noopener noreferrer" class="btn btn-secondary">
								<i class="ph {link.icon}" aria-hidden="true"></i>
								{link.label}
							</a>
						{/each}
					</div>
				{/if}
			</section>
		{/each}
	</div>
</div>

<style>
	.privacy-container {
		max-width: 900px;
		margin: 0 auto;
		display: flex;
		flex-direction: column;
		gap: 1.75rem;
	}

	.page-header {
		display: flex;
		align-items: flex-start;
		gap: 1.25rem;
		padding-bottom: 1.25rem;
		border-bottom: 1px solid var(--border-subtle);
	}

	.header-icon {
		width: 3.25rem;
		height: 3.25rem;
		border-radius: 14px;
		background: color-mix(in srgb, var(--accent-green) 15%, transparent);
		color: var(--accent-green);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.85rem;
		flex-shrink: 0;
	}

	h1 {
		font-size: 1.85rem;
		font-weight: 800;
		letter-spacing: -0.03em;
		color: var(--text-primary);
		margin-bottom: 0.2rem;
	}

	.page-sub {
		color: var(--text-secondary);
		font-size: 0.98rem;
		line-height: 1.5;
	}

	.updated {
		color: var(--text-muted);
		font-size: 0.8rem;
		margin-top: 0.35rem;
		font-weight: 500;
	}

	.content-grid {
		display: flex;
		flex-direction: column;
		gap: 1.35rem;
	}

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 16px;
		padding: 1.5rem;
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
		transition: border-color 0.2s ease;
	}

	.card:hover {
		border-color: var(--border-muted);
	}

	.highlight-card {
		background: linear-gradient(135deg, color-mix(in srgb, var(--accent-green) 8%, var(--bg-surface)), var(--bg-surface));
		border-color: color-mix(in srgb, var(--accent-green) 25%, var(--border-subtle));
	}

	h2 {
		font-size: 1.2rem;
		font-weight: 700;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	h2 :global(.ph) {
		color: var(--accent-green);
		font-size: 1.35rem;
	}

	p {
		color: var(--text-secondary);
		line-height: 1.65;
		font-size: 0.95rem;
	}

	.legal-basis {
		font-size: 0.85rem;
		color: var(--text-muted);
		margin-top: 0.2rem;
	}

	ul {
		display: flex;
		flex-direction: column;
		gap: 0.55rem;
		padding-left: 1.2rem;
		color: var(--text-secondary);
		font-size: 0.93rem;
		line-height: 1.6;
	}

	ul li strong {
		color: var(--text-primary);
	}

	a {
		color: var(--accent-green);
		text-decoration: underline;
		text-underline-offset: 3px;
	}

	a:hover {
		color: var(--text-primary);
	}

	.proxy-comparison {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 1.25rem;
		margin-top: 0.5rem;
	}

	@media (max-width: 768px) {
		.proxy-comparison {
			grid-template-columns: 1fr;
		}
	}

	.proxy-box {
		padding: 1.25rem;
		border-radius: 12px;
		border: 1px solid var(--border-subtle);
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.proxy-box.proxied {
		background: color-mix(in srgb, var(--accent-green) 5%, var(--bg-surface));
		border-color: color-mix(in srgb, var(--accent-green) 20%, var(--border-subtle));
	}

	.proxy-box.direct {
		background: color-mix(in srgb, var(--bg-elevated) 60%, var(--bg-surface));
	}

	.proxy-box h3 {
		font-size: 0.98rem;
		font-weight: 700;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.45rem;
	}

	.proxy-box.proxied h3 :global(.ph) {
		color: var(--accent-green);
	}

	.proxy-box.direct h3 :global(.ph) {
		color: var(--text-muted);
	}

	.proxy-box ul {
		padding-left: 1rem;
		gap: 0.5rem;
	}

	.legal-links {
		display: flex;
		gap: 1rem;
		flex-wrap: wrap;
		margin-top: 0.35rem;
	}

</style>
