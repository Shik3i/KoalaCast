<script lang="ts">
	import { onMount } from 'svelte';
	import { prefs } from '$lib/stores/prefs.svelte';
	import { GENRES, genreLabel } from '$lib/genres';
	import { SUPPORTED_LANGUAGES } from '$lib/data/languages';
	import { t } from '$lib/i18n';
	import { fade, scale } from 'svelte/transition';

	let cardEl: HTMLElement | null = $state(null);
	onMount(() => cardEl?.focus());

	function handleDialogKeydown(event: KeyboardEvent) {
		if (event.key !== 'Tab' || !cardEl) return;
		const controls = [...cardEl.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')]
			.filter((element) => !element.hasAttribute('disabled'));
		if (!controls.length) return;
		const first = controls[0];
		const last = controls[controls.length - 1];
		if (event.shiftKey && document.activeElement === first) {
			event.preventDefault();
			last.focus();
		} else if (!event.shiftKey && document.activeElement === last) {
			event.preventDefault();
			first.focus();
		}
	}
</script>

<svelte:window onkeydown={handleDialogKeydown} />

<div class="ob-overlay" transition:fade={{ duration: 180 }} role="dialog" aria-modal="true" aria-label={t('onboarding.dialogLabel')}>
	<div class="ob-card" bind:this={cardEl} tabindex="-1" transition:scale={{ duration: 240, start: 0.96 }}>
		<span class="ob-badge"><i class="ph-fill ph-sparkle" aria-hidden="true"></i> {t('onboarding.badge')}</span>
		<h2>{t('onboarding.title')}</h2>
		<p>{t('onboarding.intro')}</p>

		<!-- Spoken Languages Section -->
		<div class="ob-section">
			<h3 class="section-title"><i class="ph ph-translate" aria-hidden="true"></i> {t('onboarding.spokenLanguages')}</h3>
			<div class="ob-lang-grid">
				{#each SUPPORTED_LANGUAGES as lang}
					<button
						type="button"
						class="ob-chip lang-chip"
						class:on={prefs.languages.includes(lang.code)}
						aria-pressed={prefs.languages.includes(lang.code)}
						onclick={() => prefs.toggleLanguage(lang.code)}
					>
						<span class="flag-emoji">{lang.flag}</span>
						<span>{lang.name}</span>
					</button>
				{/each}
			</div>
		</div>

		<!-- Topic Interests Section -->
		<div class="ob-section">
			<h3 class="section-title"><i class="ph ph-sparkle" aria-hidden="true"></i> {t('onboarding.topicInterests')}</h3>
			<div class="ob-grid">
				{#each GENRES as g (g.name)}
					<button type="button" class="ob-chip" class:on={prefs.interests.includes(g.name)} aria-pressed={prefs.interests.includes(g.name)} onclick={() => prefs.toggleInterest(g.name)}>
						<i class="ph {g.icon}" aria-hidden="true"></i>
						{genreLabel(g.name)}
					</button>
				{/each}
			</div>
		</div>

		<div class="ob-actions">
			<button type="button" class="ob-skip" onclick={() => prefs.completeOnboarding()}>{t('onboarding.skip')}</button>
			<button type="button" class="ob-done" onclick={() => prefs.completeOnboarding()}>
				{prefs.interests.length > 0
					? t('onboarding.continueWithTopics', { count: prefs.interests.length })
					: t('onboarding.getStarted')}
			</button>
		</div>
	</div>
</div>

<style>
	.ob-overlay {
		position: fixed;
		inset: 0;
		z-index: 300;
		display: grid;
		place-items: center;
		padding: 1.25rem;
		background: color-mix(in srgb, var(--bg-primary) 85%, transparent);
		backdrop-filter: blur(12px);
		overflow-y: auto;
	}
	.ob-card {
		width: min(720px, 100%);
		max-height: 90vh;
		overflow-y: auto;
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 8px;
		box-shadow: var(--shadow-xl);
		padding: 2rem;
	}
	.ob-card:focus { outline: none; }
	.ob-badge {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		background: var(--accent-fill);
		color: var(--accent-on);
		font-size: 0.72rem;
		font-weight: 900;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		padding: 0.25rem 0.7rem;
		border-radius: 4px;
	}
	.ob-card h2 { font-size: clamp(1.5rem, 4vw, 2rem); font-weight: 800; margin: 0.9rem 0 0.4rem; letter-spacing: -0.02em; }
	.ob-card > p { color: var(--text-secondary); line-height: 1.55; margin-bottom: 1.5rem; }

	.ob-section {
		margin-bottom: 1.5rem;
	}

	.section-title {
		font-size: 1rem;
		font-weight: 700;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.4rem;
		margin-bottom: 0.75rem;
	}

	.ob-lang-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
		gap: 0.6rem;
	}

	.ob-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
		gap: 0.6rem;
	}
	.ob-chip {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.65rem 0.9rem;
		border-radius: 6px;
		border: 1.5px solid var(--border-subtle);
		background: var(--bg-elevated);
		color: var(--text-secondary);
		font-weight: 600;
		font-size: 0.9rem;
		cursor: pointer;
		transition: all 0.18s var(--ease-spring, ease);
	}
	.ob-chip :global(.ph) { font-size: 1.2rem; }
	.ob-chip:hover { border-color: var(--accent-green); color: var(--text-primary); }
	.ob-chip.on {
		background: color-mix(in srgb, var(--accent-green) 16%, var(--bg-surface));
		border-color: var(--accent-green);
		color: var(--accent-green);
	}

	.flag-emoji {
		font-family: 'Twemoji Country Flags', var(--font-sans);
		font-size: 1.2rem;
		line-height: 1;
	}

	.ob-actions {
		position: sticky;
		bottom: -2rem;
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 1rem;
		margin-top: 1.75rem;
		padding-top: 1rem;
		border-top: 1px solid var(--border-subtle);
	}
	.ob-skip {
		background: none;
		border: none;
		color: var(--text-muted);
		font-weight: 600;
		font-size: 0.9rem;
		padding: 0.6rem 0.4rem;
		cursor: pointer;
	}
	.ob-skip:hover { color: var(--text-primary); }
	.ob-done {
		background: var(--accent-fill);
		color: var(--accent-on);
		border: none;
		padding: 0.75rem 1.6rem;
		border-radius: 5px;
		font-weight: 700;
		font-size: 0.95rem;
		cursor: pointer;
		transition: filter 0.2s ease, transform 0.15s ease;
	}
	.ob-done:hover { filter: brightness(1.08); transform: translateY(-1px); }
	@media (max-width: 560px) {
		.ob-overlay { padding: 0; place-items: stretch; }
		.ob-card { width: 100%; max-height: 100dvh; border-radius: 0; padding: 1rem; }
		.ob-actions { bottom: -1rem; margin-inline: -1rem; padding: 1rem; background: var(--bg-surface); }
		.ob-lang-grid, .ob-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	}
</style>
