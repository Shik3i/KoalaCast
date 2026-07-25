<svelte:head>
	<title>Privacy Policy — KoalaCast</title>
	<meta name="description" content="KoalaCast Privacy Policy — Learn how KoalaCast protects your privacy with local-first storage, anonymous backend proxying, and zero third-party tracking." />
</svelte:head>

<div class="privacy-container">
	<header class="page-header">
		<div class="header-icon">
			<i class="ph ph-shield-check" aria-hidden="true"></i>
		</div>
		<div>
			<h1>Privacy Policy</h1>
			<p class="page-sub">How KoalaCast protects your privacy with local-first architecture and transparent data practices.</p>
		</div>
	</header>

	<div class="content-grid">
		<!-- Section 1: Overview -->
		<section class="card highlight-card">
			<h2><i class="ph ph-lock-key" aria-hidden="true"></i> Privacy-First Philosophy</h2>
			<p>
				KoalaCast is built with privacy as a foundational principle. Whether you use KoalaCast self-hosted or on a hosted instance, 
				we do <strong>not</strong> track your listening habits, build behavioral profiles, sell your personal data, or include third-party advertising or analytics scripts.
			</p>
		</section>

		<!-- Section 2: Local Storage -->
		<section class="card">
			<h2><i class="ph ph-hard-drive" aria-hidden="true"></i> Local-First Storage</h2>
			<p>
				By default, KoalaCast operates in a local-first browser mode:
			</p>
			<ul>
				<li><strong>Subscriptions &amp; Queue:</strong> Your podcast subscriptions, episode queue, and playback progress are stored locally on your device using IndexedDB and LocalStorage.</li>
				<li><strong>Listening History:</strong> Your history remains on your local device unless you explicitly opt into cross-device sync.</li>
				<li><strong>Preferences:</strong> Theme choices and genre preferences are stored on-device.</li>
			</ul>
		</section>

		<!-- Section 3: Server Proxying -->
		<section class="card">
			<h2><i class="ph ph-globe" aria-hidden="true"></i> Anonymous Backend Proxying</h2>
			<p>
				To protect your device's IP address from third-party networks, KoalaCast handles external requests through the backend proxy:
			</p>
			<ul>
				<li><strong>Podcast Discovery &amp; Search:</strong> Search queries (iTunes / PodcastIndex) are routed through the backend so search APIs never receive requests directly from your browser.</li>
				<li><strong>Artwork Proxy:</strong> Podcast images are fetched and cached by the backend server. Your browser requests artwork from the same origin, preventing image servers from tracking your IP address across feeds.</li>
				<li><strong>RSS Feed Parsing:</strong> RSS feed XML files are fetched and parsed on the server side.</li>
			</ul>
		</section>

		<!-- Section 4: Accounts & Sync -->
		<section class="card">
			<h2><i class="ph ph-user-circle" aria-hidden="true"></i> Optional Sync Account</h2>
			<p>
				You can use KoalaCast without creating an account. If you choose to register an account for cross-device sync:
			</p>
			<ul>
				<li><strong>Credentials:</strong> Passwords are hashed securely using <code>bcrypt</code> before being saved to the database.</li>
				<li><strong>Session Cookies:</strong> Authentication uses a secure <code>HttpOnly</code> session cookie (<code>koalacast_session</code>) to keep you signed in.</li>
				<li><strong>Sync Data:</strong> Subscriptions, queue state, and playback positions are synced securely between your authenticated devices and the KoalaCast database.</li>
			</ul>
		</section>

		<!-- Section 5: Audio Streaming -->
		<section class="card">
			<h2><i class="ph ph-broadcast" aria-hidden="true"></i> Third-Party Audio Playback</h2>
			<p>
				When playing a podcast episode, audio files (MP3/AAC) are streamed directly from the publisher's hosting server (CDN). 
				As with any web browser media playback, the publisher's server may log standard HTTP request metadata (such as your IP address and browser User-Agent) 
				necessary to deliver the audio stream.
			</p>
		</section>

		<!-- Section 6: Control & Rights -->
		<section class="card">
			<h2><i class="ph ph-sliders" aria-hidden="true"></i> Data Control &amp; Deletion</h2>
			<p>
				You retain full control over your data:
			</p>
			<ul>
				<li><strong>Exporting:</strong> You can export your subscriptions at any time as a standard OPML file in <a href="/settings">Settings</a>.</li>
				<li><strong>Local Data Removal:</strong> You can clear all client-side stored data directly in <a href="/settings">Settings</a>.</li>
				<li><strong>Account Management:</strong> Account sessions can be reviewed and revoked at any time.</li>
			</ul>
		</section>

		<!-- Section 7: External Legal Links -->
		<section class="card legal-card">
			<h2><i class="ph ph-scales" aria-hidden="true"></i> Operator Information</h2>
			<p>
				KoalaCast is an open-source project by Timo Schmidt (Shik3i). For additional legal information regarding the hosting platform, please visit:
			</p>
			<div class="legal-links">
				<a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer" class="btn btn-secondary">
					<i class="ph ph-scales" aria-hidden="true"></i> Legal Notice (Impressum)
				</a>
				<a href="https://koalastuff.net/privacy" target="_blank" rel="noopener noreferrer" class="btn btn-secondary">
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i> koalastuff.net Privacy
				</a>
			</div>
		</section>
	</div>
</div>

<style>
	.privacy-container {
		max-width: 900px;
		margin: 0 auto;
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.page-header {
		display: flex;
		align-items: flex-start;
		gap: 1.25rem;
		padding-bottom: 1.5rem;
		border-bottom: 1px solid var(--border-subtle);
	}

	.header-icon {
		width: 3.5rem;
		height: 3.5rem;
		border-radius: 16px;
		background: color-mix(in srgb, var(--accent-green) 15%, transparent);
		color: var(--accent-green);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 2rem;
		flex-shrink: 0;
	}

	h1 {
		font-size: 2rem;
		font-weight: 800;
		letter-spacing: -0.03em;
		color: var(--text-primary);
		margin-bottom: 0.35rem;
	}

	.page-sub {
		color: var(--text-secondary);
		font-size: 1.05rem;
		line-height: 1.5;
	}

	.content-grid {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 16px;
		padding: 1.75rem;
		display: flex;
		flex-direction: column;
		gap: 1rem;
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
		font-size: 1.25rem;
		font-weight: 700;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	h2 :global(.ph) {
		color: var(--accent-green);
		font-size: 1.4rem;
	}

	p {
		color: var(--text-secondary);
		line-height: 1.65;
		font-size: 0.98rem;
	}

	ul {
		display: flex;
		flex-direction: column;
		gap: 0.65rem;
		padding-left: 1.25rem;
		color: var(--text-secondary);
		font-size: 0.95rem;
		line-height: 1.6;
	}

	ul li strong {
		color: var(--text-primary);
	}

	code {
		background: var(--bg-elevated);
		padding: 0.15rem 0.4rem;
		border-radius: 6px;
		font-size: 0.88em;
		color: var(--accent-green);
	}

	a {
		color: var(--accent-green);
		text-decoration: underline;
		text-underline-offset: 3px;
	}

	a:hover {
		color: var(--text-primary);
	}

	.legal-links {
		display: flex;
		gap: 1rem;
		flex-wrap: wrap;
		margin-top: 0.5rem;
	}

	.btn {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.65rem 1.25rem;
		border-radius: 12px;
		font-weight: 600;
		font-size: 0.92rem;
		text-decoration: none;
		transition: all 0.2s ease;
	}

	.btn-secondary {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
	}

	.btn-secondary:hover {
		background: color-mix(in srgb, var(--accent-green) 15%, var(--bg-elevated));
		border-color: var(--accent-green);
		color: var(--accent-green);
		transform: translateY(-1px);
	}
</style>
