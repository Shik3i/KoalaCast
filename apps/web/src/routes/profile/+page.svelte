<script lang="ts">
	import { onMount } from 'svelte';
	import {
		getAllLocalPlaybackStates,
		getLocalListeningSessions,
		getLocalSubscriptions,
		type LocalListeningSession,
		type LocalPlaybackState
	} from '$lib/idb/db';
	import { localDayKey, summarizeListening } from '$lib/listening/analytics';
	import { sync } from '$lib/stores/sync.svelte';

	type Range = 'year' | '90days' | 'all';

	let range = $state<Range>('year');
	let sessions = $state<LocalListeningSession[]>([]);
	let history = $state<LocalPlaybackState[]>([]);
	let subscriptionCount = $state(0);
	let loadedSyncAt = 0;

	const rangeFloor = $derived.by(() => {
		const now = new Date();
		if (range === '90days') return Date.now() - 90 * 86_400_000;
		if (range === 'year') return new Date(now.getFullYear(), 0, 1).getTime();
		return 0;
	});
	const filteredSessions = $derived(sessions.filter((item) => item.started_at >= rangeFloor));
	const filteredStates = $derived(history.filter((item) => (item.last_played_at ?? 0) >= rangeFloor));
	const stats = $derived(summarizeListening(filteredSessions, filteredStates));
	const maxShowMs = $derived(stats.showTotals[0]?.ms || 1);
	const maxWeekdayMs = $derived(Math.max(1, ...stats.weekdayTotals));
	const maxCategoryMs = $derived(Math.max(1, ...stats.categoryTotals.map((item) => item.ms)));
	const maxHourMs = $derived(Math.max(1, ...stats.hourTotals));
	const firstListening = $derived(sessions[0]?.started_at ?? null);
	const averagePerDay = $derived(stats.activeDays ? stats.totalWallMs / stats.activeDays : 0);
	const touchedShows = $derived(new Set(sessions.map((item) => item.podcast_id)).size);
	const heatmap = $derived.by(() => {
		const end = new Date();
		end.setHours(0, 0, 0, 0);
		const start = new Date(end);
		start.setDate(start.getDate() - 181);
		return Array.from({ length: 182 }, (_, index) => {
			const date = new Date(start);
			date.setDate(start.getDate() + index);
			const minutes = (stats.byDay.get(localDayKey(date.getTime())) ?? 0) / 60_000;
			return { date, minutes, level: minutes === 0 ? 0 : minutes < 20 ? 1 : minutes < 45 ? 2 : minutes < 90 ? 3 : 4 };
		});
	});
	const weekdayRows = $derived([1, 2, 3, 4, 5, 6, 0].map((index) => ({
		label: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][index],
		ms: stats.weekdayTotals[index]
	})));
	const heaviestDay = $derived([...weekdayRows].sort((a, b) => b.ms - a.ms)[0]);
	const lightestDay = $derived([...weekdayRows].sort((a, b) => a.ms - b.ms)[0]);
	const peakHour = $derived(stats.hourTotals.indexOf(maxHourMs));

	async function loadListeningData() {
		[sessions, history, subscriptionCount] = await Promise.all([
			getLocalListeningSessions(),
			getAllLocalPlaybackStates(),
			getLocalSubscriptions().then((items) => items.length)
		]);
	}

	onMount(loadListeningData);

	$effect(() => {
		const syncedAt = sync.lastSyncedAt;
		if (!syncedAt || syncedAt === loadedSyncAt) return;
		loadedSyncAt = syncedAt;
		void loadListeningData();
	});

	function duration(ms: number, compact = false) {
		const minutes = Math.max(0, Math.round(ms / 60_000));
		const hours = Math.floor(minutes / 60);
		const rest = minutes % 60;
		if (!hours) return `${minutes}m`;
		return compact && !rest ? `${hours}h` : `${hours}h ${String(rest).padStart(2, '0')}m`;
	}

	function exportData() {
		const blob = new Blob([
			JSON.stringify({ exportedAt: new Date().toISOString(), listeningSessions: sessions, playbackStates: history }, null, 2)
		], { type: 'application/json' });
		const url = URL.createObjectURL(blob);
		const anchor = document.createElement('a');
		anchor.href = url;
		anchor.download = 'koalacast-listening-data.json';
		anchor.click();
		URL.revokeObjectURL(url);
	}
</script>

<div class="profile-page">
	<header class="profile-head" id="stats">
		<span class="profile-avatar">JK</span>
		<div>
			<h1>Your listening</h1>
			<p>
				{firstListening ? `Since ${new Date(firstListening).toLocaleDateString([], { day: '2-digit', month: 'short', year: 'numeric' })}` : 'Tracking starts with your next play'}
				· {subscriptionCount} subscriptions · {touchedShows} shows touched
			</p>
		</div>
		<div class="range-tabs" role="group" aria-label="Statistics range">
			<button aria-pressed={range === 'year'} class:active={range === 'year'} onclick={() => (range = 'year')}>This year</button>
			<button aria-pressed={range === '90days'} class:active={range === '90days'} onclick={() => (range = '90days')}>90 days</button>
			<button aria-pressed={range === 'all'} class:active={range === 'all'} onclick={() => (range = 'all')}>All time</button>
		</div>
	</header>

	<section class="kpi-grid">
		<article><span>Listened</span><strong>{duration(stats.totalWallMs, true)}</strong><p>Actual time with audio playing</p></article>
		<article><span>Episodes finished</span><strong>{stats.completedCount}</strong><p>Across {stats.showTotals.length} tracked shows</p></article>
		<article><span>Longest streak</span><strong>{stats.longestStreak} days</strong><p>Consecutive local calendar days</p></article>
		<article><span>Average per day</span><strong>{duration(averagePerDay)}</strong><p>On days you listened at all</p></article>
	</section>

	<section class="activity-card" id="activity">
		<header><h2>Listening activity</h2><span>Last 26 weeks · actual play time</span></header>
		<div class="activity-layout">
			<div class="weekday-labels" aria-hidden="true"><span>Mon</span><span>Wed</span><span>Fri</span><span>Sun</span></div>
			<div class="heatmap" aria-label="Listening activity over the last 26 weeks">
				{#each heatmap as day}
					<span class={`level-${day.level}`} title={`${day.date.toLocaleDateString()}: ${Math.round(day.minutes)} minutes`}></span>
				{/each}
			</div>
		</div>
		<footer><span>Less</span>{#each [0,1,2,3,4] as level}<i class={`level-${level}`}></i>{/each}<span>More</span><strong>{stats.activeDays} days with listening</strong></footer>
	</section>

	<div class="profile-analysis">
		<section class="rankings" id="rankings">
			<header><h2>Most played shows</h2><span>By actual listening time</span></header>
			<div class="ranking-list">
				{#each stats.showTotals as show, index}
					<a href={`/podcast/${show.id}`}>
						<span>{String(index + 1).padStart(2, '0')}</span>
						<i class="cover-placeholder"></i>
						<div><strong>{show.title}</strong><span><i style:width={`${show.ms / maxShowMs * 100}%`}></i></span></div>
						<time>{duration(show.ms)}</time>
						<em>{show.episodes} ep</em>
					</a>
				{:else}
					<p class="empty">Your rankings appear after you listen to a show.</p>
				{/each}
			</div>
		</section>

		<aside class="breakdowns">
			<section>
				<header><h2>By weekday</h2></header>
				<div class="weekday-bars">
					{#each weekdayRows as day}
						<div><span>{duration(day.ms, true)}</span><i><b style:height={`${day.ms / maxWeekdayMs * 100}%`}></b></i><strong>{day.label}</strong></div>
					{/each}
				</div>
				<p>{stats.totalWallMs ? `${heaviestDay?.label || '—'} is heaviest · ${lightestDay?.label || '—'} is lightest` : 'No listening recorded yet'}</p>
			</section>
			<section>
				<header><h2>By hour</h2></header>
				<div class="hour-heat">
					{#each stats.hourTotals as value, hour}
						<i style:opacity={`${.16 + .84 * value / maxHourMs}`} title={`${String(hour).padStart(2, '0')}:00 · ${duration(value)}`}></i>
					{/each}
				</div>
				<div class="hour-axis"><span>00</span><span>06</span><span>12</span><span>18</span><span>23</span></div>
				<p>{stats.totalWallMs ? `Peak ${String(Math.max(0, peakHour)).padStart(2, '0')}:00–${String(Math.max(0, peakHour) + 1).padStart(2, '0')}:00` : 'No hourly pattern recorded yet'}</p>
			</section>
			<section>
				<header><h2>Categories</h2></header>
				<div class="category-bars">
					{#each stats.categoryTotals as category}
						<div><span>{category.label}</span><i><b style:width={`${category.ms / maxCategoryMs * 100}%`}></b></i><strong>{stats.totalWallMs ? Math.round(category.ms / stats.totalWallMs * 100) : 0}%</strong></div>
					{:else}
						<p>No category data recorded yet.</p>
					{/each}
				</div>
			</section>
		</aside>
	</div>

	<section class="saved-section" id="time-saved">
		<header><h2>Time saved</h2><span>Baseline: playing everything at 1× in full</span></header>
		<div class="saved-grid">
			<article class="saved-total">
				<span>Total saved</span>
				<strong>{duration(stats.totalSavedMs)}</strong>
				<p>You covered {duration(stats.baselineAudioMs)} of audio in {duration(stats.totalWallMs)} of real time.</p>
			</article>
			<div class="saving-rows">
				<div><i class="ph ph-gauge"></i><span><strong>Variable speed</strong><small>Average {stats.averageSpeed.toFixed(2)}× across tracked playback</small><em><i style:width={`${stats.totalSavedMs ? stats.speedSavedMs / stats.totalSavedMs * 100 : 0}%`}></i></em></span><time>{duration(stats.speedSavedMs)}</time></div>
				<div><i class="ph ph-waveform"></i><span><strong>Trim silence</strong><small>Measured while silence trimming is active</small><em><i style:width={`${stats.totalSavedMs ? stats.silenceSavedMs / stats.totalSavedMs * 100 : 0}%`}></i></em></span><time>{duration(stats.silenceSavedMs)}</time></div>
				<div><i class="ph ph-skip-forward"></i><span><strong>Intro & outro</strong><small>Per-show automatic skips</small><em><i style:width={`${stats.totalSavedMs ? stats.introOutroSkippedMs / stats.totalSavedMs * 100 : 0}%`}></i></em></span><time>{duration(stats.introOutroSkippedMs)}</time></div>
				<div><i class="ph ph-fast-forward"></i><span><strong>Manual fast-forward</strong><small>Forward jumps and timeline seeks</small><em><i style:width={`${stats.totalSavedMs ? stats.manualSkippedMs / stats.totalSavedMs * 100 : 0}%`}></i></em></span><time>{duration(stats.manualSkippedMs)}</time></div>
			</div>
		</div>
		<footer class="saved-summary">Average speed {stats.averageSpeed.toFixed(2)}× · {stats.totalSavedMs ? Math.round(stats.totalSavedMs / Math.max(1, stats.baselineAudioMs) * 100) : 0}% of baseline skipped · measured locally</footer>
	</section>

	<section class="privacy-card" id="privacy">
		<div>
			<span>Data & privacy</span>
			<h2>{sync.enabled ? 'Account sync is active.' : 'Stored only in this browser.'}</h2>
			<p>{sync.enabled ? 'Listening sessions sync to your signed-in KoalaCast account so your statistics follow you across devices.' : 'Sign in to synchronize listening sessions across your devices. No third-party analytics receive this data.'}</p>
		</div>
		<button onclick={exportData}><i class="ph ph-download-simple"></i> Export JSON</button>
	</section>
</div>

<style>
	.profile-page { padding: 24px 22px 36px; }
	.profile-head { display: grid; grid-template-columns: 64px minmax(0,1fr) auto; gap: 14px; align-items: center; padding-bottom: 20px; border-bottom: 1px solid var(--border-hair); }
	.profile-avatar { display: grid; place-items: center; width: 64px; height: 64px; border-radius: 50%; background: var(--accent-fill); color: var(--accent-on); font: 700 16px/1 var(--font-mono); }
	.profile-head h1 { font-size: 30px; letter-spacing: -.04em; }
	.profile-head p, section > header span { color: var(--ink-4); font: 600 9px/1.5 var(--font-mono); letter-spacing: .06em; text-transform: uppercase; }
	.range-tabs { display: flex; gap: 2px; }
	.range-tabs button { padding: 7px 9px; border: 0; border-radius: 3px; background: transparent; color: var(--ink-4); font: 600 8px/1 var(--font-mono); text-transform: uppercase; }
	.range-tabs button.active { background: var(--accent-wash); color: var(--accent-ink); }
	.kpi-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 10px; padding: 18px 0; }
	.kpi-grid article { padding: 15px; border: 1px solid var(--border-hair); border-radius: 8px; background: var(--bg-sunken); }
	.kpi-grid span, .saved-total > span, .privacy-card > div > span { color: var(--ink-4); font: 600 8px/1 var(--font-mono); letter-spacing: .08em; text-transform: uppercase; }
	.kpi-grid strong { display: block; margin: 7px 0 5px; color: var(--ink-strong); font: 800 26px/1 var(--font-ui); letter-spacing: -.04em; }
	.kpi-grid p { color: var(--ink-3); font-size: 10px; }
	.activity-card, .rankings, .saved-section { padding: 20px 0; border-top: 1px solid var(--border-hair); }
	section > header { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
	section > header h2 { font-size: 17px; }
	.activity-layout { display: grid; grid-template-columns: 24px minmax(0,1fr); gap: 6px; }
	.weekday-labels { display: flex; flex-direction: column; justify-content: space-between; padding: 1px 0; color: var(--ink-4); font: 500 7px/1 var(--font-mono); text-transform: uppercase; }
	.heatmap { display: grid; grid-template-rows: repeat(7, 14px); grid-auto-flow: column; grid-auto-columns: minmax(7px,1fr); gap: 3px; overflow: hidden; }
	.heatmap span, .activity-card footer i { border-radius: 2px; background: var(--heat-0); }
	.level-1 { background: var(--heat-1) !important; }.level-2 { background: var(--heat-2) !important; }.level-3 { background: var(--heat-3) !important; }.level-4 { background: var(--heat-4) !important; }
	.activity-card footer { display: flex; align-items: center; gap: 4px; margin-top: 10px; color: var(--ink-4); font: 600 8px/1 var(--font-mono); text-transform: uppercase; }
	.activity-card footer i { width: 10px; height: 10px; }.activity-card footer strong { margin-left: auto; color: var(--ink-3); }
	.profile-analysis { display: grid; grid-template-columns: minmax(0,1fr) 380px; gap: 24px; align-items: start; }
	.ranking-list { border-top: 1px solid var(--border-row); }
	.ranking-list a { display: grid; grid-template-columns: 22px 36px minmax(0,1fr) 96px 58px; gap: 8px; align-items: center; min-height: 52px; border-bottom: 1px solid var(--border-row); }
	.ranking-list > a > span, .ranking-list time, .ranking-list em { color: var(--ink-4); font: 600 9px/1 var(--font-mono); font-style: normal; font-variant-numeric: tabular-nums; }
	.cover-placeholder { width: 36px; height: 36px; border-radius: 4px; background: var(--bg-tile); }
	.ranking-list a > div { min-width: 0; }.ranking-list a > div strong { display: block; overflow: hidden; font: 700 12px/1.4 var(--font-ui); text-overflow: ellipsis; white-space: nowrap; }
	.ranking-list a > div > span { display: block; height: 3px; margin-top: 5px; background: var(--track); border-radius: 20px; }.ranking-list a > div > span i { display: block; height: 100%; background: var(--accent-fill); border-radius: inherit; }
	.empty { padding: 20px 0; color: var(--ink-4); font-size: 12px; }
	.breakdowns { display: grid; gap: 12px; padding: 20px 0; }
	.breakdowns section { padding: 14px; border: 1px solid var(--border-hair); border-radius: 6px; background: var(--bg-sunken); }
	.breakdowns section > header { margin-bottom: 10px; }.breakdowns p { margin-top: 9px; color: var(--ink-4); font: 500 8px/1.4 var(--font-mono); text-transform: uppercase; }
	.weekday-bars { display: grid; grid-template-columns: repeat(7,1fr); gap: 7px; height: 92px; }
	.weekday-bars div { display: grid; grid-template-rows: 12px 1fr 10px; gap: 4px; text-align: center; }
	.weekday-bars span, .weekday-bars strong { color: var(--ink-4); font: 500 7px/1 var(--font-mono); }
	.weekday-bars i { display: flex; align-items: end; justify-content: center; background: var(--track); border-radius: 3px 3px 1px 1px; }
	.weekday-bars b { width: 100%; min-height: 2px; background: var(--data-bar); }.weekday-bars div:nth-child(5) b { background: var(--accent-fill); }
	.hour-heat { display: grid; grid-template-columns: repeat(12,1fr); gap: 3px; }.hour-heat i { aspect-ratio: 1; border-radius: 2px; background: var(--accent-fill); }
	.hour-axis { display: flex; justify-content: space-between; margin-top: 5px; color: var(--ink-4); font: 500 7px/1 var(--font-mono); }
	.category-bars { display: grid; gap: 8px; }.category-bars div { display: grid; grid-template-columns: 88px 1fr 28px; gap: 7px; align-items: center; }
	.category-bars span, .category-bars strong { overflow: hidden; color: var(--ink-4); font: 500 8px/1 var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }.category-bars strong { text-align: right; }
	.category-bars i { height: 4px; border-radius: 8px; background: var(--track); }.category-bars b { display: block; height: 100%; border-radius: inherit; background: var(--data-bar); }
	.saved-grid { display: grid; grid-template-columns: minmax(220px,.7fr) 1.3fr; gap: 14px; }
	.saved-total { padding: 18px; border: 1px solid var(--accent-ink); border-radius: 8px; background: var(--accent-wash); }
	.saved-total strong { display: block; margin: 8px 0; color: var(--ink-strong); font: 800 30px/1 var(--font-ui); letter-spacing: -.04em; }.saved-total p { color: var(--ink-3); font-size: 11px; }
	.saving-rows > div { display: grid; grid-template-columns: 28px minmax(0,1fr) auto; gap: 10px; align-items: center; min-height: 52px; border-bottom: 1px solid var(--border-row); }
	.saving-rows > div > i { color: var(--accent-ink); font-size: 20px; }.saving-rows span { display: flex; flex-direction: column; }.saving-rows strong { font: 700 12px/1.3 var(--font-ui); }.saving-rows small { color: var(--ink-4); font: 500 8px/1.5 var(--font-mono); text-transform: uppercase; }
	.saving-rows em { height: 3px; margin-top: 5px; background: var(--track); border-radius: 10px; }.saving-rows em i { display: block; height: 100%; background: var(--accent-fill); }.saving-rows time { color: var(--ink-3); font: 600 10px/1 var(--font-mono); }
	.saved-summary { margin-top: 10px; color: var(--ink-4); font: 600 8px/1 var(--font-mono); text-transform: uppercase; }
	.privacy-card { display: flex; justify-content: space-between; gap: 20px; align-items: center; margin-top: 20px; padding: 18px; border: 1px solid var(--border-hair); border-radius: 8px; background: var(--bg-sunken); }
	.privacy-card h2 { margin: 5px 0; font-size: 17px; }.privacy-card p { max-width: 68ch; color: var(--ink-3); font-size: 11px; }
	.privacy-card button { display: inline-flex; gap: 7px; align-items: center; flex: 0 0 auto; padding: 9px 11px; border: 0; border-radius: 5px; background: var(--accent-fill); color: var(--accent-on); font: 700 9px/1 var(--font-mono); text-transform: uppercase; }
	@media (max-width: 1050px) { .profile-analysis { grid-template-columns: 1fr; }.breakdowns { grid-template-columns: repeat(3,1fr); }.ranking-list a { grid-template-columns: 22px 36px minmax(0,1fr) 80px 46px; } }
	@media (max-width: 760px) { .profile-head { grid-template-columns: 54px 1fr; }.profile-avatar { width: 54px; height: 54px; }.range-tabs { grid-column: 1 / -1; overflow-x: auto; }.kpi-grid { grid-template-columns: repeat(2,1fr); }.saved-grid, .breakdowns { grid-template-columns: 1fr; } }
	@media (max-width: 520px) { .profile-page { padding: 16px; }.heatmap { grid-auto-columns: 9px; overflow-x: auto; }.heatmap span:nth-child(-n+91) { display: none; }.ranking-list a { grid-template-columns: 22px 34px minmax(0,1fr) 56px; }.ranking-list em { display: none; }.privacy-card { align-items: flex-start; flex-direction: column; } }
</style>
