<script lang="ts">
	import { clearAllLocalData } from '$lib/idb/db';

	let usernameInput = $state('');
	let passwordInput = $state('');
	let isRegistering = $state(false);
	let isLoggingIn = $state(false);
	let authUser = $state<any>(null);
	let recoveryCodeDisplay = $state('');
	let authError = $state('');
	let sessions = $state<any[]>([]);

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
			alert('Account created! Please save your recovery code carefully.');
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
			loadActiveSessions();
		} catch (err: any) {
			authError = 'Network error during login.';
		} finally {
			isLoggingIn = false;
		}
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
			alert('Local data reset successfully.');
		}
	}
</script>

<div class="settings-page">
	<h2>Settings & Account Management</h2>

	<section class="card">
		<h3>Privacy & Mode Explanation</h3>
		<div class="privacy-box">
			<h4>Local Browser Mode</h4>
			<p>Your subscriptions, queue, and listening history stay in this browser. KoalaCast contacts the server to search podcasts and retrieve RSS metadata, but anonymous listening activity is not stored on the server.</p>
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
			<button onclick={() => location.reload()}>Sign Out</button>
		</section>
	{/if}

	<section class="card">
		<h3>Data Portability & Local Reset</h3>
		<div class="btn-group">
			<a href="/api/v1/opml/export" class="btn">Export OPML</a>
			<button class="btn-danger" onclick={handleResetLocalData}>Reset Local Browser Data</button>
		</div>
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
		border-radius: 8px;
		padding: 1.5rem;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.subtitle {
		color: var(--text-secondary);
		font-size: 0.9rem;
	}

	.privacy-box {
		background: var(--bg-elevated);
		border-radius: 6px;
		padding: 1rem;
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
		gap: 0.25rem;
	}

	.form-group input {
		padding: 0.65rem 1rem;
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		background: var(--bg-primary);
		color: var(--text-primary);
	}

	.btn-group {
		display: flex;
		gap: 1rem;
	}

	button, .btn {
		background: var(--accent-green);
		color: white;
		border: none;
		padding: 0.65rem 1.25rem;
		border-radius: 6px;
		font-weight: 600;
		cursor: pointer;
	}

	.btn-secondary {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
	}

	.btn-danger {
		background: #d90429;
	}

	.error-banner {
		padding: 0.75rem;
		background: #f8d7da;
		color: #721c24;
		border-radius: 6px;
	}

	.recovery-box {
		background: #fff3cd;
		color: #856404;
		border: 1px solid #ffeeba;
		border-radius: 6px;
		padding: 1rem;
	}

	.code {
		font-family: monospace;
		font-size: 1.25rem;
		font-weight: 700;
		letter-spacing: 0.1em;
		margin-top: 0.5rem;
		background: white;
		padding: 0.5rem;
		border-radius: 4px;
	}
</style>
