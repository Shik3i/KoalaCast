<svelte:head>
	<title>Datenschutzerklärung / Privacy Policy — KoalaCast</title>
	<meta name="description" content="KoalaCast Privacy Policy — Complete, accurate disclosure of data processing, backend proxying, local storage, and streaming practices." />
</svelte:head>

<div class="privacy-container">
	<header class="page-header">
		<div class="header-icon">
			<i class="ph ph-shield-check" aria-hidden="true"></i>
		</div>
		<div>
			<h1>Datenschutzerklärung / Privacy Policy</h1>
			<p class="page-sub">Transparente Übersicht aller Datenverarbeitungen, Proxy-Mechanismen und Speicherorte in KoalaCast.</p>
		</div>
	</header>

	<div class="content-grid">
		<!-- Section 1: Overview & Philosophy -->
		<section class="card highlight-card">
			<h2><i class="ph ph-lock-key" aria-hidden="true"></i> 1. Grundsatz &amp; Tracking-Freiheit</h2>
			<p>
				KoalaCast ist eine <strong>vollständig tracking-freie, werbefreie und datensparsame</strong> Podcast-Plattform. 
				Wir setzen <strong>keine Analytics-Tools</strong> (z.B. Google Analytics), keine Tracking-Pixel, keine Werbenetzwerke und keine Telemetrie ein. 
				Es werden keine Nutzerprofile erstellt und keine Daten an Dritte verkauft.
			</p>
		</section>

		<!-- Section 2: Local Storage -->
		<section class="card">
			<h2><i class="ph ph-hard-drive" aria-hidden="true"></i> 2. Lokaler Modus (Browser-Speicherung)</h2>
			<p>
				KoalaCast kann <strong>ohne Benutzerkonto</strong> genutzt werden. Sämtliche persönlichen Nutzungsdaten verbleiben auf Ihrem Endgerät:
			</p>
			<ul>
				<li><strong>Abonnements &amp; Warteschlange:</strong> Werden lokal in der Browser-Datenbank (<code>IndexedDB</code>) gespeichert.</li>
				<li><strong>Wiedergabefortschritt &amp; Verlauf:</strong> Verbleiben ausschließlich lokal auf Ihrem Gerät.</li>
				<li><strong>Einstellungen:</strong> Dark Mode / Theme und Genre-Präferenzen werden in <code>LocalStorage</code> gesichert.</li>
			</ul>
		</section>

		<!-- Section 3: What is proxied vs what is NOT -->
		<section class="card">
			<h2><i class="ph ph-arrows-merge" aria-hidden="true"></i> 3. Server-Proxying: Was wird geproxt, was nicht?</h2>
			
			<div class="proxy-comparison">
				<div class="proxy-box proxied">
					<h3><i class="ph ph-check-circle" aria-hidden="true"></i> Über KoalaCast Server geproxt:</h3>
					<ul>
						<li>
							<strong>Podcast-Suche &amp; Discovery:</strong> Suchanfragen an iTunes &amp; PodcastIndex werden vom KoalaCast Backend ausgeführt. 
							Die Suchdienste sehen nur die IP-Adresse des KoalaCast Servers, nicht Ihre IP.
						</li>
						<li>
							<strong>Cover-Bilder (Artwork Proxy):</strong> Bilder werden über <code>/api/v1/proxy/image</code> auf dem Server geladen, skaliert und komprimiert (RAM-LRU Cache). 
							Bilder-Server von Drittanbietern können Ihre IP-Adresse nicht nachverfolgen.
						</li>
						<li>
							<strong>Kapitel &amp; Transkripte:</strong> Werden über <code>/api/v1/proxy/chapters</code> und <code>/api/v1/proxy/transcript</code> CORS-konform vom Server geladen.
						</li>
						<li>
							<strong>RSS Feeds:</strong> RSS-XML-Dateien werden vom KoalaCast Server geparst.
						</li>
					</ul>
				</div>

				<div class="proxy-box direct">
					<h3><i class="ph ph-broadcast" aria-hidden="true"></i> Direktverbindung (NICHT geproxt):</h3>
					<ul>
						<li>
							<strong>Audio-Dateien (MP3 / AAC Streams):</strong> Audiodateien von Podcast-Episoden werden <strong>direkt vom Browser</strong> des Nutzers beim jeweiligen Podcast-Publisher / CDN (z.B. Libsyn, Megaphone, Podbean, Anchor, AWS S3) gestreamt.
						</li>
						<li>
							<strong>Hintergrund:</strong> KoalaCast leitet keine Gigabytes an Audiodaten über den eigenen Server, um Bandbreite und Serverressourcen zu schonen.
						</li>
						<li>
							<strong>Datenschutz-Hinweis:</strong> Beim Abspielen einer Episode erhält der Server des jeweiligen Podcast-Publishers die Standard-HTTP-Anfrage Ihres Browsers (inkl. IP-Adresse und User-Agent) zur Auslieferung der Audiodatei.
						</li>
					</ul>
				</div>
			</div>
		</section>

		<!-- Section 4: Accounts & Sync -->
		<section class="card">
			<h2><i class="ph ph-user-circle" aria-hidden="true"></i> 4. Optionales Benutzerkonto &amp; Geräte-Sync</h2>
			<p>
				Wenn Sie optional ein Benutzerkonto zur geräteübergreifenden Synchronisation erstellen:
			</p>
			<ul>
				<li><strong>Zugangsdaten:</strong> Es werden nur ein Benutzername und ein Passwort benötigt (keine E-Mail-Adresse erforderlich). Passwörter werden mit <code>bcrypt</code> gehasht.</li>
				<li><strong>Session-Cookies:</strong> Die Authentifizierung erfolgt über ein sicheres <code>HttpOnly</code> Session-Cookie (<code>koalacast_session</code>).</li>
				<li><strong>Anonymisierte IP-Subnetze:</strong> In der Geräte-Übersicht werden IP-Adressen nur anonymisiert/gekürzt gespeichert (z.B. <code>192.168.1.0</code> oder <code>2001:db8::</code>), um aktive Sitzungen identifizierbar zu machen, ohne Standort-Tracking zu betreiben.</li>
				<li><strong>Sync-Daten:</strong> Abonnements, Warteschlange und Wiedergabepositionen werden verschlüsselt/geschützt in der Server-SQLite-Datenbank synchronisiert.</li>
			</ul>
		</section>

		<!-- Section 5: Data Export & Removal -->
		<section class="card">
			<h2><i class="ph ph-sliders" aria-hidden="true"></i> 5. Datenkontrolle &amp; Export</h2>
			<ul>
				<li><strong>OPML-Export:</strong> Sie können Ihre Abonnements jederzeit in den <a href="/settings">Einstellungen</a> als OPML-Datei exportieren oder importieren.</li>
				<li><strong>Lokale Daten löschen:</strong> In den Einstellungen können alle lokal im Browser gespeicherten Daten mit einem Klick gelöscht werden.</li>
				<li><strong>Session-Widerruf:</strong> Angemeldete Sitzungen können jederzeit in den Einstellungen eingesehen und widerrufen werden.</li>
			</ul>
		</section>

		<!-- Section 6: Operator Info -->
		<section class="card legal-card">
			<h2><i class="ph ph-scales" aria-hidden="true"></i> 6. Verantwortliche Stelle &amp; Impressum</h2>
			<p>
				KoalaCast ist ein Open-Source-Projekt von KoalaStuff (`koalastuff.net`). Für rechtliche Hinweise und das Impressum der Plattform besuchen Sie bitte:
			</p>
			<div class="legal-links">
				<a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer" class="btn btn-secondary">
					<i class="ph ph-scales" aria-hidden="true"></i> Impressum (koalastuff.net/legal)
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
		margin-bottom: 0.25rem;
	}

	.page-sub {
		color: var(--text-secondary);
		font-size: 0.98rem;
		line-height: 1.5;
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
		font-size: 1rem;
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

	.btn {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.6rem 1.15rem;
		border-radius: 12px;
		font-weight: 600;
		font-size: 0.9rem;
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
