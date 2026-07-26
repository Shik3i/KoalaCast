<script lang="ts">
	import { t } from '$lib/i18n';
	const links = [
		{ href: '/profile', icon: 'ph-chart-bar', label: 'quiet.nav.profile' as const },
		{ href: '/global-stats', icon: 'ph-globe-hemisphere-west', label: 'globalStats.nav' as const },
		{ href: '/settings', icon: 'ph-gear', label: 'quiet.nav.settings' as const },
		{ href: '/account', icon: 'ph-user-circle', label: 'nav.account' as const },
		{ href: '/privacy', icon: 'ph-shield-check', label: 'footer.privacy' as const }
	];
</script>

<div class="more-page">
	<header><h1>{t('nav.more')}</h1><p>{t('nav.moreHint')}</p></header>
	<nav aria-label={t('nav.more')}>
		{#each links as link}
			<a href={link.href}><i class="ph {link.icon}" aria-hidden="true"></i><span>{t(link.label)}</span><i class="ph ph-caret-right" aria-hidden="true"></i></a>
		{/each}
		<a href="https://koalastuff.net/legal" target="_blank" rel="noopener noreferrer"><i class="ph ph-scales" aria-hidden="true"></i><span>{t('footer.legalNotice')}</span><i class="ph ph-arrow-square-out" aria-hidden="true"></i></a>
	</nav>
</div>

<style>
	.more-page { display: grid; gap: 20px; padding: 24px 22px 36px; }
	header h1 { font-size: 30px; }
	header p { margin-top: 5px; color: var(--ink-4); }
	nav { display: grid; border-top: 1px solid var(--border-row); }
	a { display: grid; grid-template-columns: 28px minmax(0, 1fr) 20px; align-items: center; gap: 10px; min-height: 56px; border-bottom: 1px solid var(--border-row); color: var(--ink-2); }
	a > i:first-child { color: var(--accent-ink); font-size: 20px; }
	a > i:last-child { color: var(--ink-4); }
	@media (max-width: 560px) { .more-page { padding: 16px; } }
</style>
