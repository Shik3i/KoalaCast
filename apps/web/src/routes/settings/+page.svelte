<script lang="ts">
	import { onMount } from 'svelte';
	import { clearAllLocalData } from '$lib/idb/db';
	import { getStoredTheme, setTheme, type ThemeMode } from '$lib/theme';
	import { toast } from '$lib/stores/toast.svelte';

	let usernameInput = $state('');
	let passwordInput = $state('');
	let isRegistering = $state(false);
	let isLoggingIn = $state(false);
	let authUser = $state<any>(null);
	let recoveryCodeDisplay = $state('');
	let authError = $state('');
	let sessions = $state<any[]>([]);

	// Theme state
	let currentTheme = $state<ThemeMode>('system');

	// OPML Import States
	let isImportingOpml = $state(false);
	let opmlReport = $state<any>(null);
	let opmlError = $state('');

	onMount(() => {
		currentTheme = getStoredTheme();
	});

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
				authError = data.error || 'Registration failed.';
				return;
			}

			recoveryCodeDisplay = data.recovery_code;
			toast.success('Account created — save your recovery code below.');
		} catch (err: any) {
			authError = 'Network error during registration.';
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
				authError = data.error || 'Login failed.';
				return;
			}

			authUser = data;
			toast.success(`Welcome back, ${data.username}`);
			loadActiveSessions();
		} catch (err: any) {
			authError = 'Network error during login.';
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
		authUser = null;
		sessions = [];
		usernameInput = '';
		passwordInput = '';
		toast.success('Signed out.');
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

	async function handleResetLocalData() {
		if (confirm('Clear all local browser subscriptions and listening history? This action cannot be undone.')) {
			await clearAllLocalData();
			toast.success('Local data reset.');
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
				opmlError = report.error || 'Failed to import OPML file.';
				return;
			}

			opmlReport = report;
			toast.success(`OPML imported — ${report.imported} added, ${report.skipped} skipped.`);
		} catch (err: any) {
			opmlError = 'Error reading or processing OPML XML file.';
		} finally {
			isImportingOpml = false;
			target.value = '';
		}
	}
</script>

<div class="settings-page">
	<h2>Settings & Configuration</h2>

	<!-- Theme Selection Card -->
	<section class="card">
		<h3>Appearance & Theme</h3>
		<p class="subtitle">Select your preferred color theme or automatically follow your operating system settings.</p>

		<div class="theme-selector">
			<button
				class="theme-btn"
				class:active={currentTheme === 'system'}
				onclick={() => handleThemeChange('system')}
			>
				<i class="ph ph-desktop" aria-hidden="true"></i> System (Auto)
			</button>

			<button
				class="theme-btn"
				class:active={currentTheme === 'dark'}
				onclick={() => handleThemeChange('dark')}
			>
				<i class="ph ph-moon" aria-hidden="true"></i> Dark
			</button>

			<button
				class="theme-btn"
				class:active={currentTheme === 'light'}
				onclick={() => handleThemeChange('light')}
			>
				<i class="ph ph-sun" aria-hidden="true"></i> Light
			</button>
		</div>
	</section>

	<section class="card">
		<h3>Privacy & Mode Explanation</h3>
		<div class="privacy-box">
			<h4>Local Browser Mode</h4>
			<p>Your subscriptions, queue, and listening history stay in this browser. KoalaCast contacts the server to search podcasts and retrieve RSS metadata, but anonymous listening activity is not stored on the server.</p>
		</div>
	</section>

	<!-- OPML Import / Export Card -->
	<section class="card">
		<h3>OPML Import & Export</h3>
		<p class="subtitle">Import subscriptions from Pocket Casts, Apple Podcasts, AntennaPod, or Overcast XML files.</p>

		{#if opmlError}
			<div class="error-banner">{opmlError}</div>
		{/if}

		{#if opmlReport}
			<div class="report-box">
				<h4>Import Summary</h4>
				<ul>
					<li><strong>Total Found:</strong> {opmlReport.total_found}</li>
					<li><strong>Successfully Imported:</strong> {opmlReport.imported}</li>
					<li><strong>Skipped / Duplicates:</strong> {opmlReport.skipped}</li>
				</ul>
			</div>
		{/if}

		<div class="opml-actions">
			<label class="btn btn-import">
				<i class="ph ph-upload-simple" aria-hidden="true"></i>
				{isImportingOpml ? 'Importing OPML...' : 'Upload & Import OPML File'}
				<input type="file" accept=".opml,.xml" onchange={handleOpmlFileUpload} disabled={isImportingOpml} hidden />
			</label>

			<a href="/api/v1/opml/export" class="btn btn-secondary" target="_blank">
				<i class="ph ph-download-simple" aria-hidden="true"></i> Export OPML
			</a>
		</div>
	</section>

	{#if !authUser}
		<section class="card">
			<h3>Account Sign In / Registration</h3>
			<p class="subtitle">Accounts allow you to synchronize subscriptions and progress across devices without email or tracking.</p>

			{#if authError}
				<div class="error-banner">{authError}</div>
			{/if}

			{#if recoveryCodeDisplay}
				<div class="recovery-box">
					<h4>⚠️ Save Your Recovery Code</h4>
					<p>Loss of both password and recovery code makes account recovery impossible.</p>

					<div class="code">{recoveryCodeDisplay}</div>
				</div>
			{/if}

			<form class="auth-form">
				<div class="form-group">
					<label for="username">Username</label>
					<input id="username" type="text" bind:value={usernameInput} placeholder="Username" required />
				</div>
				<div class="form-group">
					<label for="password">Password</label>
					<input id="password" type="password" bind:value={passwordInput} placeholder="Password" required />
				</div>
				<div class="btn-group">
					<button type="button" onclick={handleLogin} disabled={isLoggingIn}>Sign In</button>
					<button type="button" class="btn-secondary" onclick={handleRegister} disabled={isRegistering}>Create Account</button>
				</div>
			</form>
		</section>
	{:else}
		<section class="card">
			<h3>Active Account</h3>
			<p>Logged in as <strong>{authUser.username}</strong> ({authUser.role})</p>
			<button onclick={handleLogout}>Sign Out</button>
		</section>
	{/if}

	<section class="card">
		<h3>Data Management</h3>
		<button class="btn-danger" onclick={handleResetLocalData}>Reset Local Browser Data</button>
	</section>
</div>

<style>
	.settings-page {
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		padding: 1.75rem;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.subtitle {
		color: var(--text-secondary);
		font-size: 0.95rem;
	}

	.theme-selector {
		display: flex;
		gap: 1rem;
		flex-wrap: wrap;
		margin-top: 0.5rem;
	}

	.theme-btn {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
		padding: 0.75rem 1.25rem;
		border-radius: 8px;
		font-weight: 700;
		font-size: 0.95rem;
		display: flex;
		align-items: center;
		gap: 0.5rem;
		transition: all 0.2s ease;
	}

	.theme-btn:hover {
		border-color: var(--accent-green);
	}

	.theme-btn.active {
		background: var(--accent-green);
		color: white;
		border-color: var(--accent-green);
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

	.form-group input {
		padding: 0.65rem 1rem;
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		background: var(--bg-primary);
		color: var(--text-primary);
	}

	.opml-actions, .btn-group {
		display: flex;
		gap: 1rem;
		flex-wrap: wrap;
	}

	button, .btn {
		background: var(--accent-green);
		color: white;
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
</style>
