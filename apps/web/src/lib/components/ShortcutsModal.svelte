<script lang="ts">
	import { t } from '$lib/i18n';
	import { KEYBOARD_SHORTCUTS } from '$lib/data/shortcuts';

	let { show = $bindable(false) } = $props();
	let modalContent: HTMLDivElement | null = $state(null);
	let closeButton: HTMLButtonElement | null = $state(null);

	$effect(() => {
		if (!show) return;
		const returnFocus = document.activeElement as HTMLElement | null;
		setTimeout(() => closeButton?.focus());
		return () => returnFocus?.isConnected && returnFocus.focus();
	});

	function close() {
		show = false;
	}

	function handleOverlayKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			e.preventDefault();
			close();
			return;
		}
		if (e.key !== 'Tab' || !modalContent) return;
		const controls = [...modalContent.querySelectorAll<HTMLElement>('button, [href], [tabindex]:not([tabindex="-1"])')]
			.filter((element) => !element.hasAttribute('disabled'));
		if (!controls.length) return;
		const first = controls[0];
		const last = controls[controls.length - 1];
		if (e.shiftKey && document.activeElement === first) {
			e.preventDefault();
			last.focus();
		} else if (!e.shiftKey && document.activeElement === last) {
			e.preventDefault();
			first.focus();
		}
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
			bind:this={modalContent}
			class="modal-content"
			role="dialog"
			aria-modal="true"
			aria-labelledby="shortcuts-title"
			tabindex="-1"
		>
			<h2 id="shortcuts-title">{t('shortcuts.title')}</h2>
			<ul>
				{#each KEYBOARD_SHORTCUTS as shortcut}
					<li><kbd>{shortcut.key}</kbd> {t(shortcut.descriptionKey)}</li>
				{/each}
			</ul>
			<button bind:this={closeButton} type="button" class="btn-close" onclick={close}>{t('common.close')}</button>
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

	h2 {
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
		min-height: 44px;
	}

	.btn-close:hover {
		background: var(--accent-green);
		color: var(--bg-primary);
		border-color: var(--accent-green);
	}
</style>
