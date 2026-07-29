<script lang="ts">
	import { t } from '$lib/i18n';
	import { toast } from '$lib/stores/toast.svelte';
	import { flip } from 'svelte/animate';
	import { fly } from 'svelte/transition';

	const icons: Record<string, string> = {
		info: 'ph-info',
		success: 'ph-check-circle',
		error: 'ph-warning-circle'
	};
</script>

<div class="toast-stack" aria-label={t('common.notifications')}>
	{#each toast.items as item (item.id)}
		<div
			class="toast {item.type}"
			role={item.type === 'error' ? 'alert' : 'status'}
			aria-live={item.type === 'error' ? 'assertive' : 'polite'}
			aria-atomic="true"
			animate:flip={{ duration: 260 }}
			in:fly={{ y: 16, duration: 260 }}
			out:fly={{ y: 10, duration: 200 }}
		>
			<i class="ph-fill {icons[item.type]}" aria-hidden="true"></i>
			<span>{item.message}</span>
			<button onclick={() => toast.dismiss(item.id)} aria-label={t('common.dismissNotification')} title={t('common.dismissNotification')}>
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		</div>
	{/each}
</div>

<style>
	.toast-stack {
		position: fixed;
		top: 1rem;
		right: 1rem;
		z-index: 300;
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
		pointer-events: none;
		max-width: min(92vw, 380px);
	}

	.toast {
		pointer-events: auto;
		display: flex;
		align-items: center;
		gap: 0.65rem;
		padding: 0.75rem 0.9rem;
		border-radius: 14px;
		background: color-mix(in srgb, var(--bg-surface) 92%, transparent);
		border: 1px solid var(--border-subtle);
		box-shadow: var(--shadow-lg);
		backdrop-filter: blur(14px) saturate(140%);
		-webkit-backdrop-filter: blur(14px) saturate(140%);
		color: var(--text-primary);
		font-size: 0.9rem;
		font-weight: 500;
	}

	.toast > span {
		flex: 1;
		min-width: 0;
	}

	.toast > i {
		font-size: 1.2rem;
		flex-shrink: 0;
	}

	.toast.success > i:first-child {
		color: var(--accent-green-hover);
	}
	.toast.error > i:first-child {
		color: var(--color-danger, #e5484d);
	}
	.toast.info > i:first-child {
		color: var(--focus-ring);
	}

	.toast button {
		width: 44px;
		height: 44px;
		background: none;
		border: none;
		color: var(--text-muted);
		display: grid;
		place-items: center;
		padding: 0;
		border-radius: 6px;
		flex-shrink: 0;
	}
	.toast button:hover {
		color: var(--text-primary);
		background: var(--bg-elevated);
	}
</style>
