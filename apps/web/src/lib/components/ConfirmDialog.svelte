<script lang="ts">
	import { confirmDialog } from '$lib/stores/confirm.svelte';
	import { t } from '$lib/i18n';
	let confirmButton: HTMLButtonElement | null = $state(null);
	$effect(() => {
		if (confirmDialog.request) setTimeout(() => confirmButton?.focus());
	});
</script>

<svelte:window onkeydown={(event) => event.key === 'Escape' && confirmDialog.request && confirmDialog.finish(false)} />

{#if confirmDialog.request}
	<div class="confirm-overlay" role="presentation" onclick={(event) => event.target === event.currentTarget && confirmDialog.finish(false)}>
		<div class="confirm-card" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-message">
			<i class="ph ph-warning" aria-hidden="true"></i>
			<h2 id="confirm-title">{t('common.confirm')}</h2>
			<p id="confirm-message">{confirmDialog.request.message}</p>
			<div>
				<button type="button" class="cancel" onclick={() => confirmDialog.finish(false)}>{t('common.cancel')}</button>
				<button bind:this={confirmButton} type="button" class="danger" onclick={() => confirmDialog.finish(true)}>{t('common.confirm')}</button>
			</div>
		</div>
	</div>
{/if}

<style>
	.confirm-overlay { position: fixed; inset: 0; z-index: 500; display: grid; place-items: center; padding: 16px; background: rgba(3, 8, 5, .72); backdrop-filter: blur(8px); }
	.confirm-card { display: grid; gap: 12px; width: min(440px, 100%); padding: 22px; border: 1px solid var(--border-ui); border-radius: 8px; background: var(--bg-surface); color: var(--text-primary); }
	.confirm-card > i { color: var(--color-danger); font-size: 26px; }
	.confirm-card h2 { font-size: 20px; }
	.confirm-card p { color: var(--text-secondary); line-height: 1.5; }
	.confirm-card div { display: flex; justify-content: flex-end; gap: 8px; }
	.confirm-card button { min-height: 44px; padding: 0 14px; border: 1px solid var(--border-ui); border-radius: 5px; font-weight: 700; }
	.cancel { background: transparent; color: var(--text-primary); }
	.danger { border-color: var(--color-danger-border) !important; background: var(--color-danger); color: #fff; }
</style>
