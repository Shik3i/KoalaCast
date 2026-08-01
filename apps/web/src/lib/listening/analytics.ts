import type { LocalListeningSession, LocalPlaybackState } from '$lib/idb/db';

export interface ListeningAnalytics {
	totalWallMs: number;
	baselineAudioMs: number;
	totalSavedMs: number;
	speedSavedMs: number;
	silenceSavedMs: number;
	manualSkippedMs: number;
	introOutroSkippedMs: number;
	averageSpeed: number;
	activeDays: number;
	longestStreak: number;
	completedCount: number;
	showTotals: Array<{ id: string; title: string; ms: number; episodes: number }>;
	weekdayTotals: number[];
	hourTotals: number[];
	categoryTotals: Array<{ label: string; ms: number }>;
	byDay: Map<string, number>;
}

export function localDayKey(timestamp: number): string {
	const date = new Date(timestamp);
	return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function summarizeListening(
	sessions: LocalListeningSession[],
	states: LocalPlaybackState[]
): ListeningAnalytics {
	const listenedByEpisode = new Map<string, number>();
	for (const session of sessions) {
		listenedByEpisode.set(
			session.episode_id,
			(listenedByEpisode.get(session.episode_id) ?? 0) + Math.max(0, session.audio_listened_ms)
		);
	}
	const byDay = new Map<string, number>();
	const weekdayTotals = Array(7).fill(0) as number[];
	const hourTotals = Array(24).fill(0) as number[];
	const shows = new Map<string, { id: string; title: string; ms: number; episodes: Set<string> }>();
	const categories = new Map<string, number>();
	let totalWallMs = 0;
	let speedSavedMs = 0;
	let silenceSavedMs = 0;
	let manualSkippedMs = 0;
	let introOutroSkippedMs = 0;
	let speedWeightedMs = 0;

	for (const session of sessions) {
		const wall = Math.max(0, session.wall_clock_ms);
		totalWallMs += wall;
		speedSavedMs += Math.max(0, session.speed_saved_ms);
		silenceSavedMs += Math.max(0, session.silence_saved_ms);
		manualSkippedMs += Math.max(0, session.manual_skipped_ms);
		introOutroSkippedMs += Math.max(0, session.intro_outro_skipped_ms);
		speedWeightedMs += Math.max(0, session.speed_weighted_ms);
		distributeAcrossHours(session, wall, (timestamp, portion) => {
			const date = new Date(timestamp);
			const day = localDayKey(timestamp);
			byDay.set(day, (byDay.get(day) ?? 0) + portion);
			weekdayTotals[date.getDay()] += portion;
			hourTotals[date.getHours()] += portion;
		});

		const show = shows.get(session.podcast_id) ?? {
			id: session.podcast_id,
			title: session.podcast_title || 'Unknown show',
			ms: 0,
			episodes: new Set<string>()
		};
		show.ms += wall;
		show.episodes.add(session.episode_id);
		shows.set(session.podcast_id, show);

		const category = session.categories?.find(Boolean) || 'Uncategorised';
		categories.set(category, (categories.get(category) ?? 0) + wall);
	}

	const totalSavedMs = speedSavedMs + silenceSavedMs + manualSkippedMs + introOutroSkippedMs;
	return {
		totalWallMs,
		baselineAudioMs: totalWallMs + totalSavedMs,
		totalSavedMs,
		speedSavedMs,
		silenceSavedMs,
		manualSkippedMs,
		introOutroSkippedMs,
		averageSpeed: totalWallMs ? speedWeightedMs / totalWallMs : 1,
		activeDays: byDay.size,
		longestStreak: longestStreak([...byDay.keys()]),
		completedCount: states.filter((state) => finishedByListening(state, listenedByEpisode)).length,
		showTotals: [...shows.values()]
			.map((show) => ({ id: show.id, title: show.title, ms: show.ms, episodes: show.episodes.size }))
			.sort((a, b) => b.ms - a.ms)
			.slice(0, 10),
		weekdayTotals,
		hourTotals,
		categoryTotals: [...categories.entries()]
			.map(([label, ms]) => ({ label, ms }))
			.sort((a, b) => b.ms - a.ms)
			.slice(0, 5),
		byDay
	};
}

export function finishedByListening(
	state: LocalPlaybackState,
	listenedByEpisode: ReadonlyMap<string, number>
): boolean {
	if (!state.completed) return false;
	const listenedMs = listenedByEpisode.get(state.episode_id) ?? 0;
	const durationMs = Math.max(0, state.duration_ms ?? 0);
	return durationMs > 0
		? listenedMs >= durationMs * 0.5
		: listenedMs >= 5 * 60_000;
}

function distributeAcrossHours(
	session: LocalListeningSession,
	wallMs: number,
	consume: (timestamp: number, portionMs: number) => void
) {
	const start = session.started_at;
	const end = Math.max(start, session.ended_at);
	const span = end - start;
	if (span <= 0 || wallMs <= 0) {
		if (wallMs > 0) consume(start, wallMs);
		return;
	}
	let cursor = start;
	while (cursor < end) {
		const nextHour = new Date(cursor);
		nextHour.setMinutes(60, 0, 0);
		const boundary = Math.min(end, nextHour.getTime());
		consume(cursor, wallMs * ((boundary - cursor) / span));
		cursor = boundary;
	}
}

function longestStreak(keys: string[]): number {
	const days = [...new Set(keys)]
		.map((key) => {
			const [year, month, day] = key.split('-').map(Number);
			return Date.UTC(year, month - 1, day);
		})
		.sort((a, b) => a - b);
	let longest = 0;
	let current = 0;
	let previous = 0;
	for (const day of days) {
		current = previous && day - previous === 86_400_000 ? current + 1 : 1;
		longest = Math.max(longest, current);
		previous = day;
	}
	return longest;
}
