<svelte:head>
	<title>Privacy Policy | KoalaCast</title>
	<meta name="description" content="How KoalaCast processes seven-day webserver access logs, account and session records, local browser storage, server proxying, and direct audio streams." />
</svelte:head>

<div class="privacy-container">
	<header class="page-header">
		<div class="header-icon">
			<i class="ph ph-shield-check" aria-hidden="true"></i>
		</div>
		<div>
			<h1>Privacy Policy</h1>
			<p class="page-sub">How KoalaCast processes connection data, local browser storage, server proxying, accounts, and audio streams.</p>
			<p class="updated">Last updated: 25 July 2026</p>
		</div>
	</header>

	<div class="content-grid">
		<!-- Section 1: Operator and hosting -->
		<section class="card">
			<h2><i class="ph ph-buildings" aria-hidden="true"></i> 1. Operator and hosting</h2>
			<p>
				The operator named in the <a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer">Legal Notice (Impressum)</a> is responsible for this service. 
				KoalaCast is hosted on infrastructure provided by Hetzner Online GmbH, Industriestr. 25, 91710 Gunzenhausen, Germany. 
				The regular hosting location is within the EU/EEA.
			</p>
			<p class="legal-basis">
				<em>Legal basis: Art. 6(1)(f) GDPR, legitimate interest in reliable service delivery, technical troubleshooting, and secure operation.</em>
			</p>
		</section>

		<!-- Section 2: Connection and security data -->
		<section class="card">
			<h2><i class="ph ph-shield-warning" aria-hidden="true"></i> 2. Connection and security data</h2>
			<p>
				The web server keeps access logs for seven days. These logs may contain the IP address, request date and time, requested HTTP method and path, response status, referrer, and browser user agent. 
				They are used to operate and troubleshoot the service and to detect and block bad actors, spam, denial-of-service (DoS) attacks, and other abuse. 
				Access logs are not used for advertising, profiling, or audience analytics and are deleted automatically after seven days.
			</p>
			<p>
				KoalaCast also uses the current network address temporarily in memory for rate limiting and abuse prevention. 
				When a network address is stored with a session or security audit event, IPv4 addresses are reduced to their /24 network (e.g. <code>192.168.1.0</code>) and IPv6 addresses to at most the first three groups (e.g. <code>2001:db8::</code>). 
				A shortened browser user agent may also be stored for session security.
			</p>
			<p class="legal-basis">
				<em>Legal basis: Art. 6(1)(f) GDPR, legitimate interest in reliable service delivery, technical troubleshooting, secure operation, and abuse prevention.</em>
			</p>
		</section>

		<!-- Section 3: Local Browser Mode -->
		<section class="card">
			<h2><i class="ph ph-hard-drive" aria-hidden="true"></i> 3. Local Browser Mode</h2>
			<p>
				KoalaCast is 100% functional without an account. By default, your data is stored locally in your browser:
			</p>
			<ul>
				<li><strong>Subscriptions &amp; Queue:</strong> Your podcast subscriptions, episode queue, and playback progress are stored locally on your device in <code>IndexedDB</code>.</li>
				<li><strong>Listening History:</strong> Your history remains on your local device unless you explicitly opt into cross-device sync.</li>
				<li><strong>Preferences:</strong> Theme choices and genre preferences are saved in <code>LocalStorage</code>.</li>
			</ul>
			<p>
				In local mode, no listening activity, subscriptions, or history records are transmitted to or saved on the KoalaCast server database.
			</p>
			<p class="legal-basis">
				<em>Legal basis: Art. 6(1)(b) GDPR, providing the requested local application functionality.</em>
			</p>
		</section>

		<!-- Section 4: Server Proxying & External Metadata -->
		<section class="card">
			<h2><i class="ph ph-arrows-merge" aria-hidden="true"></i> 4. Server Proxying &amp; External Metadata</h2>
			<p>
				To protect your IP address from third-party networks and bypass CORS restrictions, KoalaCast handles external metadata through the backend proxy:
			</p>
			<div class="proxy-comparison">
				<div class="proxy-box proxied">
					<h3><i class="ph ph-check-circle" aria-hidden="true"></i> Proxied by KoalaCast Backend:</h3>
					<ul>
						<li>
							<strong>Artwork Proxy (<code>/api/v1/proxy/image</code>):</strong> Podcast cover images are fetched by the server, Catmull-Rom downscaled, compressed to JPEG, and cached in a 100MB RAM LRU cache. Third-party image hosts do not see client IP addresses.
						</li>
						<li>
							<strong>Search &amp; Discovery:</strong> Search queries (iTunes Search API &amp; PodcastIndex API) are executed by the Go backend. Search APIs see only the KoalaCast server IP address.
						</li>
						<li>
							<strong>RSS Feed Parsing:</strong> Podcast RSS XML feed files are fetched and parsed on the server side.
						</li>
						<li>
							<strong>Chapters &amp; Transcripts:</strong> Episode chapter JSON and WebVTT/SRT transcript files are fetched via CORS-safe proxy endpoints (<code>/api/v1/proxy/chapters</code>, <code>/api/v1/proxy/transcript</code>).
						</li>
					</ul>
				</div>

				<div class="proxy-box direct">
					<h3><i class="ph ph-broadcast" aria-hidden="true"></i> Direct Connection (NOT Proxied):</h3>
					<ul>
						<li>
							<strong>Audio Streams (MP3 / AAC):</strong> Audio media files are streamed <strong>directly from podcast publishers' CDNs</strong> (e.g. Libsyn, Megaphone, Podbean, Anchor, AWS S3) to your browser HTML5 player.
						</li>
						<li>
							<strong>Reasoning:</strong> KoalaCast servers do not proxy or re-encode gigabytes of audio data, ensuring zero server latency and minimal resource overhead.
						</li>
						<li>
							<strong>Publisher Metadata:</strong> When playing an episode, the publisher's CDN receives standard HTTP GET requests directly from your browser, containing your IP address and User-Agent header as required for web audio delivery.
						</li>
					</ul>
				</div>
			</div>
			<p class="legal-basis">
				<em>Legal basis: Art. 6(1)(f) GDPR, privacy-preserving proxying and CORS compatibility.</em>
			</p>
		</section>

		<!-- Section 5: Accounts and sessions -->
		<section class="card">
			<h2><i class="ph ph-user-circle" aria-hidden="true"></i> 5. Accounts and sessions</h2>
			<p>
				Account and session records are personal data when they relate to an identifiable user. 
				For registered accounts, KoalaCast stores a public username, account status, role, account creation/login timestamps, and a <code>bcrypt</code> password hash. 
				It <strong>does not require an email address</strong> and never stores the password itself. Account data is retained until deletion is requested or required for account administration.
			</p>
			<p>
				Login uses one essential <code>HttpOnly</code> session cookie (<code>koalacast_session</code>). Only a SHA-256 hash of its random token is stored on the server. 
				Sessions expire after 30 days by default, are invalidated on logout, and are removed after expiry. 
				This cookie is strictly necessary for requested login functionality, so no consent banner is required for it.
			</p>
			<p>
				Optional cross-device sync synchronizes user subscriptions, episode queue, and playback progress to the server's SQLite database.
			</p>
			<p class="legal-basis">
				<em>Legal basis: Art. 6(1)(b) GDPR, providing the requested account and sync service, and Art. 6(1)(f) GDPR, account security.</em>
			</p>
		</section>

		<!-- Section 6: No advertising or client tracking -->
		<section class="card highlight-card">
			<h2><i class="ph ph-lock-key" aria-hidden="true"></i> 6. No advertising or client tracking</h2>
			<p>
				KoalaCast does <strong>not</strong> use third-party analytics (no Google Analytics, no Plausible/Matomo scripts), tracking cookies, advertising networks, social media embeds, or behavioral profiling. 
				It does not use automated decision-making within the meaning of Art. 22 GDPR.
			</p>
		</section>

		<!-- Section 7: External links -->
		<section class="card">
			<h2><i class="ph ph-arrow-square-out" aria-hidden="true"></i> 7. External links</h2>
			<p>
				GitHub, license, and publisher links are ordinary external links, not embedded trackers. 
				Opening one sends the usual connection data to that provider under its own privacy policy.
			</p>
		</section>

		<!-- Section 8: Your rights -->
		<section class="card legal-card">
			<h2><i class="ph ph-scales" aria-hidden="true"></i> 8. Your rights &amp; Data Control</h2>
			<p>
				Under the GDPR, you have the right to access (Art. 15), rectification (Art. 16), erasure (Art. 17), restriction of processing (Art. 18), data portability (Art. 20), objection (Art. 21), and lodging a complaint with a supervisory authority.
			</p>
			<ul>
				<li><strong>OPML Export:</strong> Export or import your subscriptions at any time in <a href="/settings">Settings</a>.</li>
				<li><strong>Local Data Wipe:</strong> Clear all client-side stored IndexedDB/LocalStorage data in <a href="/settings">Settings</a>.</li>
				<li><strong>Session Management:</strong> Review and revoke active account sessions at any time in <a href="/settings">Settings</a>.</li>
			</ul>
			<p>
				For privacy inquiries or account deletion requests, please use the contact details provided in the <a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer">Legal Notice</a>.
			</p>
		</section>
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
</style>
