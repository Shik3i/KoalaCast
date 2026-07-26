<script lang="ts">
	import { goto } from '$app/navigation';
	import { t } from '$lib/i18n';

	let username = $state('');
	let recoveryCode = $state('');
	let newPassword = $state('');
	let busy = $state(false);
	let error = $state('');
	let newRecoveryCode = $state('');

	async function recover(event: SubmitEvent) {
		event.preventDefault();
		busy = true;
		error = '';
		try {
			const response = await fetch('/api/v1/auth/recovery/verify', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ username, recovery_code: recoveryCode, new_password: newPassword })
			});
			const data = await response.json();
			if (!response.ok) throw new Error(data.error || t('recover.error'));
			newRecoveryCode = data.new_recovery_code;
		} catch (reason) {
			error = reason instanceof Error ? reason.message : t('recover.error');
		} finally {
			busy = false;
		}
	}
</script>

<div class="recover-page">
	<section>
		<i class="ph ph-key" aria-hidden="true"></i>
		<h1>{t('recover.title')}</h1>
		<p>{t('recover.subtitle')}</p>
		{#if newRecoveryCode}
			<div class="success" role="status">
				<strong>{t('recover.success')}</strong>
				<code>{newRecoveryCode}</code>
				<p>{t('recover.saveCode')}</p>
				<button onclick={() => goto('/login')}>{t('login.title')}</button>
			</div>
		{:else}
			{#if error}<p class="error" role="alert">{error}</p>{/if}
			<form onsubmit={recover}>
				<label>{t('login.username')}<input bind:value={username} autocomplete="username" required /></label>
				<label>{t('recover.code')}<input bind:value={recoveryCode} autocomplete="one-time-code" required /></label>
				<label>{t('recover.newPassword')}<input bind:value={newPassword} type="password" autocomplete="new-password" minlength="8" required /></label>
				<button disabled={busy}>{busy ? t('common.loading') : t('recover.submit')}</button>
			</form>
		{/if}
	</section>
</div>

<style>
	.recover-page { display: grid; place-items: start center; min-height: 70vh; padding: 32px 16px; }
	.recover-page > section { display: grid; gap: 14px; width: min(460px, 100%); padding: 24px; border: 1px solid var(--border-subtle); border-radius: 8px; background: var(--bg-surface); }
	.recover-page > section > i { color: var(--accent-green); font-size: 30px; }
	.recover-page p { color: var(--text-secondary); line-height: 1.5; }
	form, label, .success { display: grid; gap: 8px; }
	form { gap: 14px; }
	input { min-height: 44px; padding: 0 12px; border: 1px solid var(--border-ui); border-radius: 5px; background: var(--bg-elevated); color: var(--text-primary); }
	button { min-height: 44px; padding: 0 14px; border: 0; border-radius: 5px; background: var(--accent-fill); color: var(--accent-on); font-weight: 700; }
	.error { color: var(--color-danger) !important; }
	code { padding: 12px; overflow-wrap: anywhere; border: 1px solid var(--border-ui); border-radius: 5px; background: var(--bg-elevated); }
</style>
