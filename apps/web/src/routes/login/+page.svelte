<script lang="ts">
	import { t } from '$lib/i18n';
	import { goto } from '$app/navigation';
	import { toast } from '$lib/stores/toast.svelte';
	import { sync } from '$lib/stores/sync.svelte';

	let usernameInput = $state('');
	let passwordInput = $state('');
	let isLoggingIn = $state(false);
	let authError = $state('');

	async function handleLogin(e: Event) {
		e.preventDefault();
		if (!usernameInput.trim() || !passwordInput) {
			authError = 'Please enter both username and password.';
			return;
		}

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
				authError = data.error || 'Login failed. Please check your credentials.';
				return;
			}

			toast.success(t('toast.welcomeBack', { username: data.username }));
			if (data.user_id) sync.enable(data.user_id);
			goto('/account');
		} catch (err) {
			authError = 'Network error during login.';
		} finally {
			isLoggingIn = false;
		}
	}
</script>

<svelte:head>
	<title>{t('login.pageTitle')}</title>
	<meta name="description" content={t('login.metaDescription')} />
</svelte:head>

<div class="auth-container">
	<div class="auth-card">
		<header class="auth-header">
			<div class="auth-icon">
				<i class="ph ph-user-circle" aria-hidden="true"></i>
			</div>
			<h1>{t('login.title')}</h1>
			<p class="subtitle">{t('login.subtitle')}</p>
		</header>

		{#if authError}
			<div class="error-banner" role="alert">
				<i class="ph ph-warning-circle" aria-hidden="true"></i>
				{authError}
			</div>
		{/if}

		<form onsubmit={handleLogin} class="auth-form">
			<div class="form-group">
				<label for="username">{t('login.username')}</label>
				<input
					id="username"
					type="text"
					bind:value={usernameInput}
					placeholder={t('login.usernamePlaceholder')}
					required
					autocomplete="username"
				/>
			</div>

			<div class="form-group">
				<label for="password">{t('login.password')}</label>
				<input
					id="password"
					type="password"
					bind:value={passwordInput}
					placeholder={t('login.passwordPlaceholder')}
					required
					autocomplete="current-password"
				/>
			</div>

			<button type="submit" class="btn btn-primary" disabled={isLoggingIn}>
				{#if isLoggingIn}
					<i class="ph ph-spinner spinner" aria-hidden="true"></i> {t('login.signingIn')}
				{:else}
					<i class="ph ph-sign-in" aria-hidden="true"></i> {t('login.title')}
				{/if}
			</button>
		</form>

		<footer class="auth-footer">
			<p>{t('login.noAccount')}</p>
			<a href="/register" class="link-register">
				{t('login.createAccount')} <i class="ph ph-arrow-right" aria-hidden="true"></i>
			</a>
		</footer>
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

	.link-register {
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
