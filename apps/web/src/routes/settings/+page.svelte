<script lang="ts">
	import { onMount } from 'svelte';
	import { saveLocalSubscriptions, getLocalSubscriptions } from '$lib/idb/db';
	import { resetAllLocalData } from '$lib/stores/account-context';
	import {
		COLOR_PALETTES,
		DEFAULT_PALETTE,
		getStoredPalette,
		getStoredTheme,
		setPalette,
		setTheme,
		type PaletteId,
		type ThemeMode
	} from '$lib/theme';
	import { toast } from '$lib/stores/toast.svelte';
	import { prefs, type DownloadRetention } from '$lib/stores/prefs.svelte';
	import { sync } from '$lib/stores/sync.svelte';
	import { activateAccountContext, activateLoggedInAccount } from '$lib/stores/account-context';
	import { GENRES, genreLabel } from '$lib/genres';
	import { SUPPORTED_LANGUAGES } from '$lib/data/languages';
	import { LOCALES, t } from '$lib/i18n';
	import { confirmDialog } from '$lib/stores/confirm.svelte';
	import { install } from '$lib/stores/install.svelte';
	import { opmlBackup, opmlBackupSupported } from '$lib/backup/opml-backup.svelte';
	import { buildOpml } from '$lib/opml';
	import VisualizerSignal from '$lib/components/VisualizerSignal.svelte';

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
	let currentPalette = $state<PaletteId>(DEFAULT_PALETTE);

	// OPML Import States
	let isImportingOpml = $state(false);
	let opmlReport = $state<any>(null);
	let opmlError = $state('');

	onMount(() => void opmlBackup.load());
	onMount(() => {
		const openHashSection = () => {
			const id = window.location.hash.slice(1);
			if (!id) return;
			const section = document.getElementById(id);
			if (section instanceof HTMLDetailsElement) section.open = true;
		};
		openHashSection();
		window.addEventListener('hashchange', openHashSection);
		return () => window.removeEventListener('hashchange', openHashSection);
	});

	onMount(async () => {
		currentTheme = getStoredTheme();
		currentPalette = getStoredPalette();
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
				}
			}
		} catch (_) {}
	});

	function themeLabel(mode: ThemeMode) {
		if (mode === 'dark') return t('settings.themeDark');
		if (mode === 'light') return t('settings.themeLight');
		return t('settings.themeSystem');
	}

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
		prefs.setThemeMode(mode);
	}

	function handlePaletteChange(palette: PaletteId) {
		currentPalette = palette;
		prefs.setPaletteId(palette);
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
			if (data.user_id) await activateLoggedInAccount(data.user_id);
		} catch (err: any) {
			authError = t('settings.loginNetworkError');
		} finally {
			isLoggingIn = false;
		}
	}

	async function handleLogout() {
		try {
			const res = await fetch('/api/v1/auth/logout', { method: 'POST' });
			if (!res.ok) throw new Error(`logout ${res.status}`);
		} catch (_) {
			toast.error(t('toast.signOutError'));
			return;
		}
		await activateAccountContext(null);
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
			// Make already-local listening sessions eligible immediately. Without
			// this, Community can read as empty until the next 45-second sync tick.
			if (globalStatsOptIn) await sync.syncNow();
			toast.success(enabled ? t('settings.globalStatsEnabled') : t('settings.globalStatsDisabled'));
		} catch (_) {
			globalStatsOptIn = previous;
			toast.error(t('settings.globalStatsSaveError'));
		} finally {
			isSavingGlobalStatsPreference = false;
		}
	}

	async function handleResetLocalData() {
		if (await confirmDialog.ask(t('settings.confirmReset'))) {
			await resetAllLocalData();
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
			// Both of these are things the listener can act on, so they say what is
			// actually wrong instead of collapsing into the generic read error below.
			if (xmlText.length > MAX_OPML_CHARS) throw new OpmlError(t('settings.opmlTooLarge'));
			const feeds = parseOpmlFeeds(xmlText);
			if (feeds.length === 0) throw new OpmlError(t('settings.opmlNoFeeds'));
			const processableFeeds = feeds.slice(0, MAX_LOCAL_OPML_FEEDS);
			const existing = new Set((await getLocalSubscriptions()).map((sub) => sub.feed_url));
			const imported = processableFeeds.filter((feed) => !existing.has(feed.feed_url));
			const now = Date.now();
			await saveLocalSubscriptions(
				imported.map((feed, index) => ({
					podcast_id: feed.feed_url,
					feed_url: feed.feed_url,
					title: feed.title,
					artwork_url: '',
					added_at: now + index,
					inbox_mode: prefs.defaultInboxMode
				}))
			);
			opmlReport = {
				total_found: feeds.length,
				imported: imported.length,
				skipped: feeds.length - imported.length,
				failures: []
			};

			toast.success(t('settings.opmlImported', { imported: imported.length, skipped: feeds.length - imported.length }));
		} catch (err: any) {
			opmlError = err instanceof OpmlError ? err.message : t('settings.opmlReadError');
		} finally {
			isImportingOpml = false;
			target.value = '';
		}
	}

	/** Carries an already-translated, listener-facing reason. */
	class OpmlError extends Error {}

	function parseOpmlFeeds(xmlText: string): Array<{ feed_url: string; title: string }> {
		const document = new DOMParser().parseFromString(xmlText.replace(/^\uFEFF/, ''), 'application/xml');
		if (document.querySelector('parsererror') || document.documentElement.localName !== 'opml') return [];
		const seen = new Set<string>();
		const feeds = [];
		for (const outline of document.querySelectorAll('outline')) {
			const attributes = Object.fromEntries(
				Array.from(outline.attributes).map((attribute) => [attribute.name.toLowerCase(), attribute.value])
			);
			const feedUrl = (attributes.xmlurl || attributes.url || '').trim();
			if (!feedUrl || seen.has(feedUrl)) continue;
			seen.add(feedUrl);
			feeds.push({
				feed_url: feedUrl,
				title: (attributes.title || attributes.text || feedUrl).trim() || feedUrl
			});
		}
		return feeds;
	}

	const MAX_OPML_CHARS = 5 * 1024 * 1024;
	const MAX_LOCAL_OPML_FEEDS = 5_000;

	// Export is generated on-device from local subscriptions so it works without an
	// account (the server export endpoint only sees account-synced subscriptions).
	async function handleExportOpml() {
		const subs = (await getLocalSubscriptions()).filter((s) => s.feed_url);
		if (subs.length === 0) {
			toast.error(t('settings.opmlNothingToExport'));
			return;
		}
		const blob = new Blob([buildOpml(subs)], { type: 'application/xml' });
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = 'koalacast_subscriptions.opml';
		document.body.appendChild(a);
		a.click();
		a.remove();
		URL.revokeObjectURL(url);
		toast.success(t('settings.opmlExported', { count: subs.length }));
	}

	async function toggleOpmlBackup(enabled: boolean) {
		if (!enabled) {
			await opmlBackup.disable();
			toast.success(t('settings.opmlBackupOff'));
			return;
		}
		if (await opmlBackup.choose()) toast.success(t('settings.opmlBackupOn'));
	}
</script>

<div class="settings-page">
	<div class="settings-head">
		<h1><i class="ph-fill ph-gear-six" aria-hidden="true"></i> {t('settings.title')}</h1>
		<p class="page-sub">{t('settings.subtitle')}</p>
		<p class="settings-status">
			<strong>{authUser ? t('settings.loggedInAs') + ' ' + authUser.username : t('settings.localBrowserMode')}</strong>
			<span>{authUser ? (sync.lastSyncedAt ? new Date(sync.lastSyncedAt).toLocaleTimeString() : t('settings.syncNow')) : t('profileStats.localOnly')}</span>
			<span>{t('settings.globalStatsOptIn')}: {globalStatsOptIn ? t('common.on') : t('common.off')}</span>
		</p>
	</div>
	<nav class="settings-nav" aria-label={t('settings.title')}>
		<a href="#appearance">{t('settings.appearance')}</a>
		<a href="#discovery">{t('quiet.nav.discover')}</a>
		<a href="#playback">{t('settings.playback')}</a>
		<a href="#downloads">{t('settings.downloads')}</a>
		<a href="#privacy">{t('settings.privacy')}</a>
		<a href="#account">{t('settings.accountSync')}</a>
		<a href="#data">{t('settings.dataManagement')}</a>
	</nav>

	<div class="settings-grid">
	<!-- Theme Selection Card -->
	<details class="card" id="appearance" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-palette" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.appearance')}</strong>
				<small>{t('settings.appearanceHint')}</small>
			</span>
			<span class="summary-value">{themeLabel(currentTheme)}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">

		<div class="theme-selector">
			<button
				type="button"
				class="theme-btn"
				class:active={currentTheme === 'system'}
				aria-pressed={currentTheme === 'system'}
				onclick={() => handleThemeChange('system')}
			>
				<i class="ph ph-desktop" aria-hidden="true"></i> {t('settings.themeSystem')}
			</button>

			<button
				type="button"
				class="theme-btn"
				class:active={currentTheme === 'dark'}
				aria-pressed={currentTheme === 'dark'}
				onclick={() => handleThemeChange('dark')}
			>
				<i class="ph ph-moon" aria-hidden="true"></i> {t('settings.themeDark')}
			</button>

			<button
				type="button"
				class="theme-btn"
				class:active={currentTheme === 'light'}
				aria-pressed={currentTheme === 'light'}
				onclick={() => handleThemeChange('light')}
			>
				<i class="ph ph-sun" aria-hidden="true"></i> {t('settings.themeLight')}
			</button>
		</div>

		<div class="palette-heading">
			<h4>{t('settings.colorPalette')}</h4>
			<p class="subtitle">{t('settings.colorPaletteHint')}</p>
		</div>
		<div class="palette-grid" aria-label={t('settings.colorPalette')}>
			{#each COLOR_PALETTES as palette (palette.id)}
				<button
					type="button"
					class="palette-card"
					class:active={currentPalette === palette.id}
					aria-pressed={currentPalette === palette.id}
					onclick={() => handlePaletteChange(palette.id)}
				>
					<span class="palette-swatches" aria-hidden="true">
						{#each palette.swatches as swatch}
							<span style:background={swatch}></span>
						{/each}
					</span>
					<span class="palette-copy">
						<strong>{t(palette.labelKey)}</strong>
						<small>{t(palette.descriptionKey)}</small>
					</span>
					{#if currentPalette === palette.id}
						<i class="ph-fill ph-check-circle palette-check" aria-hidden="true"></i>
					{/if}
				</button>
			{/each}
			</div>

		</div>
	</details>

	<details class="card" id="playback" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-play-circle" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.playback')}</strong>
				<small>{t('settings.playbackHint')}</small>
			</span>
			<span class="summary-value">{prefs.dateFormat === 'relative' ? t('settings.dateRelative') : t('settings.dateAbsolute')}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
			<div class="audio-settings">
			<div class="consent-row">
				<div>
					<h4>{t('settings.volumeBoost')}</h4>
					<p>{t('settings.volumeBoostHint')}</p>
				</div>
				<label class="consent-switch">
					<input
						type="checkbox"
						checked={prefs.volumeBoost}
						onchange={(event) => prefs.setVolumeBoost(event.currentTarget.checked)}
						aria-label={t('settings.volumeBoost')}
					/>
					<span aria-hidden="true"></span>
				</label>
			</div>
			<div class="consent-row">
				<div>
					<h4>{t('settings.skipSilence')}</h4>
					<p>{t('settings.skipSilenceHint')}</p>
				</div>
				<label class="consent-switch">
					<input
						type="checkbox"
						checked={prefs.skipSilence}
						onchange={(event) => prefs.setSkipSilence(event.currentTarget.checked)}
						aria-label={t('settings.skipSilence')}
					/>
					<span aria-hidden="true"></span>
				</label>
			</div>
		</div>
		<h4 class="date-heading">{t('settings.episodeDates')}</h4>
		<div class="theme-selector">
			<button
				type="button"
				class="theme-btn"
				class:active={prefs.dateFormat === 'absolute'}
				aria-pressed={prefs.dateFormat === 'absolute'}
				onclick={() => prefs.setDateFormat('absolute')}
			>
				<i class="ph ph-calendar-blank" aria-hidden="true"></i> {t('settings.dateAbsolute')}
			</button>
			<button
				type="button"
				class="theme-btn"
				class:active={prefs.dateFormat === 'relative'}
				aria-pressed={prefs.dateFormat === 'relative'}
				onclick={() => prefs.setDateFormat('relative')}
			>
				<i class="ph ph-clock-countdown" aria-hidden="true"></i> {t('settings.dateRelative')}
				</button>
			</div>
			<h4 class="date-heading preference-heading">{t('settings.newPodcastEpisodes')}</h4>
			<p class="subtitle">{t('settings.newPodcastEpisodesHint')}</p>
			<div class="theme-selector" role="group" aria-label={t('settings.newPodcastEpisodes')}>
				<button
					type="button"
					class="theme-btn"
					class:active={prefs.defaultInboxMode === 'all'}
					aria-pressed={prefs.defaultInboxMode === 'all'}
					onclick={() => prefs.setDefaultInboxMode('all')}
				>
					<i class="ph ph-stack" aria-hidden="true"></i> {t('settings.allEpisodes')}
				</button>
				<button
					type="button"
					class="theme-btn"
					class:active={prefs.defaultInboxMode === 'latest'}
					aria-pressed={prefs.defaultInboxMode === 'latest'}
					onclick={() => prefs.setDefaultInboxMode('latest')}
				>
					<i class="ph ph-sparkle" aria-hidden="true"></i> {t('settings.latestEpisode')}
				</button>
			</div>
			<!-- Installation itself stays browser-owned so Android's native install
			     promotion is never suppressed by beforeinstallprompt.preventDefault(). -->
			{#if install.installed}
				<h4 class="date-heading preference-heading">{t('settings.installApp')}</h4>
				<p class="subtitle">{t('settings.installAppHint')}</p>
				<p class="subtitle"><i class="ph ph-check-circle" aria-hidden="true"></i> {t('settings.installAppDone')}</p>
			{/if}
			<h4 class="date-heading preference-heading">{t('settings.startScreen')}</h4>
			<p class="subtitle">{t('settings.startScreenHint')}</p>
			<div class="theme-selector" role="group" aria-label={t('settings.startScreen')}>
				{#each [
					{ value: 'discover' as const, icon: 'ph-newspaper', label: t('quiet.nav.discover') },
					{ value: 'inbox' as const, icon: 'ph-tray', label: t('nav.new') },
					{ value: 'library' as const, icon: 'ph-squares-four', label: t('nav.library') }
				] as option}
					<button type="button" class="theme-btn" class:active={prefs.startScreen === option.value} aria-pressed={prefs.startScreen === option.value} onclick={() => prefs.setStartScreen(option.value)}>
						<i class="ph {option.icon}" aria-hidden="true"></i> {option.label}
					</button>
				{/each}
			</div>
			<h4 class="date-heading preference-heading">{t('settings.visualizer')}</h4>
			<p class="subtitle">{t('settings.visualizerHint')}</p>
			<div class="theme-selector" role="group" aria-label={t('settings.visualizer')}>
				{#each [
					{ value: 'off' as const, icon: 'ph-minus', label: t('common.off') },
					{ value: 'level' as const, icon: 'ph-chart-bar', label: t('settings.visualizerLevel') },
					{ value: 'waveform' as const, icon: 'ph-waveform', label: t('settings.visualizerWaveform') },
					{ value: 'bars' as const, icon: 'ph-chart-bar-horizontal', label: t('settings.visualizerBars') },
					{ value: 'pulse' as const, icon: 'ph-circles-three-plus', label: t('settings.visualizerPulse') }
				] as option}
					<button type="button" class="theme-btn" class:active={prefs.visualizer === option.value} aria-pressed={prefs.visualizer === option.value} onclick={() => prefs.setVisualizer(option.value)}>
						<i class="ph {option.icon}" aria-hidden="true"></i> {option.label}
					</button>
				{/each}
			</div>
			<!-- The app shows each style drawn beside its name; a name alone tells
			     nobody what "Pulse" is going to do to their progress bar. There is
			     no audio here, so the component falls back to its canned shape. -->
			{#if prefs.visualizer !== 'off'}
				<div class="visualizer-preview">
					{#if prefs.visualizer === 'level'}
						<!-- Level is drawn by the progress track itself, not by the
						     signal overlay, so it gets its own swollen-bar sample. -->
						<span class="level-preview"></span>
					{:else}
						<VisualizerSignal style={prefs.visualizer} variant="preview" progress={55} level={0.7} />
					{/if}
				</div>
			{/if}
		</div>
	</details>

	<details class="card" id="downloads" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-download-simple" aria-hidden="true"></i></span>
			<span class="summary-copy"><strong>{t('settings.downloads')}</strong><small>{t('settings.downloadsHint')}</small></span>
			<span class="summary-value">{prefs.downloadConcurrency}×</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content download-settings">
			<div class="consent-row">
				<div><h4>{t('settings.wifiOnly')}</h4><p>{t('settings.wifiOnlyHint')}</p></div>
				<label class="consent-switch"><input type="checkbox" checked={prefs.downloadWifiOnly} onchange={(event) => prefs.setDownloadWifiOnly(event.currentTarget.checked)} aria-label={t('settings.wifiOnly')} /><span aria-hidden="true"></span></label>
			</div>
			<label class="select-setting"><span><strong>{t('settings.parallelDownloads')}</strong><small>{t('settings.parallelDownloadsHint')}</small></span><select value={prefs.downloadConcurrency} onchange={(event) => prefs.setDownloadConcurrency(Number(event.currentTarget.value))}>{#each [1, 2, 3, 4] as value}<option value={value}>{value}</option>{/each}</select></label>
			<label class="select-setting"><span><strong>{t('settings.autoDownloadCount')}</strong><small>{t('settings.autoDownloadCountHint')}</small></span><select value={prefs.autoDownloadCount} onchange={(event) => prefs.setAutoDownloadCount(Number(event.currentTarget.value))}>{#each [1, 3, 5, 10] as value}<option value={value}>{value}</option>{/each}</select></label>
			<label class="select-setting"><span><strong>{t('settings.downloadRetention')}</strong><small>{t('settings.downloadRetentionHint')}</small></span><select value={prefs.downloadRetention} onchange={(event) => prefs.setDownloadRetention(event.currentTarget.value as DownloadRetention)}><option value="keep">{t('settings.retentionKeep')}</option><option value="finished">{t('settings.retentionFinished')}</option><option value="7d">7 {t('settings.days')}</option><option value="14d">14 {t('settings.days')}</option><option value="30d">30 {t('settings.days')}</option></select></label>
			<label class="select-setting"><span><strong>{t('settings.downloadBudget')}</strong><small>{t('settings.downloadBudgetHint')}</small></span><select value={prefs.downloadBudgetBytes} onchange={(event) => prefs.setDownloadBudgetBytes(Number(event.currentTarget.value))}><option value={0}>{t('settings.unlimited')}</option><option value={536870912}>512 MB</option><option value={1073741824}>1 GB</option><option value={2147483648}>2 GB</option><option value={5368709120}>5 GB</option></select></label>
			<p class="platform-note">{t('settings.downloadPolicyPlatformHint')}</p>
		</div>
	</details>

	<!-- Interface Language Card — what KoalaCast itself is displayed in. Kept
	     separate from content languages: wanting a German UI and wanting only
	     German podcasts are two different preferences. -->
	<details class="card" id="discovery" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-globe" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.interfaceLanguage')}</strong>
				<small>{t('settings.interfaceLanguageHint')}</small>
			</span>
			<span class="summary-value">{LOCALES.find((locale) => locale.code === prefs.uiLanguage)?.name}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
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
		</div>
	</details>

	<!-- Content Languages Card -->
	<details class="card" id="languages" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-translate" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.contentLanguages')}</strong>
				<small>{t('settings.contentLanguagesHint')}</small>
			</span>
			<span class="summary-value">{prefs.languages.length}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
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
		</div>
	</details>

	<details class="card" id="interests" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-sparkle" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.interests')}</strong>
				<small>{t('settings.interestsHint')}</small>
			</span>
			<span class="summary-value">{prefs.interests.length}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
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
		</div>
	</details>

	<details class="card" id="hidden-podcasts" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-eye-slash" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.hiddenPodcasts')}</strong>
				<small>{t('settings.hiddenPodcastsHint')}</small>
			</span>
			<span class="summary-value">{prefs.hiddenPodcasts.length}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
			{#if prefs.hiddenPodcasts.length === 0}
				<p class="settings-empty">{t('settings.hiddenPodcastsEmpty')}</p>
			{:else}
				<div class="hidden-podcast-list">
					{#each prefs.hiddenPodcasts as podcast (podcast.key)}
						<div>
							<strong>{podcast.title}</strong>
							<button type="button" onclick={() => prefs.unhidePodcast(podcast.key)}>
								{t('settings.showAgain')}
							</button>
						</div>
					{/each}
				</div>
			{/if}
		</div>
	</details>

	<details class="card" id="privacy" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-shield-check" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.privacy')}</strong>
				<small>{t('settings.localBrowserMode')}</small>
			</span>
			<span class="summary-value">{globalStatsOptIn ? t('common.on') : t('common.off')}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
			<div class="privacy-box">
			<h4>{t('settings.localBrowserMode')}</h4>
			<p>{t(authUser ? 'settings.privacyBodySignedIn' : 'settings.privacyBody')}</p>
		</div>
		<div class="consent-row">
			<div><h4>{t('settings.proxyImages')}</h4><p>{t('settings.proxyImagesHint')}</p></div>
			<label class="consent-switch"><input type="checkbox" checked={prefs.proxyImages} onchange={(event) => prefs.setProxyImages(event.currentTarget.checked)} aria-label={t('settings.proxyImages')} /><span aria-hidden="true"></span></label>
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
		</div>
	</details>

	<!-- OPML Import / Export Card -->
	<details class="card" id="opml" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-arrows-down-up" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.opmlTitle')}</strong>
				<small>{t('settings.opmlHint')}</small>
			</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">

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

		<!-- Only where the browser can hold on to a file handle. Elsewhere the manual
		     export above is the whole story, and pretending otherwise would promise a
		     backup that never happens. -->
		{#if opmlBackupSupported()}
			<div class="consent-row">
				<div>
					<h4>{t('settings.opmlBackup')}</h4>
					<p>
						{opmlBackup.enabled && opmlBackup.lastWrittenAt
							? t('settings.opmlBackupStatus', {
									file: opmlBackup.fileName,
									time: new Date(opmlBackup.lastWrittenAt).toLocaleDateString(prefs.uiLanguage)
								})
							: t('settings.opmlBackupHint')}
					</p>
				</div>
				<label class="consent-switch">
					<input
						type="checkbox"
						checked={opmlBackup.enabled}
						onchange={(event) => toggleOpmlBackup(event.currentTarget.checked)}
						aria-label={t('settings.opmlBackup')}
					/>
					<span aria-hidden="true"></span>
				</label>
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
		</div>
	</details>

	<details class="card" id="account" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-user-circle" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.accountSync')}</strong>
				<small>{authUser ? t('settings.syncDescription') : t('settings.signInPrompt')}</small>
			</span>
			<span class="summary-value">{authUser ? authUser.username : t('profileStats.localOnly')}</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
			{#if authUser}
			<p class="subtitle">{t('settings.loggedInAs')} <strong>{authUser.username}</strong> ({authUser.role}). {t('settings.syncDescription')}</p>
			
			<div class="sync-row">
				<div class="sync-info">
					<span class="sync-state">
						<span class="sync-dot {sync.status}"></span>
						{#if sync.status === 'syncing'}
							{t('settings.syncing')}
						{:else if sync.status === 'error'}
							{t('settings.syncError')}
						{:else if sync.lastSyncedAt}
							{t('settings.syncedAt', { time: new Date(sync.lastSyncedAt).toLocaleTimeString() })}
						{:else}
							{t('settings.syncReady')}
						{/if}
					</span>
					<!-- A bare red dot told a listener whose data never arrived nothing
					     at all about why: the network, the session, or a rejected
					     record. The Android client has always shown this. It is a
					     diagnostic, so it keeps the wording it arrived in. -->
					{#if sync.lastError}
						<small class="sync-detail">{sync.lastError}</small>
					{/if}
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
		</div>
	</details>

	<details class="card danger-card" id="data" name="settings-section">
		<summary>
			<span class="summary-icon"><i class="ph ph-database" aria-hidden="true"></i></span>
			<span class="summary-copy">
				<strong>{t('settings.dataManagement')}</strong>
				<small>{t('settings.resetLocalData')}</small>
			</span>
			<i class="ph ph-caret-down summary-caret" aria-hidden="true"></i>
		</summary>
		<div class="card-content">
			<button class="btn-danger" onclick={handleResetLocalData}>{t('settings.resetLocalData')}</button>
		</div>
	</details>
	</div>
</div>

<style>
	.settings-page {
		display: flex;
		flex-direction: column;
		gap: 1.15rem;
		padding: clamp(20px, 2.5vw, 34px);
		max-width: 1280px;
		margin: 0 auto;
	}
	.settings-status { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 12px; color: var(--text-muted); font-size: .8rem; }
	.settings-status strong { color: var(--text-primary); }
	.settings-nav { position: sticky; top: 0; z-index: 10; display: flex; flex-wrap: wrap; gap: 6px; padding: 8px; border: 1px solid var(--border-subtle); border-radius: var(--radius-lg); background: color-mix(in srgb, var(--bg-surface) 94%, transparent); backdrop-filter: blur(12px); }
	.settings-nav a { display: inline-flex; align-items: center; flex: 1 1 auto; justify-content: center; min-height: 44px; padding: 8px 10px; border-radius: var(--radius-control); color: var(--text-secondary); font-size: .78rem; font-weight: 700; }
	.settings-nav a:hover, .settings-nav a:focus-visible { background: var(--accent-wash); color: var(--accent-ink); }
	.card { scroll-margin-top: 64px; }
	.settings-grid {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: .75rem;
		align-items: start;
	}

	.settings-head h1 {
		font-size: clamp(1.6rem, 3vw, 2.1rem);
		font-weight: 800;
		letter-spacing: -0.02em;
		display: flex;
		align-items: center;
		gap: 0.55rem;
	}
	.settings-head h1 :global(.ph-fill) { color: var(--accent-green); }
	.settings-head .page-sub { color: var(--text-muted); font-size: 0.95rem; margin-top: 0.25rem; }

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: var(--radius-lg);
		overflow: clip;
		transition: border-color .2s ease, box-shadow .2s ease, background .2s ease;
	}
	.card:hover {
		border-color: var(--border-ui);
		background: color-mix(in srgb, var(--accent-wash) 18%, var(--bg-surface));
	}
	.card[open] {
		grid-column: 1 / -1;
		border-color: var(--border-ui);
		background: var(--bg-surface);
		box-shadow: 0 14px 34px color-mix(in srgb, var(--ink) 7%, transparent);
	}
	.card summary {
		display: grid;
		grid-template-columns: 40px minmax(0, 1fr) auto 18px;
		align-items: center;
		gap: .8rem;
		min-height: 78px;
		padding: .9rem 1rem;
		cursor: pointer;
		list-style: none;
	}
	.card summary::-webkit-details-marker { display: none; }
	.card summary:focus-visible {
		outline: 3px solid var(--focus-ring);
		outline-offset: -3px;
	}
	.summary-icon {
		display: grid;
		width: 40px;
		height: 40px;
		place-items: center;
		border-radius: var(--radius-lg);
		background: var(--accent-wash);
		color: var(--accent-ink);
		font-size: 1.25rem;
		transition: transform .22s var(--ease-spring, ease), background .2s ease;
	}
	.card:hover .summary-icon { transform: scale(1.05) rotate(-2deg); }
	.card[open] .summary-icon { background: var(--accent-fill); color: var(--accent-on); transform: scale(1) rotate(0); }
	.summary-copy { display: grid; gap: .2rem; min-width: 0; }
	.summary-copy strong { color: var(--text-primary); font-size: .95rem; line-height: 1.2; }
	.summary-copy small {
		overflow: hidden;
		color: var(--text-muted);
		font-size: .78rem;
		line-height: 1.35;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.summary-value {
		max-width: 180px;
		overflow: hidden;
		padding: .28rem .55rem;
		border-radius: 999px;
		background: var(--bg-elevated);
		color: var(--text-secondary);
		font: 700 .69rem/1.2 var(--font-ui);
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.summary-caret {
		color: var(--text-muted);
		transition: transform .22s var(--ease-spring, ease), color .2s ease;
	}
	.card[open] .summary-caret { color: var(--accent-ink); transform: rotate(180deg); }
	.card-content {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		padding: .25rem 1rem 1.25rem 4.8rem;
		border-top: 1px solid var(--border-hair);
		animation: settings-content-in .24s var(--ease-out, ease) both;
	}
	.danger-card[open] { border-color: var(--color-danger-border); }
	@keyframes settings-content-in {
		from { opacity: 0; transform: translateY(-5px); }
		to { opacity: 1; transform: translateY(0); }
	}
	.consent-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1.5rem;
		padding: 1rem;
		border: 1px solid var(--border-subtle);
		background: var(--bg-sunken);
		border-radius: var(--radius-lg);
	}
	.consent-row h4 { margin-bottom: .35rem; }
	.consent-row p { max-width: 60ch; color: var(--text-muted); font-size: .9rem; }
	.consent-row a { display: inline-flex; gap: .3rem; align-items: center; margin-top: .65rem; color: var(--accent-green); font-weight: 700; font-size: .85rem; }
	.download-settings { display: grid; gap: .8rem; }
	/* A fixed slot, so switching style does not make the panel jump. */
	.visualizer-preview {
		position: relative;
		margin-top: 0.75rem;
		height: 34px;
		padding: 0 12px;
		display: flex;
		align-items: center;
		border: 1px solid var(--border-ui);
		border-radius: var(--radius-control);
		background: var(--bg-sunken);
	}

	.level-preview {
		flex: 1;
		height: 7px;
		border-radius: 999px;
		background: linear-gradient(
			to right,
			var(--accent-fill) 0 55%,
			var(--track) 55% 100%
		);
	}

	.select-setting { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: .9rem 1rem; background: var(--bg-elevated); border: 1px solid var(--border-ui); border-radius: var(--radius-lg); }
	.select-setting span { display: grid; gap: .25rem; }
	.select-setting small, .platform-note { color: var(--text-muted); font-size: .84rem; }
	.select-setting select { min-width: 8rem; min-height: 2.75rem; padding: .55rem .75rem; color: var(--text-primary); background: var(--bg-surface); border: 1px solid var(--border-ui); border-radius: var(--radius-control); }
	.platform-note { margin: 0; }
	.consent-switch { display: flex; align-items: center; gap: .55rem; cursor: pointer; white-space: nowrap; }
	.consent-switch input { position: absolute; opacity: 0; pointer-events: none; }
	.consent-switch span { width: 42px; height: 24px; padding: 3px; border-radius: 999px; background: var(--bg-elevated); border: 1px solid var(--border-ui); transition: background .15s; }
	.consent-switch span::after { content: ''; display: block; width: 16px; height: 16px; border-radius: 50%; background: var(--text-muted); transition: transform .15s, background .15s; }
	.consent-switch input:checked + span { background: var(--accent-fill); border-color: var(--accent-fill); }
	.consent-switch input:checked + span::after { transform: translateX(18px); background: var(--accent-on); }
	.consent-switch input:focus-visible + span { outline: 2px solid var(--accent-green); outline-offset: 2px; }
	.consent-switch input:disabled + span { opacity: .55; cursor: wait; }
	.audio-settings { display: grid; gap: .7rem; margin: 1rem 0 1.2rem; }
	.audio-settings .consent-row { background: var(--bg-elevated); border: 1px solid var(--border-ui); border-radius: var(--radius-lg); }
	.date-heading { margin: 0 0 .65rem; }
	.preference-heading { margin-top: 1.25rem; }
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
		border-radius: var(--radius-control);
		width: fit-content;
		margin-top: 0.25rem;
	}

	.theme-btn {
		background: transparent;
		color: var(--text-secondary);
		border: none;
		padding: 0.6rem 1.1rem;
		border-radius: var(--radius-inset);
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

	.palette-heading {
		margin-top: 1.35rem;
	}

	.palette-heading h4 {
		margin-bottom: 0.2rem;
		font-size: 0.95rem;
	}

	.palette-grid {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 0.65rem;
		margin-top: 0.7rem;
	}

	.palette-card {
		position: relative;
		display: grid;
		grid-template-columns: 54px minmax(0, 1fr);
		align-items: center;
		gap: 0.8rem;
		min-height: 76px;
		padding: 0.7rem;
		border: 1px solid var(--border-subtle);
		border-radius: var(--radius-control);
		background: var(--bg-elevated);
		color: var(--text-primary);
		text-align: left;
		transition: var(--transition-smooth);
	}

	.palette-card:hover {
		border-color: var(--border-ui);
		background: color-mix(in srgb, var(--accent-green) 5%, var(--bg-elevated));
	}

	.palette-card.active {
		border-color: var(--accent-green);
		box-shadow: inset 0 0 0 1px var(--accent-green);
	}

	.palette-swatches {
		display: grid;
		grid-template-columns: 1fr 1fr;
		width: 54px;
		height: 54px;
		overflow: hidden;
		border: 1px solid color-mix(in srgb, #fff 18%, transparent);
		border-radius: var(--radius-lg);
	}

	.palette-swatches span {
		min-width: 0;
	}

	.palette-copy {
		display: grid;
		gap: 0.18rem;
		min-width: 0;
		padding-right: 1.1rem;
	}

	.palette-copy strong {
		font: 750 0.9rem/1.2 var(--font-ui);
	}

	.palette-copy small {
		color: var(--text-muted);
		font-size: 0.76rem;
		line-height: 1.3;
	}

	.palette-check {
		position: absolute;
		top: 0.65rem;
		right: 0.65rem;
		color: var(--accent-green);
		font-size: 1rem;
	}

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
		border-radius: var(--radius-control);
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
	.settings-empty { margin: 0; color: var(--ink-4); font-size: 13px; }
	.hidden-podcast-list { display: grid; gap: 8px; }
	.hidden-podcast-list > div { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 48px; padding: 8px 0; border-bottom: 1px solid var(--border-row); }
	.hidden-podcast-list strong { min-width: 0; overflow: hidden; color: var(--ink-2); text-overflow: ellipsis; white-space: nowrap; }
	.hidden-podcast-list button { flex: 0 0 auto; min-height: 44px; padding: 0 12px; border: 1px solid var(--border-ui); border-radius: var(--radius-control); background: transparent; color: var(--ink-3); }

	.language-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
		gap: 0.75rem;
		margin-top: 0.25rem;
	}

	@media (max-width: 840px) {
		.palette-grid {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}

	@media (max-width: 560px) {
		.palette-grid {
			grid-template-columns: 1fr;
		}
	}

	.lang-chip {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		padding: 0.7rem 1rem;
		border-radius: var(--radius-control);
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
		font-family: 'Apple Color Emoji', 'Segoe UI Emoji', sans-serif;
		font-size: 1.25rem;
		line-height: 1;
	}

	.privacy-box {
		background: var(--bg-elevated);
		border-radius: var(--radius-lg);
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

	/*
	 * This page styles bare `<button>` rather than a class, so the values have to
	 * be kept equal to the shared `.btn` in app.css by hand — an element selector
	 * outranks a class one and would otherwise quietly override it. Anything
	 * changed here has to change there too.
	 */
	button, .btn {
		background: var(--accent-green);
		color: var(--accent-button-text);
		border: 1px solid transparent;
		min-height: 44px;
		padding: 0 16px;
		border-radius: var(--radius-control);
		font-family: var(--font-ui);
		font-weight: 700;
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 8px;
		font-size: 0.92rem;
		line-height: 1;
		text-decoration: none;
	}

	.btn-secondary {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border-color: var(--border-ui);
	}

	.btn-danger {
		background: var(--color-danger);
		color: var(--danger-button-text);
		border-color: var(--color-danger);
		width: fit-content;
	}

	.error-banner {
		padding: 0.75rem 1rem;
		background: var(--color-danger-bg);
		color: var(--text-primary);
		border: 1px solid var(--color-danger-border);
		border-radius: var(--radius-lg);
	}

	.report-box {
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: var(--radius-lg);
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
		border-radius: var(--radius-lg);
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
		border-radius: var(--radius-lg);
		padding: 0.75rem 1rem;
		margin: 0.25rem 0 0.5rem;
	}
	.sync-info { display: flex; flex-direction: column; gap: 0.2rem; min-width: 0; }
	.sync-state { font-weight: 600; font-size: 0.9rem; display: inline-flex; align-items: center; gap: 0.5rem; }
	.sync-detail { color: var(--text-secondary); font-size: 0.78rem; overflow-wrap: anywhere; }
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
		border-radius: var(--radius-lg);
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
		border-radius: var(--radius-lg);
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
		border-radius: var(--radius-lg);
	}
	@media (max-width: 900px) {
		.settings-grid { grid-template-columns: 1fr; }
		.card[open] { grid-column: auto; }
	}
	@media (max-width: 620px) {
		.settings-page { padding: 16px 14px 96px; }
		.settings-nav { display: none; }
		.settings-grid { grid-template-columns: 1fr; }
		.card[open] { grid-column: auto; }
		.card summary { grid-template-columns: 36px minmax(0, 1fr) 16px; min-height: 70px; padding: .75rem; }
		.summary-icon { width: 36px; height: 36px; }
		.summary-value { display: none; }
		.card-content { padding: .8rem .75rem 1rem; }
		.consent-row { align-items: flex-start; flex-direction: column; }
		.select-setting { align-items: stretch; flex-direction: column; }
		.select-setting select { width: 100%; }
		.theme-selector { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); width: 100%; }
		.theme-btn { justify-content: center; min-width: 0; padding-inline: .45rem; }
		.palette-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
		.palette-card { grid-template-columns: 38px minmax(0, 1fr); min-height: 62px; }
		.palette-swatches { width: 38px; height: 38px; }
		.palette-copy small { display: none; }
	}
</style>
