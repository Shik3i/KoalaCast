<script lang="ts">
	import { onMount } from 'svelte';
	import { clearAllLocalData, saveLocalSubscription, getLocalSubscriptions } from '$lib/idb/db';
	import { getStoredTheme, setTheme, type ThemeMode } from '$lib/theme';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { sync } from '$lib/stores/sync.svelte';
	import { GENRES, genreLabel } from '$lib/genres';
	import { SUPPORTED_LANGUAGES } from '$lib/data/languages';
	import { LOCALES, t } from '$lib/i18n';

	// Tri-state cycle per genre: neutral → interested → hidden → neutral.
	function cycleGenre(name: string) {
		if (prefs.interests.includes(name)) {
			prefs.toggleInterest(name); // → neutral
			prefs.toggleHidden(name); // → hidden
		} else if (prefs.hiddenGenres.includes(name)) {
			prefs.toggleHidden(name); // → neutral
		} else {
			prefs.toggleInterest(name); // → interested
		}
	}
	function genreState(name: string): 'like' | 'hide' | 'neutral' {
		if (prefs.interests.includes(name)) return 'like';
		if (prefs.hiddenGenres.includes(name)) return 'hide';
		return 'neutral';
	}

	let usernameInput = $state('');
	let passwordInput = $state('');
	let isRegistering = $state(false);
	let isLoggingIn = $state(false);
	let authUser = $state<any>(null);
	let recoveryCodeDisplay = $state('');
	let authError = $state('');
	let sessions = $state<any[]>([]);
	let globalStatsOptIn = $state(false);
	let isLoadingGlobalStatsPreference = $state(false);
	let isSavingGlobalStatsPreference = $state(false);

	// Theme state
	let currentTheme = $state<ThemeMode>('system');

	// OPML Import States
	let isImportingOpml = $state(false);
	let opmlReport = $state<any>(null);
	let opmlError = $state('');

	onMount(async () => {
		currentTheme = getStoredTheme();
		// Restore an existing signed-in session after a reload: the HttpOnly session
		// cookie persists, but this component's auth state does not, so without this
		// a logged-in user always saw the sign-in form again.
		try {
			const res = await fetch('/api/v1/auth/status');
			if (res.ok) {
				const me = await res.json();
				if (me.authenticated && me.user_id) {
					authUser = { username: me.username, role: me.role };
					loadActiveSessions();
					loadGlobalStatsPreference();
					sync.enable(me.user_id);
				}
			}
		} catch (_) {}
	});

	async function revokeSession(id: string) {
		try {
			const res = await fetch(`/api/v1/auth/sessions/${id}`, { method: 'DELETE' });
			if (res.ok) {
				sessions = sessions.filter((s) => s.id !== id);
				toast.success(t('toast.sessionRevoked'));
			}
		} catch (_) {}
	}

	function handleThemeChange(mode: ThemeMode) {
		currentTheme = mode;
		setTheme(mode);
	}

	async function handleRegister(e: Event) {
		e.preventDefault();
		isRegistering = true;
		authError = '';

		try {
			const res = await fetch('/api/v1/auth/register', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ username: usernameInput, password: passwordInput })
			});

			const data = await res.json();
			if (!res.ok) {
				authError = data.error || t('settings.registrationFailed');
				return;
			}

			recoveryCodeDisplay = data.recovery_code;
			toast.success(t('toast.accountCreatedSaveCode'));
		} catch (err: any) {
			authError = t('settings.registrationNetworkError');
		} finally {
			isRegistering = false;
		}
	}

	async function handleLogin(e: Event) {
		e.preventDefault();
		isLoggingIn = true;
		authError = '';

		try {
			const res = await fetch('/api/v1/auth/login', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ username: usernameInput, password: passwordInput })
			});

			const data = await res.json();
			if (!res.ok) {
				authError = data.error || t('settings.loginFailed');
				return;
			}

			authUser = data;
			toast.success(t('toast.welcomeBack', { username: data.username }));
			loadActiveSessions();
			loadGlobalStatsPreference();
			// Kick off cross-device sync now that we're authenticated.
			if (data.user_id) sync.enable(data.user_id);
		} catch (err: any) {
			authError = t('settings.loginNetworkError');
		} finally {
			isLoggingIn = false;
		}
	}

	async function handleLogout() {
		try {
			await fetch('/api/v1/auth/logout', { method: 'POST' });
		} catch (_) {
			// Even if the network call fails, drop the client-side session view.
		}
		sync.disable();
		authUser = null;
		globalStatsOptIn = false;
		sessions = [];
		usernameInput = '';
		passwordInput = '';
		toast.success(t('settings.signedOut'));
	}

	async function loadActiveSessions() {
		try {
			const res = await fetch('/api/v1/auth/sessions');
			if (res.ok) {
				const data = await res.json();
				sessions = data.sessions || [];
			}
		} catch (_) {}
	}

	async function loadGlobalStatsPreference() {
		isLoadingGlobalStatsPreference = true;
		try {
			const res = await fetch('/api/v1/stats/preferences');
			if (!res.ok) return;
			const data = await res.json();
			globalStatsOptIn = data.global_stats_opt_in === true;
		} catch (_) {
			// Keep the safe default (off) when the preference cannot be loaded.
		} finally {
			isLoadingGlobalStatsPreference = false;
		}
	}

	async function updateGlobalStatsPreference(enabled: boolean) {
		if (isSavingGlobalStatsPreference) return;
		const previous = globalStatsOptIn;
		globalStatsOptIn = enabled;
		isSavingGlobalStatsPreference = true;
		try {
			const res = await fetch('/api/v1/stats/preferences', {
				method: 'PUT',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ global_stats_opt_in: enabled })
			});
			if (!res.ok) throw new Error(`preference update failed: ${res.status}`);
			const data = await res.json();
			globalStatsOptIn = data.global_stats_opt_in === true;
			toast.success(enabled ? t('settings.globalStatsEnabled') : t('settings.globalStatsDisabled'));
		} catch (_) {
			globalStatsOptIn = previous;
			toast.error(t('settings.globalStatsSaveError'));
		} finally {
			isSavingGlobalStatsPreference = false;
		}
	}

	async function handleResetLocalData() {
		if (confirm(t('settings.confirmReset'))) {
			await clearAllLocalData();
			toast.success(t('settings.localDataReset'));
		}
	}

	async function handleOpmlFileUpload(e: Event) {
		const target = e.target as HTMLInputElement;
		if (!target.files || target.files.length === 0) return;

		const file = target.files[0];
		isImportingOpml = true;
		opmlError = '';
		opmlReport = null;

		try {
			const xmlText = await file.text();
			const res = await fetch('/api/v1/opml/import', {
				method: 'POST',
				headers: { 'Content-Type': 'application/xml' },
				body: xmlText
			});

			const report = await res.json();
			if (!res.ok) {
				opmlError = report.error || t('settings.opmlImportFailed');
				return;
			}

			opmlReport = report;

			// Local-first: the server ingested the feeds and returned the resolved
			// podcasts; persist them as on-device subscriptions so they appear in the
			// (account-less) Library.
			if (Array.isArray(report.podcasts)) {
				for (const p of report.podcasts) {
					if (!p?.id) continue;
					try {
						await saveLocalSubscription({
							podcast_id: p.id,
							feed_url: p.feed_url || '',
							title: p.title || 'Podcast',
							artwork_url: p.artwork_url || '',
							added_at: Date.now()
						});
					} catch (_) {}
				}
			}

			toast.success(t('settings.opmlImported', { imported: report.imported, skipped: report.skipped }));
		} catch (err: any) {
			opmlError = t('settings.opmlReadError');
		} finally {
			isImportingOpml = false;
			target.value = '';
		}
	}

	// Export is generated on-device from local subscriptions so it works without an
	// account (the server export endpoint only sees account-synced subscriptions).
	function escapeXml(s: string): string {
		return (s || '').replace(
			/[<>&"']/g,
			(c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[c] as string
		);
	}

	async function handleExportOpml() {
		const subs = (await getLocalSubscriptions()).filter((s) => s.feed_url);
		if (subs.length === 0) {
			toast.error(t('settings.opmlNothingToExport'));
			return;
		}
		const outlines = subs
			.map(
				(s) =>
					`    <outline type="rss" text="${escapeXml(s.title)}" title="${escapeXml(s.title)}" xmlUrl="${escapeXml(s.feed_url)}" />`
			)
			.join('\n');
		const xml =
			`<?xml version="1.0" encoding="UTF-8"?>\n<opml version="2.0">\n  <head>\n    <title>KoalaCast Subscriptions</title>\n  </head>\n  <body>\n${outlines}\n  </body>\n</opml>\n`;
		const blob = new Blob([xml], { type: 'application/xml' });
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = 'koalacast_subscriptions.opml';
		document.body.appendChild(a);
		a.click();
		a.remove();
		URL.revokeObjectURL(url);
		toast.success(`Exported ${subs.length} subscription${subs.length === 1 ? '' : 's'}.`);
	}
</script>

<div class="settings-page">
	<div class="settings-head">
		<h2><i class="ph-fill ph-gear-six" aria-hidden="true"></i> {t('settings.title')}</h2>
		<p class="page-sub">{t('settings.subtitle')}</p>
	</div>

	<!-- Theme Selection Card -->
	<section class="card">
		<h3><i class="ph ph-palette" aria-hidden="true"></i> {t('settings.appearance')}</h3>
		<p class="subtitle">{t('settings.appearanceHint')}</p>

		<div class="theme-selector">
			<button
				class="theme-btn"
				class:active={currentTheme === 'system'}
				onclick={() => handleThemeChange('system')}
			>
				<i class="ph ph-desktop" aria-hidden="true"></i> {t('settings.themeSystem')}
			</button>

			<button
				class="theme-btn"
				class:active={currentTheme === 'dark'}
				onclick={() => handleThemeChange('dark')}
			>
				<i class="ph ph-moon" aria-hidden="true"></i> {t('settings.themeDark')}
			</button>

			<button
				class="theme-btn"
				class:active={currentTheme === 'light'}
				onclick={() => handleThemeChange('light')}
			>
				<i class="ph ph-sun" aria-hidden="true"></i> {t('settings.themeLight')}
			</button>
		</div>

		<p class="subtitle" style="margin-top: 1rem;">{t('settings.episodeDates')}</p>
		<div class="theme-selector">
			<button class="theme-btn" class:active={prefs.dateFormat === 'absolute'} onclick={() => prefs.setDateFormat('absolute')}>
				<i class="ph ph-calendar-blank" aria-hidden="true"></i> {t('settings.dateAbsolute')}
			</button>
			<button class="theme-btn" class:active={prefs.dateFormat === 'relative'} onclick={() => prefs.setDateFormat('relative')}>
				<i class="ph ph-clock-countdown" aria-hidden="true"></i> {t('settings.dateRelative')}
			</button>
		</div>
	</section>

	<!-- Interface Language Card — what KoalaCast itself is displayed in. Kept
	     separate from content languages: wanting a German UI and wanting only
	     German podcasts are two different preferences. -->
	<section class="card" id="interface-language">
		<h3><i class="ph ph-globe" aria-hidden="true"></i> {t('settings.interfaceLanguage')}</h3>
		<p class="subtitle">{t('settings.interfaceLanguageHint')}</p>
		<div class="language-grid">
			{#each LOCALES as locale (locale.code)}
				<button
					type="button"
					class="lang-chip"
					class:active={prefs.uiLanguage === locale.code}
					aria-pressed={prefs.uiLanguage === locale.code}
					onclick={() => prefs.setUILanguage(locale.code)}
				>
					<span class="flag-emoji">{locale.flag}</span>
					<span class="lang-name">{locale.name}</span>
					{#if prefs.uiLanguage === locale.code}
						<i class="ph-fill ph-check-circle state-ic" aria-hidden="true"></i>
					{/if}
				</button>
			{/each}
		</div>
		<p class="subtitle translate-cta">
			{t('settings.helpTranslate')}
			<a href="https://github.com/Shik3i/KoalaCast/blob/main/docs/i18n.md" target="_blank" rel="noopener noreferrer">
				{t('settings.helpTranslateLink')}
				<i class="ph ph-arrow-square-out" aria-hidden="true"></i>
			</a>
		</p>
	</section>

	<!-- Content Languages Card -->
	<section class="card" id="languages">
		<h3><i class="ph ph-translate" aria-hidden="true"></i> {t('settings.contentLanguages')}</h3>
		<p class="subtitle">{t('settings.contentLanguagesHint')}</p>
		<div class="language-grid">
			{#each SUPPORTED_LANGUAGES as lang (lang.code)}
				<button
					type="button"
					class="lang-chip"
					class:active={prefs.languages.includes(lang.code)}
					aria-pressed={prefs.languages.includes(lang.code)}
					onclick={() => prefs.toggleLanguage(lang.code)}
				>
					<span class="flag-emoji">{lang.flag}</span>
					<span class="lang-name">{lang.name}</span>
					{#if prefs.languages.includes(lang.code)}
						<i class="ph-fill ph-check-circle state-ic" aria-hidden="true"></i>
					{/if}
				</button>
			{/each}
		</div>
	</section>

	<section class="card" id="interests">
		<h3><i class="ph ph-sparkle" aria-hidden="true"></i> {t('settings.interests')}</h3>
		<p class="subtitle">{t('settings.interestsHint')}</p>
		<div class="genre-grid">
			{#each GENRES as g (g.name)}
				<button class="genre-chip {genreState(g.name)}" onclick={() => cycleGenre(g.name)}>
					<i class="ph {g.icon}" aria-hidden="true"></i>
					<span>{genreLabel(g.name)}</span>
					{#if genreState(g.name) === 'like'}<i class="ph-fill ph-heart state-ic" aria-hidden="true"></i>
					{:else if genreState(g.name) === 'hide'}<i class="ph-fill ph-eye-slash state-ic" aria-hidden="true"></i>{/if}
				</button>
			{/each}
		</div>
		<div class="genre-legend">
			<span><span class="dot like"></span> {t('settings.preferred')}</span>
			<span><span class="dot hide"></span> {t('settings.hidden')}</span>
		</div>
	</section>

	<section class="card" id="privacy">
		<h3><i class="ph ph-shield-check" aria-hidden="true"></i> {t('settings.privacy')}</h3>
		<div class="privacy-box">
			<h4>{t('settings.localBrowserMode')}</h4>
			<p>{t('settings.privacyBody')}</p>
		</div>
		{#if authUser}
			<div class="consent-row">
				<div>
					<h4>{t('settings.globalStatsOptIn')}</h4>
					<p>{t('settings.globalStatsOptInBody', { username: authUser.username })}</p>
					<a href="/global-stats">{t('settings.viewGlobalStats')} <i class="ph ph-arrow-right" aria-hidden="true"></i></a>
				</div>
				<label class="consent-switch">
					<input
						type="checkbox"
						checked={globalStatsOptIn}
						disabled={isLoadingGlobalStatsPreference || isSavingGlobalStatsPreference}
						onchange={(event) => updateGlobalStatsPreference(event.currentTarget.checked)}
					/>
					<span aria-hidden="true"></span>
					<strong>{globalStatsOptIn ? t('common.on') : t('common.off')}</strong>
				</label>
			</div>
		{:else}
			<div class="privacy-box muted">
				<h4>{t('settings.globalStatsOptIn')}</h4>
				<p>{t('settings.globalStatsSignIn')}</p>
			</div>
		{/if}
	</section>

	<!-- OPML Import / Export Card -->
	<section class="card">
		<h3><i class="ph ph-arrows-down-up" aria-hidden="true"></i> {t('settings.opmlTitle')}</h3>
		<p class="subtitle">{t('settings.opmlHint')}</p>

		{#if opmlError}
			<div class="error-banner">{opmlError}</div>
		{/if}

		{#if opmlReport}
			<div class="report-box">
				<h4>{t('settings.importSummary')}</h4>
				<ul>
					<li><strong>{t('settings.totalFound')}</strong> {opmlReport.total_found}</li>
					<li><strong>{t('settings.successfullyImported')}</strong> {opmlReport.imported}</li>
					<li><strong>{t('settings.skippedDuplicates')}</strong> {opmlReport.skipped}</li>
				</ul>
			</div>
		{/if}

		<div class="opml-actions">
			<label class="btn btn-import">
				<i class="ph ph-upload-simple" aria-hidden="true"></i>
				{isImportingOpml ? t('settings.importingOpml') : t('settings.uploadOpml')}
				<input type="file" accept=".opml,.xml" onchange={handleOpmlFileUpload} disabled={isImportingOpml} hidden />
			</label>

			<button type="button" class="btn btn-secondary" onclick={handleExportOpml}>
				<i class="ph ph-download-simple" aria-hidden="true"></i> {t('settings.exportOpml')}
			</button>
		</div>
	</section>

	<section class="card">
		<h3><i class="ph ph-user-circle" aria-hidden="true"></i> {t('settings.accountSync')}</h3>
		{#if authUser}
			<p class="subtitle">{t('settings.loggedInAs')} <strong>{authUser.username}</strong> ({authUser.role}). Subscriptions, progress, and listening statistics sync automatically across your devices.</p>
			
			<div class="sync-row">
				<div class="sync-info">
					<span class="sync-state">
						<span class="sync-dot {sync.status}"></span>
						{#if sync.status === 'syncing'}
							Syncing…
						{:else if sync.status === 'error'}
							Sync error — will retry automatically
						{:else if sync.lastSyncedAt}
							Synced · last {new Date(sync.lastSyncedAt).toLocaleTimeString()}
						{:else}
							Sync ready
						{/if}
					</span>
				</div>
				<button type="button" class="btn-secondary" onclick={() => sync.syncNow()} disabled={sync.status === 'syncing'}>
					<i class="ph ph-arrows-clockwise" aria-hidden="true"></i> {t('settings.syncNow')}
				</button>
			</div>

			<div class="account-actions">
				<a href="/account" class="btn btn-primary">
					<i class="ph ph-user-gear" aria-hidden="true"></i> {t('settings.manageAccount')}
				</a>
			</div>
		{:else}
			<p class="subtitle">{t('settings.signInPrompt')}</p>
			<div class="account-actions">
				<a href="/login" class="btn btn-primary">
					<i class="ph ph-sign-in" aria-hidden="true"></i> {t('common.signIn')}
				</a>
				<a href="/register" class="btn btn-secondary">
					<i class="ph ph-user-plus" aria-hidden="true"></i> {t('common.createAccount')}
				</a>
			</div>
		{/if}
	</section>

	<section class="card">
		<h3><i class="ph ph-database" aria-hidden="true"></i> {t('settings.dataManagement')}</h3>
		<button class="btn-danger" onclick={handleResetLocalData}>{t('settings.resetLocalData')}</button>
	</section>
</div>

<style>
	.settings-page {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.settings-head h2 {
		font-size: clamp(1.6rem, 3vw, 2.1rem);
		font-weight: 800;
		letter-spacing: -0.02em;
		display: flex;
		align-items: center;
		gap: 0.55rem;
	}
	.settings-head h2 :global(.ph-fill) { color: var(--accent-green); }
	.settings-head .page-sub { color: var(--text-muted); font-size: 0.95rem; margin-top: 0.25rem; }

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		padding: 1.6rem;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}
	.card h3 {
		display: flex;
		align-items: center;
		gap: 0.55rem;
		font-size: 1.1rem;
		font-weight: 700;
	}
	.card h3 :global(.ph) { color: var(--accent-green); font-size: 1.2rem; }
	.consent-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1.5rem;
		padding: 1rem;
		border: 1px solid var(--border-subtle);
		background: var(--bg-sunken);
		border-radius: 9px;
	}
	.consent-row h4 { margin-bottom: .35rem; }
	.consent-row p { max-width: 60ch; color: var(--text-muted); font-size: .9rem; }
	.consent-row a { display: inline-flex; gap: .3rem; align-items: center; margin-top: .65rem; color: var(--accent-green); font-weight: 700; font-size: .85rem; }
	.consent-switch { display: flex; align-items: center; gap: .55rem; cursor: pointer; white-space: nowrap; }
	.consent-switch input { position: absolute; opacity: 0; pointer-events: none; }
	.consent-switch span { width: 42px; height: 24px; padding: 3px; border-radius: 999px; background: var(--bg-elevated); border: 1px solid var(--border-ui); transition: background .15s; }
	.consent-switch span::after { content: ''; display: block; width: 16px; height: 16px; border-radius: 50%; background: var(--text-muted); transition: transform .15s, background .15s; }
	.consent-switch input:checked + span { background: var(--accent-fill); border-color: var(--accent-fill); }
	.consent-switch input:checked + span::after { transform: translateX(18px); background: var(--accent-on); }
	.consent-switch input:focus-visible + span { outline: 2px solid var(--accent-green); outline-offset: 2px; }
	.consent-switch input:disabled + span { opacity: .55; cursor: wait; }
	.privacy-box.muted { opacity: .78; }

	.subtitle {
		color: var(--text-secondary);
		font-size: 0.95rem;
		margin-top: -0.4rem;
	}

	.translate-cta {
		margin-top: 0.9rem;
		font-size: 0.85rem;
	}

	.translate-cta a {
		color: var(--accent-green);
		font-weight: 600;
		text-decoration: none;
		white-space: nowrap;
	}

	.translate-cta a:hover {
		text-decoration: underline;
	}

	/* Segmented control */
	.theme-selector {
		display: inline-flex;
		gap: 4px;
		flex-wrap: wrap;
		background: var(--bg-elevated);
		padding: 4px;
		border-radius: 12px;
		width: fit-content;
		margin-top: 0.25rem;
	}

	.theme-btn {
		background: transparent;
		color: var(--text-secondary);
		border: none;
		padding: 0.6rem 1.1rem;
		border-radius: 9px;
		font-weight: 700;
		font-size: 0.9rem;
		display: flex;
		align-items: center;
		gap: 0.5rem;
		transition: all 0.2s ease;
	}

	.theme-btn:hover {
		color: var(--text-primary);
		background: color-mix(in srgb, var(--text-primary) 6%, transparent);
	}

	.theme-btn.active {
		background: var(--accent-green);
		color: var(--accent-button-text);
	}
	.theme-btn.active:hover { background: var(--accent-green); }

	.genre-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
		gap: 0.6rem;
		margin-top: 0.25rem;
	}
	.genre-chip {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.6rem 0.85rem;
		border-radius: 12px;
		border: 1.5px solid var(--border-subtle);
		background: var(--bg-elevated);
		color: var(--text-secondary);
		font-weight: 600;
		font-size: 0.88rem;
		transition: all 0.18s var(--ease-spring, ease);
	}
	.genre-chip > span { flex: 1; text-align: left; }
	.genre-chip :global(.ph) { font-size: 1.15rem; }
	.genre-chip .state-ic { font-size: 0.95rem; }
	.genre-chip:hover { border-color: var(--text-muted); color: var(--text-primary); }
	.genre-chip.like {
		background: color-mix(in srgb, var(--accent-green) 15%, var(--bg-surface));
		border-color: var(--accent-green);
		color: var(--accent-green);
	}
	.genre-chip.hide {
		background: color-mix(in srgb, var(--color-danger) 12%, var(--bg-surface));
		border-color: var(--color-danger-border);
		color: var(--color-danger);
		text-decoration: line-through;
	}
	.genre-legend { display: flex; gap: 1.25rem; margin-top: 0.9rem; font-size: 0.82rem; color: var(--text-muted); }
	.genre-legend span { display: inline-flex; align-items: center; gap: 0.4rem; }
	.genre-legend .dot { width: 10px; height: 10px; border-radius: 50%; }
	.genre-legend .dot.like { background: var(--accent-green); }
	.genre-legend .dot.hide { background: var(--color-danger); }

	.language-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
		gap: 0.75rem;
		margin-top: 0.25rem;
	}

	.lang-chip {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		padding: 0.7rem 1rem;
		border-radius: 12px;
		border: 1.5px solid var(--border-subtle);
		background: var(--bg-elevated);
		color: var(--text-secondary);
		font-weight: 600;
		font-size: 0.92rem;
		cursor: pointer;
		transition: all 0.2s var(--ease-spring);
	}

	.lang-chip:hover {
		border-color: var(--text-muted);
		color: var(--text-primary);
	}

	.lang-chip.active {
		background: color-mix(in srgb, var(--accent-green) 15%, var(--bg-surface));
		border-color: var(--accent-green);
		color: var(--accent-green);
	}

	.lang-chip .lang-name {
		flex: 1;
		text-align: left;
	}

	.lang-chip .flag-emoji {
		font-family: 'Twemoji Country Flags', var(--font-sans);
		font-size: 1.25rem;
		line-height: 1;
	}

	.privacy-box {
		background: var(--bg-elevated);
		border-radius: 8px;
		padding: 1rem 1.25rem;
	}

	.auth-form {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		max-width: 400px;
	}

	.form-group {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}

	.opml-actions, .btn-group, .account-actions {
		display: flex;
		gap: 1rem;
		flex-wrap: wrap;
	}

	button, .btn {
		background: var(--accent-green);
		color: var(--accent-button-text);
		border: none;
		padding: 0.65rem 1.25rem;
		border-radius: 8px;
		font-weight: 600;
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		font-size: 0.95rem;
		text-decoration: none;
	}

	.btn-secondary {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
	}

	.btn-danger {
		background: var(--color-danger);
		color: var(--danger-button-text);
		width: fit-content;
	}

	.error-banner {
		padding: 0.75rem 1rem;
		background: var(--color-danger-bg);
		color: var(--text-primary);
		border: 1px solid var(--color-danger-border);
		border-radius: 8px;
	}

	.report-box {
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		padding: 1rem;
	}

	.report-box ul {
		margin-top: 0.5rem;
		margin-left: 1.25rem;
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.recovery-box {
		background: var(--color-warning-bg);
		color: var(--text-primary);
		border: 1px solid var(--color-warning-border);
		border-radius: 8px;
		padding: 1rem;
	}

	.sync-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		flex-wrap: wrap;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: 10px;
		padding: 0.75rem 1rem;
		margin: 0.25rem 0 0.5rem;
	}
	.sync-info { display: flex; flex-direction: column; gap: 0.2rem; min-width: 0; }
	.sync-state { font-weight: 600; font-size: 0.9rem; display: inline-flex; align-items: center; gap: 0.5rem; }
	.sync-hint { font-size: 0.8rem; color: var(--text-muted); }
	.sync-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--text-muted); flex-shrink: 0; }
	.sync-dot.idle { background: var(--accent-green); }
	.sync-dot.syncing { background: var(--focus-ring); animation: sync-pulse 1s ease-in-out infinite; }
	.sync-dot.error { background: var(--color-danger); }
	@keyframes sync-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }

	.sessions-list {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin: 0.25rem 0 0.5rem;
	}
	.session-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.75rem;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: 10px;
		padding: 0.6rem 0.85rem;
	}
	.s-info { display: flex; flex-direction: column; gap: 0.15rem; min-width: 0; }
	.s-name { font-weight: 600; font-size: 0.9rem; display: inline-flex; align-items: center; gap: 0.5rem; }
	.s-current {
		font-size: 0.68rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--accent-green);
		background: color-mix(in srgb, var(--accent-green) 16%, transparent);
		padding: 0.05rem 0.45rem;
		border-radius: 999px;
	}
	.s-meta {
		font-size: 0.78rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.s-revoke {
		background: var(--bg-surface);
		color: var(--color-danger);
		border: 1px solid var(--color-danger-border);
		padding: 0.35rem 0.8rem;
		border-radius: 8px;
		font-size: 0.82rem;
		font-weight: 600;
		flex-shrink: 0;
	}
	.s-revoke:hover { background: var(--color-danger-bg); }

	.code {
		font-family: monospace;
		font-size: 1.25rem;
		font-weight: 700;
		letter-spacing: 0.1em;
		margin-top: 0.5rem;
		color: var(--text-primary);
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		padding: 0.5rem;
		border-radius: 6px;
	}
	@media (max-width: 620px) {
		.consent-row { align-items: flex-start; flex-direction: column; }
	}
</style>
