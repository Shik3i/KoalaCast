<script lang="ts">
	import { onMount } from 'svelte';
	import { t } from '$lib/i18n';
	import { audioDownloads, type AudioDownload } from '$lib/downloads/manager.svelte';
	import { optimizeArtwork } from '$lib/artwork';

	onMount(() => audioDownloads.load());

	const downloadedBytes = $derived(
		audioDownloads.items
			.filter((item) => item.state === 'downloaded')
			.reduce((sum, item) => sum + item.bytesDownloaded, 0)
	);

	function formatBytes(bytes: number) {
		if (!bytes) return '0 B';
		const units = ['B', 'KB', 'MB', 'GB'];
		const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
		return `${(bytes / 1024 ** unit).toFixed(unit > 1 ? 1 : 0)} ${units[unit]}`;
	}

	function progress(item: AudioDownload) {
		return item.totalBytes > 0
			? Math.round((item.bytesDownloaded / item.totalBytes) * 100)
			: 0;
	}
</script>

<svelte:head><title>{t('downloads.title')} — KoalaCast</title></svelte:head>

<main class="downloads-page">
	<header>
		<div>
			<p class="eyebrow">{t('downloads.offline')}</p>
			<h1>{t('downloads.title')}</h1>
			<p>{t('downloads.description')}</p>
		</div>
		<div class="storage">
			<strong>{formatBytes(downloadedBytes)}</strong>
			<span>{t('downloads.audioStored')}</span>
			<small>{t('downloads.browserStorage', {
				used: formatBytes(audioDownloads.usageBytes),
				quota: formatBytes(audioDownloads.quotaBytes)
			})}</small>
		</div>
	</header>

	<section class="download-list" aria-live="polite">
		{#each audioDownloads.items as item (item.episodeId)}
			<article>
				<img
					src={optimizeArtwork(item.artworkUrl, 120)}
					alt=""
					onerror={(event) => ((event.currentTarget as HTMLImageElement).src = '/cover-placeholder.webp')}
				/>
				<div class="meta">
					<a href={`/episode/${item.episodeId}`}>{item.title}</a>
					<span>{item.podcastTitle}</span>
					{#if item.state === 'downloading'}
						<div class="progress"><i style={`width:${progress(item)}%`}></i></div>
						<small>{formatBytes(item.bytesDownloaded)} / {item.totalBytes ? formatBytes(item.totalBytes) : '…'} · {progress(item)}%</small>
					{:else if item.state === 'failed'}
						<small class="error">{t('downloads.failed')}: {item.error}</small>
					{:else if item.state === 'cancelled'}
						<small>{t('downloads.cancelled')} · {formatBytes(item.bytesDownloaded)}</small>
					{:else}
						<small>{t('downloads.ready')} · {formatBytes(item.bytesDownloaded)}</small>
					{/if}
				</div>
				<div class="actions">
					{#if item.state === 'downloading'}
						<button onclick={() => audioDownloads.cancel(item.episodeId)}>{t('downloads.cancel')}</button>
					{:else if item.state === 'failed' || item.state === 'cancelled'}
						<button onclick={() => audioDownloads.retry(item.episodeId)}>{t('downloads.retry')}</button>
					{/if}
					<button class="remove" onclick={() => audioDownloads.remove(item.episodeId)} aria-label={t('downloads.remove')}>
						<i class="ph ph-trash" aria-hidden="true"></i>
					</button>
				</div>
			</article>
		{:else}
			<div class="empty">
				<i class="ph ph-download-simple" aria-hidden="true"></i>
				<h2>{t('downloads.empty')}</h2>
				<p>{t('downloads.emptyHint')}</p>
			</div>
		{/each}
	</section>
</main>

<style>
	.downloads-page { display: grid; gap: 28px; padding: 32px 40px 100px; max-width: 1100px; margin: 0 auto; }
	header { display: flex; justify-content: space-between; align-items: end; gap: 24px; }
	h1 { font-size: clamp(34px, 5vw, 58px); line-height: 1; }
	header p { color: var(--ink-4); margin-top: 8px; }
	.eyebrow { color: var(--accent-ink); font: 700 12px/1 var(--font-mono); letter-spacing: .1em; text-transform: uppercase; }
	.storage { display: grid; min-width: 230px; padding: 18px; border: 1px solid var(--border-strong); background: var(--bg-panel); }
	.storage strong { font-size: 24px; color: var(--ink-strong); }
	.storage span, .storage small { color: var(--ink-4); }
	.download-list { display: grid; border-top: 1px solid var(--border-row); }
	article { display: grid; grid-template-columns: 64px minmax(0, 1fr) auto; gap: 16px; align-items: center; padding: 16px 0; border-bottom: 1px solid var(--border-row); }
	article img { width: 64px; height: 64px; border-radius: 8px; object-fit: cover; }
	.meta { display: grid; gap: 4px; min-width: 0; }
	.meta > a { color: var(--ink-strong); font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.meta > span, .meta small { color: var(--ink-4); }
	.meta .error { color: var(--danger, #d75b5b); }
	.progress { height: 4px; overflow: hidden; background: var(--track); margin-top: 5px; }
	.progress i { display: block; height: 100%; background: var(--accent-fill); transition: width .15s linear; }
	.actions { display: flex; align-items: center; gap: 8px; }
	button { min-height: 40px; padding: 0 14px; border: 1px solid var(--border-strong); color: var(--ink-2); background: transparent; }
	button.remove { width: 40px; padding: 0; }
	.empty { display: grid; justify-items: center; gap: 8px; padding: 80px 20px; text-align: center; color: var(--ink-4); }
	.empty i { font-size: 44px; color: var(--accent-ink); }
	@media (max-width: 680px) {
		.downloads-page { padding: 20px 16px 120px; }
		header { display: grid; }
		.storage { min-width: 0; }
		article { grid-template-columns: 52px minmax(0, 1fr); }
		article img { width: 52px; height: 52px; }
		.actions { grid-column: 2; }
	}
</style>
