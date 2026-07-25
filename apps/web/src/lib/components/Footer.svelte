<footer class="app-footer">
	<div class="footer-container">
		<div class="footer-brand">
			<div class="brand">
				<picture>
					<source type="image/avif" srcset="/icon-36.avif 1x, /icon-72.avif 2x" />
					<source type="image/webp" srcset="/icon-36.webp 1x, /icon-72.webp 2x" />
					<img class="logo-icon" src="/icon-36.png" srcset="/icon-36.png 1x, /icon-72.png 2x" alt="" width="36" height="36" aria-hidden="true" />
				</picture>
				<span class="brand-title">KoalaCast</span>
			</div>
			<p class="tagline">Next-generation, privacy-first podcast player with cross-device sync.</p>
			<p class="copyright">© 2026 KoalaStuff</p>
		</div>

		<div class="footer-links">
			<div class="link-column">
				<h4>Legal &amp; Privacy</h4>
				<a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer">
					<i class="ph ph-scales" aria-hidden="true"></i> Legal Notice
				</a>
				<a href="/privacy">
					<i class="ph ph-shield-check" aria-hidden="true"></i> Privacy Policy
				</a>
			</div>

			<div class="link-column">
				<h4>Open Source</h4>
				<a href="https://github.com/Shik3i/KoalaCast" target="_blank" rel="noopener noreferrer">
					<i class="ph ph-github-logo" aria-hidden="true"></i> GitHub
				</a>
				<a href="https://github.com/Shik3i/KoalaCast/blob/main/LICENSE" target="_blank" rel="noopener noreferrer">
					<i class="ph ph-file-text" aria-hidden="true"></i> MIT License
				</a>
			</div>
		</div>
	</div>
</footer>

<style>
	.app-footer {
		background: color-mix(in srgb, var(--bg-surface) 75%, var(--bg-primary));
		border-top: 1px solid var(--border-subtle);
		padding: 1.75rem 2rem 1.5rem;
		margin-top: 2.5rem;
		backdrop-filter: blur(12px);
	}

	.brand-title {
		background: linear-gradient(120deg, var(--text-primary), var(--accent-green));
		-webkit-background-clip: text;
		background-clip: text;
		-webkit-text-fill-color: transparent;
	}

	.footer-container {
		max-width: 1200px;
		margin: 0 auto;
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 2rem;
		flex-wrap: wrap;
	}

	.footer-brand {
		max-width: 380px;
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
	}

	.brand {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 700;
		font-size: 1.15rem;
	}

	.logo-icon {
		width: 1.85rem;
		height: 1.85rem;
		object-fit: contain;
	}

	.tagline {
		color: var(--text-secondary);
		font-size: 0.85rem;
		line-height: 1.5;
	}

	.copyright {
		color: var(--text-muted);
		font-size: 0.8rem;
		font-weight: 500;
		margin-top: 0.25rem;
	}

	.footer-links {
		display: flex;
		gap: 3.5rem;
	}

	.link-column {
		display: flex;
		flex-direction: column;
		gap: 0.55rem;
	}

	.link-column h4 {
		font-size: 0.8rem;
		font-weight: 700;
		color: var(--text-muted);
		text-transform: uppercase;
		letter-spacing: 0.08em;
	}

	.link-column a {
		color: var(--text-secondary);
		font-size: 0.88rem;
		display: flex;
		align-items: center;
		gap: 0.45rem;
		transition: color 0.2s ease;
	}

	.link-column a:hover {
		color: var(--accent-green);
		text-decoration: none;
	}
</style>
