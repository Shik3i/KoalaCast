<footer class="app-footer">
	<div class="footer-container">
		<div class="footer-brand">
			<div class="brand">
				<span class="logo-icon">🌿</span>
				<span class="brand-title">KoalaCast</span>
			</div>
			<p class="tagline">Completely free, open-source, privacy-first podcast player with cross-device synchronization.</p>
		</div>

		<div class="footer-links">
			<div class="link-column">
				<h4>Legal & Privacy</h4>
				<a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer">
					<i class="ph ph-scales" aria-hidden="true"></i> Legal Notice (koalastuff.net/legal)
				</a>
				<a href="/settings#privacy">
					<i class="ph ph-shield-check" aria-hidden="true"></i> Privacy Policy
				</a>
			</div>

			<div class="link-column">
				<h4>Open Source</h4>
				<a href="https://github.com/Shik3i/KoalaCast" target="_blank" rel="noopener noreferrer">
					<i class="ph ph-github-logo" aria-hidden="true"></i> GitHub Repository
				</a>
				<a href="https://github.com/Shik3i/KoalaCast/blob/main/LICENSE" target="_blank" rel="noopener noreferrer">
					<i class="ph ph-file-text" aria-hidden="true"></i> MIT License
				</a>
			</div>
		</div>
	</div>

	<div class="footer-bottom">
		<p>© 2026 KoalaCast Contributors • Timo Schmidt (Shik3i)</p>
	</div>
</footer>

<style>
	.app-footer {
		background-color: var(--bg-surface);
		border-top: 1px solid var(--border-subtle);
		padding: 3rem 2rem 2rem;
		margin-top: 4rem;
	}

	.footer-container {
		max-width: 1200px;
		margin: 0 auto;
		display: flex;
		justify-content: space-between;
		gap: 3rem;
		flex-wrap: wrap;
	}

	.footer-brand {
		max-width: 380px;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.brand {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 700;
		font-size: 1.25rem;
	}

	.logo-icon {
		font-size: 1.5rem;
	}

	.tagline {
		color: var(--text-secondary);
		font-size: 0.9rem;
		line-height: 1.6;
	}

	.footer-links {
		display: flex;
		gap: 4rem;
	}

	.link-column {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.link-column h4 {
		font-size: 0.95rem;
		font-weight: 600;
		color: var(--text-primary);
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}

	.link-column a {
		color: var(--text-secondary);
		font-size: 0.9rem;
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	.link-column a:hover {
		color: var(--accent-green);
		text-decoration: none;
	}

	.footer-bottom {
		max-width: 1200px;
		margin: 2rem auto 0;
		padding-top: 1.5rem;
		border-top: 1px solid var(--border-subtle);
		text-align: center;
		font-size: 0.85rem;
		color: var(--text-muted);
	}
</style>
