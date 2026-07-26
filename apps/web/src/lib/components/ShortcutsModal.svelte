<script lang="ts">
	import { t } from '$lib/i18n';
	import { KEYBOARD_SHORTCUTS } from '$lib/data/shortcuts';

	let { show = $bindable(false) } = $props();

	function close() {
		show = false;
	}

	function handleOverlayKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') close();
	}

	function handleOverlayClick(e: MouseEvent) {
		if (e.target === e.currentTarget) close();
	}
</script>

{#if show}
	<div
		class="modal-overlay"
		onclick={handleOverlayClick}
		onkeydown={handleOverlayKeydown}
		role="presentation"
	>
		<div
			class="modal-content"
			role="dialog"
			aria-modal="true"
			aria-label={t('shortcuts.title')}
			tabindex="-1"
		>
			<h3>{t('shortcuts.title')}</h3>
			<ul>
				{#each KEYBOARD_SHORTCUTS as shortcut}
					<li><kbd>{shortcut.key}</kbd> {t(shortcut.descriptionKey)}</li>
				{/each}
			</ul>
			<button type="button" class="btn-close" onclick={close}>{t('common.close')}</button>
		</div>
	</div>
{/if}

<style>
	.modal-overlay {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.65);
		backdrop-filter: blur(8px);
		z-index: 200;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 1rem;
	}

	.modal-content {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 16px;
		padding: 1.75rem;
		max-width: 400px;
		width: 100%;
		box-shadow: 0 20px 40px rgba(0, 0, 0, 0.4);
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	h3 {
		font-size: 1.2rem;
		font-weight: 700;
		color: var(--text-primary);
		margin: 0;
	}

	ul {
		list-style: none;
		padding: 0;
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	li {
		display: flex;
		align-items: center;
		gap: 1rem;
		color: var(--text-secondary);
		font-size: 0.95rem;
	}

	kbd {
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		border-radius: 6px;
		padding: 0.2rem 0.55rem;
		font-family: inherit;
		font-size: 0.85rem;
		font-weight: 700;
		color: var(--accent-green);
		min-width: 2.2rem;
		text-align: center;
	}

	.btn-close {
		align-self: flex-end;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
		color: var(--text-primary);
		padding: 0.5rem 1.2rem;
		border-radius: 10px;
		font-weight: 600;
		cursor: pointer;
		transition: var(--transition-smooth);
	}

	.btn-close:hover {
		background: var(--accent-green);
		color: var(--bg-primary);
		border-color: var(--accent-green);
	}
</style>
