<script lang="ts">
	import { onMount } from 'svelte';
	import { t } from '$lib/i18n';

	type Range = '90days' | 'year' | 'all';
	type RankedPodcast = { rank: number; id: string; title: string; ms: number; episodes: number };
	type RankedListener = { rank: number; username: string; ms: number; active_days: number; podcasts: number };
	type LabeledTotal = { label: string; ms: number };
	type DayTotal = { date: string; ms: number };
	type GlobalStats = {
		generated_at: number;
		range: Range;
		timezone: string;
		participants: number;
		total_wall_ms: number;
		baseline_audio_ms: number;
		total_saved_ms: number;
		speed_saved_ms: number;
		silence_saved_ms: number;
		manual_skipped_ms: number;
		intro_outro_skipped_ms: number;
		average_speed: number;
		active_days: number;
		listening_sessions: number;
		episodes: number;
		podcasts: number;
		weekday_totals: number[];
		hour_totals: number[];
		day_totals: DayTotal[];
		category_totals: LabeledTotal[];
		podcast_rankings: RankedPodcast[];
		listener_leaderboard: RankedListener[];
	};

	let range = $state<Range>('year');
	let stats = $state<GlobalStats | null>(null);
	let loading = $state(true);
	let error = $state('');
	let requestId = 0;

	const maxWeekday = $derived(Math.max(1, ...(stats?.weekday_totals ?? [])));
	const maxHour = $derived(Math.max(1, ...(stats?.hour_totals ?? [])));
	const maxCategory = $derived(Math.max(1, ...(stats?.category_totals.map((item) => item.ms) ?? [])));
	const activity = $derived.by(() => {
		const values = new Map(stats?.day_totals.map((item) => [item.date, item.ms]) ?? []);
		const end = new Date();
		end.setUTCHours(0, 0, 0, 0);
		const start = new Date(end);
		start.setUTCDate(start.getUTCDate() - 181);
		const max = Math.max(1, ...values.values());
		return Array.from({ length: 182 }, (_, index) => {
			const date = new Date(start);
			date.setUTCDate(start.getUTCDate() + index);
			const key = date.toISOString().slice(0, 10);
			const ms = values.get(key) ?? 0;
			const ratio = ms / max;
			return { key, ms, level: ms === 0 ? 0 : ratio < .15 ? 1 : ratio < .35 ? 2 : ratio < .65 ? 3 : 4 };
		});
	});
	const savedParts = $derived(stats ? [
		{ label: t('globalStats.speed'), value: stats.speed_saved_ms },
		{ label: t('globalStats.silence'), value: stats.silence_saved_ms },
		{ label: t('globalStats.manual'), value: stats.manual_skipped_ms },
		{ label: t('globalStats.introOutro'), value: stats.intro_outro_skipped_ms }
	] : []);

	onMount(load);

	async function load() {
		const id = ++requestId;
		loading = true;
		error = '';
		try {
			const res = await fetch(`/api/v1/stats/global?range=${range}`);
			if (!res.ok) throw new Error(`global stats failed: ${res.status}`);
			const data = await res.json();
			if (id === requestId) stats = data;
		} catch (_) {
			if (id === requestId) error = t('globalStats.loadError');
		} finally {
			if (id === requestId) loading = false;
		}
	}

	function selectRange(value: Range) {
		if (range === value) return;
		range = value;
		void load();
	}

	function duration(ms: number, compact = false) {
		const minutes = Math.max(0, Math.round(ms / 60_000));
		const hours = Math.floor(minutes / 60);
		const rest = minutes % 60;
		if (!hours) return `${minutes}m`;
		return compact && rest === 0 ? `${hours}h` : `${hours}h ${String(rest).padStart(2, '0')}m`;
	}

	function weekday(index: number) {
		return new Intl.DateTimeFormat(undefined, { weekday: 'short', timeZone: 'UTC' })
			.format(new Date(Date.UTC(2026, 6, 26 + index)));
	}
</script>

<div class="global-page">
	<header class="global-head">
		<div>
			<p class="eyebrow">KoalaCast Community</p>
			<h1>{t('globalStats.title')}</h1>
			<p>{t('globalStats.subtitle')}</p>
		</div>
		<div class="range-tabs" role="group" aria-label={t('globalStats.statisticsRange')}>
			<button aria-pressed={range === '90days'} class:active={range === '90days'} onclick={() => selectRange('90days')}>{t('globalStats.range90')}</button>
			<button aria-pressed={range === 'year'} class:active={range === 'year'} onclick={() => selectRange('year')}>{t('globalStats.rangeYear')}</button>
			<button aria-pressed={range === 'all'} class:active={range === 'all'} onclick={() => selectRange('all')}>{t('globalStats.rangeAll')}</button>
		</div>
	</header>

	{#if loading}
		<div class="state-box"><i class="ph ph-spinner-gap spinner" aria-hidden="true"></i> {t('common.loading')}</div>
	{:else if error}
		<div class="state-box error" role="alert">{error} <button onclick={load}>{t('common.retry')}</button></div>
	{:else if stats}
		<section class="kpi-grid" aria-label={t('globalStats.summaryLabel')}>
			<article><span>{t('globalStats.totalListening')}</span><strong>{duration(stats.total_wall_ms, true)}</strong><small>{stats.listening_sessions.toLocaleString()} {t('globalStats.sessions')}</small></article>
			<article><span>{t('globalStats.participants')}</span><strong>{stats.participants.toLocaleString()}</strong><small>{t('globalStats.optInAccounts')}</small></article>
			<article><span>{t('globalStats.podcasts')}</span><strong>{stats.podcasts.toLocaleString()}</strong><small>{stats.episodes.toLocaleString()} {t('globalStats.episodes')}</small></article>
			<article><span>{t('globalStats.averageSpeed')}</span><strong>{stats.average_speed.toFixed(2)}×</strong><small>{stats.active_days} {t('globalStats.activeDays')}</small></article>
		</section>

		{#if stats.total_wall_ms === 0}
			<div class="state-box">{t('globalStats.noData')}</div>
		{:else}
			<section class="panel activity-panel">
				<div class="panel-head"><div><p class="eyebrow">{t('globalStats.weeks', { count: 26 })}</p><h2>{t('globalStats.communityActivity')}</h2></div><strong>{duration(stats.total_wall_ms)}</strong></div>
				<div class="heatmap" aria-label={t('globalStats.communityActivity')}>
					{#each activity as day (day.key)}
						<span class="level-{day.level}" title={`${day.key}: ${duration(day.ms)}`}></span>
					{/each}
				</div>
			</section>

			<div class="two-col">
				<section class="panel">
					<div class="panel-head"><h2>{t('globalStats.weekday')}</h2></div>
					<div class="bar-list">
						{#each stats.weekday_totals as ms, index}
							<div class="bar-row"><span>{weekday(index)}</span><div><i style:width={`${ms / maxWeekday * 100}%`}></i></div><strong>{duration(ms, true)}</strong></div>
						{/each}
					</div>
				</section>
				<section class="panel">
					<div class="panel-head"><h2>{t('globalStats.hour')}</h2></div>
					<div class="hour-chart">
						{#each stats.hour_totals as ms, index}
							<div title={`${String(index).padStart(2, '0')}:00 · ${duration(ms)}`}><i style:height={`${Math.max(3, ms / maxHour * 100)}%`}></i><span>{index % 4 === 0 ? String(index).padStart(2, '0') : ''}</span></div>
						{/each}
					</div>
				</section>
			</div>

			<div class="two-col">
				<section class="panel">
					<div class="panel-head"><h2>{t('globalStats.categories')}</h2></div>
					<div class="bar-list categories">
						{#each stats.category_totals as item}
							<div class="bar-row"><span>{item.label}</span><div><i style:width={`${item.ms / maxCategory * 100}%`}></i></div><strong>{duration(item.ms, true)}</strong></div>
						{/each}
					</div>
				</section>
				<section class="panel">
					<div class="panel-head"><div><p class="eyebrow">{duration(stats.total_saved_ms)}</p><h2>{t('globalStats.timeSaved')}</h2></div></div>
					<div class="saved-grid">
						{#each savedParts as item}
							<div><span>{item.label}</span><strong>{duration(item.value)}</strong><i style:width={`${stats.total_saved_ms ? item.value / stats.total_saved_ms * 100 : 0}%`}></i></div>
						{/each}
					</div>
				</section>
			</div>

			<div class="two-col ranking-grid">
				<section class="panel ranking-panel">
					<div class="panel-head"><h2>{t('globalStats.podcastRanking')}</h2></div>
					<div class="ranking-list">
						{#each stats.podcast_rankings as item}
							<div><b>{String(item.rank).padStart(2, '0')}</b><span><strong>{item.title}</strong><small>{item.episodes} {item.episodes === 1 ? t('common.episode') : t('globalStats.episodes')}</small></span><em>{duration(item.ms, true)}</em></div>
						{/each}
					</div>
				</section>
				<section class="panel ranking-panel">
					<div class="panel-head"><h2>{t('globalStats.listenerLeaderboard')}</h2></div>
					<div class="ranking-list listeners">
						{#each stats.listener_leaderboard as item}
							<div><b>{String(item.rank).padStart(2, '0')}</b><span><strong>{item.username}</strong><small>{item.active_days} {t('globalStats.activeDays')} · {item.podcasts} {t('globalStats.podcasts')}</small></span><em>{duration(item.ms, true)}</em></div>
						{/each}
					</div>
				</section>
			</div>
		{/if}

		<footer class="privacy-note">
			<i class="ph ph-shield-check" aria-hidden="true"></i>
			<p>{t('globalStats.privacy')} <a href="/settings#privacy">{t('globalStats.manageConsent')}</a>.</p>
		</footer>
	{/if}
</div>

<style>
	.global-page { padding: 26px 28px 52px; display: flex; flex-direction: column; gap: 18px; max-width: 1260px; margin: 0 auto; }
	.global-head { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 20px; border-bottom: 1px solid var(--border-hair); }
	.global-head h1 { margin: 3px 0 5px; font: 800 clamp(34px, 5vw, 58px)/.95 var(--font-display); letter-spacing: -.045em; color: var(--ink-1); }
	.global-head > div > p:last-child { max-width: 650px; color: var(--ink-4); font-size: 13px; }
	.eyebrow { color: var(--accent); font: 700 10px/1 var(--font-mono); letter-spacing: .12em; text-transform: uppercase; }
	.range-tabs { display: flex; padding: 3px; background: var(--bg-sunken); border: 1px solid var(--border-ui); border-radius: 5px; flex-shrink: 0; }
	.range-tabs button { min-height: 34px; padding: 0 11px; border: 0; border-radius: 3px; background: transparent; color: var(--ink-4); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	.range-tabs button.active { color: var(--accent-on); background: var(--accent-fill); }
	.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid var(--border-ui); }
	.kpi-grid article { padding: 18px; display: flex; flex-direction: column; border-right: 1px solid var(--border-hair); background: var(--bg-panel); }
	.kpi-grid article:last-child { border: 0; }
	.kpi-grid span, .kpi-grid small { color: var(--ink-4); font: 600 10px/1.4 var(--font-mono); text-transform: uppercase; letter-spacing: .06em; }
	.kpi-grid strong { margin: 8px 0 5px; color: var(--ink-1); font: 700 28px/1 var(--font-display); }
	.panel { padding: 18px; border: 1px solid var(--border-ui); background: var(--bg-panel); min-width: 0; }
	.panel-head { min-height: 35px; margin-bottom: 18px; display: flex; align-items: start; justify-content: space-between; gap: 12px; }
	.panel-head h2 { color: var(--ink-1); font: 700 16px/1.1 var(--font-display); }
	.panel-head strong { color: var(--ink-2); font: 600 12px/1 var(--font-mono); }
	.heatmap { display: grid; grid-auto-flow: column; grid-template-rows: repeat(7, 10px); grid-auto-columns: 10px; gap: 4px; overflow-x: auto; padding-bottom: 5px; }
	.heatmap span { background: var(--bg-sunken); border: 1px solid var(--border-hair); }
	.heatmap .level-1 { background: color-mix(in srgb, var(--accent-fill) 24%, var(--bg-sunken)); }
	.heatmap .level-2 { background: color-mix(in srgb, var(--accent-fill) 45%, var(--bg-sunken)); }
	.heatmap .level-3 { background: color-mix(in srgb, var(--accent-fill) 70%, var(--bg-sunken)); }
	.heatmap .level-4 { background: var(--accent-fill); }
	.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
	.bar-list { display: flex; flex-direction: column; gap: 10px; }
	.bar-row { display: grid; grid-template-columns: 36px 1fr 58px; align-items: center; gap: 9px; }
	.bar-row > span, .bar-row > strong { color: var(--ink-4); font: 600 10px/1 var(--font-mono); }
	.bar-row > strong { text-align: right; color: var(--ink-3); }
	.bar-row > div { height: 6px; background: var(--bg-sunken); }
	.bar-row i { display: block; height: 100%; background: var(--accent-fill); }
	.categories .bar-row { grid-template-columns: minmax(90px, 130px) 1fr 58px; }
	.hour-chart { height: 145px; display: grid; grid-template-columns: repeat(24, 1fr); align-items: end; gap: 3px; border-bottom: 1px solid var(--border-ui); }
	.hour-chart > div { height: 100%; display: flex; flex-direction: column; justify-content: end; align-items: center; gap: 5px; }
	.hour-chart i { width: 100%; min-height: 3px; background: var(--accent-fill); opacity: .84; }
	.hour-chart span { height: 12px; color: var(--ink-5); font: 600 9px/1 var(--font-mono); }
	.saved-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
	.saved-grid > div { position: relative; padding: 12px; overflow: hidden; background: var(--bg-sunken); border: 1px solid var(--border-hair); }
	.saved-grid span { display: block; color: var(--ink-4); font: 600 10px/1.2 var(--font-mono); text-transform: uppercase; }
	.saved-grid strong { display: block; margin-top: 7px; color: var(--ink-2); font: 700 16px/1 var(--font-display); }
	.saved-grid i { position: absolute; left: 0; bottom: 0; height: 2px; background: var(--accent-fill); }
	.ranking-panel { padding: 0; }
	.ranking-panel .panel-head { margin: 0; padding: 17px 18px; border-bottom: 1px solid var(--border-hair); }
	.ranking-list { max-height: 490px; overflow-y: auto; }
	.ranking-list > div { min-height: 58px; padding: 10px 16px; display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 10px; border-bottom: 1px solid var(--border-hair); }
	.ranking-list > div:last-child { border: 0; }
	.ranking-list b { color: var(--ink-5); font: 600 10px/1 var(--font-mono); }
	.ranking-list span { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
	.ranking-list span strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-2); font-size: 12px; }
	.ranking-list small { color: var(--ink-5); font: 500 10px/1 var(--font-mono); text-transform: uppercase; }
	.ranking-list em { color: var(--ink-3); font: 600 10px/1 var(--font-mono); font-style: normal; }
	.privacy-note { padding: 14px 16px; display: flex; gap: 10px; align-items: start; border: 1px solid var(--border-hair); color: var(--ink-4); font-size: 11px; }
	.privacy-note i { color: var(--accent); font-size: 17px; }
	.privacy-note a { color: var(--accent); font-weight: 700; }
	.state-box { min-height: 160px; display: flex; align-items: center; justify-content: center; gap: 8px; border: 1px solid var(--border-ui); color: var(--ink-4); }
	.state-box.error { color: var(--danger); }
	.state-box button { color: var(--accent); font-weight: 700; background: none; border: 0; }
	.spinner { animation: spin .8s linear infinite; }
	@keyframes spin { to { transform: rotate(360deg); } }
	@media (max-width: 900px) {
		.global-page { padding: 20px 18px 90px; }
		.global-head { align-items: start; flex-direction: column; }
		.kpi-grid { grid-template-columns: 1fr 1fr; }
		.kpi-grid article:nth-child(2) { border-right: 0; }
		.kpi-grid article:nth-child(-n+2) { border-bottom: 1px solid var(--border-hair); }
		.two-col { grid-template-columns: 1fr; }
	}
	@media (max-width: 520px) {
		.global-page { padding: 16px 12px 100px; }
		.global-head h1 { font-size: 38px; }
		.range-tabs { width: 100%; }
		.range-tabs button { flex: 1; padding: 0 5px; }
		.kpi-grid { grid-template-columns: 1fr; }
		.kpi-grid article { border-right: 0; border-bottom: 1px solid var(--border-hair); }
		.saved-grid { grid-template-columns: 1fr; }
	}
</style>
