<script lang="ts">
	import { goto } from '$app/navigation';
	import { toast } from '$lib/stores/toast.svelte';

	let usernameInput = $state('');
	let passwordInput = $state('');
	let isRegistering = $state(false);
	let authError = $state('');
	let recoveryCodeDisplay = $state('');

	async function handleRegister(e: Event) {
		e.preventDefault();
		if (!usernameInput.trim() || !passwordInput) {
			authError = 'Please enter both username and password.';
			return;
		}

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
			toast.success('Account created successfully!');
		} catch (err) {
			authError = 'Network error during registration.';
		} finally {
			isRegistering = false;
		}
	}

	function copyRecoveryCode() {
		navigator.clipboard.writeText(recoveryCodeDisplay);
		toast.success('Recovery code copied to clipboard!');
	}
</script>

<svelte:head>
	<title>Register Account — KoalaCast</title>
	<meta name="description" content="Create a new KoalaCast account to enable seamless cloud synchronization." />
</svelte:head>

<div class="auth-container">
	<div class="auth-card">
		{#if recoveryCodeDisplay}
			<header class="auth-header">
				<div class="auth-icon success">
					<i class="ph ph-shield-check" aria-hidden="true"></i>
				</div>
				<h1>Account Created!</h1>
				<p class="subtitle">Please save your one-time account recovery code. You will need it if you ever lose your password.</p>
			</header>

			<div class="recovery-box">
				<label for="recovery-code-val">Your Account Recovery Code</label>
				<div class="code-row">
					<code id="recovery-code-val">{recoveryCodeDisplay}</code>
					<button type="button" class="btn-copy" onclick={copyRecoveryCode} title="Copy code">
						<i class="ph ph-copy" aria-hidden="true"></i> Copy
					</button>
				</div>
			</div>

			<button type="button" class="btn btn-primary" onclick={() => goto('/login')}>
				Proceed to Sign In <i class="ph ph-arrow-right" aria-hidden="true"></i>
			</button>
		{:else}
			<header class="auth-header">
				<div class="auth-icon">
					<i class="ph ph-user-plus" aria-hidden="true"></i>
				</div>
				<h1>Create Account</h1>
				<p class="subtitle">Create a KoalaCast account to sync your subscriptions across devices.</p>
			</header>

			{#if authError}
				<div class="error-banner" role="alert">
					<i class="ph ph-warning-circle" aria-hidden="true"></i>
					{authError}
				</div>
			{/if}

			<form onsubmit={handleRegister} class="auth-form">
				<div class="form-group">
					<label for="reg-username">Username</label>
					<input
						id="reg-username"
						type="text"
						bind:value={usernameInput}
						placeholder="Choose a username"
						required
						autocomplete="username"
					/>
				</div>

				<div class="form-group">
					<label for="reg-password">Password</label>
					<input
						id="reg-password"
						type="password"
						bind:value={passwordInput}
						placeholder="Create a strong password"
						required
						autocomplete="new-password"
					/>
				</div>

				<button type="submit" class="btn btn-primary" disabled={isRegistering}>
					{#if isRegistering}
						<i class="ph ph-spinner spinner" aria-hidden="true"></i> Creating account...
					{:else}
						<i class="ph ph-user-plus" aria-hidden="true"></i> Register Account
					{/if}
				</button>
			</form>

			<footer class="auth-footer">
				<p>Already have an account?</p>
				<a href="/login" class="link-login">
					Sign in to your account <i class="ph ph-arrow-right" aria-hidden="true"></i>
				</a>
			</footer>
		{/if}
	</div>
</div>

<style>
	.auth-container {
		max-width: 440px;
		margin: 2rem auto;
		width: 100%;
	}

	.auth-card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 20px;
		padding: 2.25rem;
		box-shadow: 0 16px 40px rgba(0, 0, 0, 0.2);
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.auth-header {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: 0.5rem;
	}

	.auth-icon {
		width: 3.5rem;
		height: 3.5rem;
		border-radius: 16px;
		background: color-mix(in srgb, var(--accent-green) 15%, transparent);
		color: var(--accent-green);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 2.2rem;
		margin-bottom: 0.25rem;
	}

	.auth-icon.success {
		background: color-mix(in srgb, var(--accent-green) 20%, transparent);
	}

	h1 {
		font-size: 1.75rem;
		font-weight: 800;
		color: var(--text-primary);
		letter-spacing: -0.02em;
	}

	.subtitle {
		color: var(--text-secondary);
		font-size: 0.92rem;
		line-height: 1.5;
	}

	.error-banner {
		background: color-mix(in srgb, #ef4444 15%, transparent);
		border: 1px solid color-mix(in srgb, #ef4444 35%, transparent);
		color: #f87171;
		padding: 0.75rem 1rem;
		border-radius: 12px;
		font-size: 0.9rem;
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	.auth-form {
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	.form-group {
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
	}

	label {
		font-size: 0.88rem;
		font-weight: 600;
		color: var(--text-secondary);
	}

	input {
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: 12px;
		padding: 0.75rem 1rem;
		color: var(--text-primary);
		font-size: 0.95rem;
		transition: border-color 0.2s ease;
	}

	input:focus {
		outline: none;
		border-color: var(--accent-green);
	}

	.recovery-box {
		background: var(--bg-elevated);
		border: 1px dashed var(--accent-green);
		border-radius: 14px;
		padding: 1.25rem;
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
	}

	.recovery-box label {
		font-size: 0.8rem;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--accent-green);
	}

	.code-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
	}

	code {
		font-family: monospace;
		font-size: 1.05rem;
		font-weight: 700;
		color: var(--text-primary);
		word-break: break-all;
	}

	.btn-copy {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		color: var(--text-primary);
		padding: 0.4rem 0.8rem;
		border-radius: 8px;
		font-size: 0.85rem;
		font-weight: 600;
		cursor: pointer;
		display: flex;
		align-items: center;
		gap: 0.35rem;
		transition: all 0.2s ease;
	}

	.btn-copy:hover {
		background: var(--accent-green);
		color: var(--bg-primary);
		border-color: var(--accent-green);
	}

	.btn {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
		padding: 0.85rem 1.5rem;
		border-radius: 12px;
		font-weight: 700;
		font-size: 0.98rem;
		cursor: pointer;
		border: none;
		transition: all 0.2s ease;
		margin-top: 0.5rem;
	}

	.btn-primary {
		background: var(--accent-green);
		color: var(--bg-primary);
	}

	.btn-primary:hover:not(:disabled) {
		transform: translateY(-1px);
		filter: brightness(1.1);
	}

	.btn:disabled {
		opacity: 0.65;
		cursor: not-allowed;
	}

	.spinner {
		animation: spin 1s linear infinite;
	}

	@keyframes spin {
		100% {
			transform: rotate(360deg);
		}
	}

	.auth-footer {
		border-top: 1px solid var(--border-subtle);
		padding-top: 1.25rem;
		text-align: center;
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
		font-size: 0.9rem;
		color: var(--text-muted);
	}

	.link-login {
		color: var(--accent-green);
		font-weight: 600;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 0.35rem;

		&:hover {
			text-decoration: underline;
		}
	}
</style>
