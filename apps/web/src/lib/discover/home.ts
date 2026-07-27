export interface DiscoverPodcast {
	id: string;
	title: string;
	author: string;
	category?: string;
	categories?: string[];
	latestDurationMs?: number;
	latestPublishedAt?: number;
	sourceRank?: number;
}

export type DiscoverMood = 'calm' | 'curious' | 'company' | 'focus';
export type DiscoverSort = 'momentum' | 'rank' | 'length' | 'newest';

const MOOD_CATEGORIES: Record<DiscoverMood, string[]> = {
	calm: ['health & fitness', 'religion & spirituality', 'music', 'leisure', 'arts', 'education'],
	curious: ['science', 'technology', 'history', 'education', 'society & culture', 'news'],
	company: ['comedy', 'society & culture', 'kids & family', 'leisure', 'sports'],
	focus: ['business', 'education', 'technology', 'science', 'news']
};

const CATEGORY_ALIASES: Record<string, string> = {
	kunst: 'arts',
	wirtschaft: 'business',
	bildung: 'education',
	'gesundheit & fitness': 'health & fitness',
	'gesundheit und fitness': 'health & fitness',
	geschichte: 'history',
	'kinder & familie': 'kids & family',
	'kinder und familie': 'kids & family',
	freizeit: 'leisure',
	musik: 'music',
	nachrichten: 'news',
	politik: 'news',
	'religion & spiritualität': 'religion & spirituality',
	'religion und spiritualität': 'religion & spirituality',
	wissenschaft: 'science',
	'gesellschaft & kultur': 'society & culture',
	'gesellschaft und kultur': 'society & culture',
	sport: 'sports',
	technologie: 'technology'
};

function categories(podcast: DiscoverPodcast): string[] {
	return [...(podcast.categories ?? []), podcast.category ?? '']
		.map((category) => category.trim().toLowerCase())
		.map((category) => CATEGORY_ALIASES[category] ?? category)
		.filter(Boolean);
}

function moodScore(podcast: DiscoverPodcast, mood: DiscoverMood): number {
	const values = categories(podcast);
	const priorities = MOOD_CATEGORIES[mood];
	const categoryScore = priorities.reduce(
		(score, category, index) => score + (values.includes(category) ? priorities.length - index : 0),
		0
	);
	return categoryScore;
}

export function arrangeDiscover(
	podcasts: DiscoverPodcast[],
	options: {
		mood: DiscoverMood;
		sort: DiscoverSort;
		sessionMinutes: number | null;
		fitsSession: boolean;
	}
): DiscoverPodcast[] {
	const sessionMs = (options.sessionMinutes ?? 0) * 60_000;
	const filtered = options.fitsSession && options.sessionMinutes !== null
		? podcasts.filter(
				(podcast) =>
					typeof podcast.latestDurationMs !== 'number' ||
					podcast.latestDurationMs <= 0 ||
					podcast.latestDurationMs <= sessionMs
			)
		: [...podcasts];

	return filtered
		.map((podcast, index) => ({
			podcast,
			index,
			mood: moodScore(podcast, options.mood),
			verifiedFit:
				options.fitsSession &&
				options.sessionMinutes !== null &&
				typeof podcast.latestDurationMs === 'number' &&
				podcast.latestDurationMs > 0 &&
				podcast.latestDurationMs <= sessionMs
					? 1
					: 0
		}))
		.sort((a, b) => {
			if (a.verifiedFit !== b.verifiedFit) return b.verifiedFit - a.verifiedFit;
			if (options.sort === 'length') {
				const aDuration = a.podcast.latestDurationMs ?? Number.POSITIVE_INFINITY;
				const bDuration = b.podcast.latestDurationMs ?? Number.POSITIVE_INFINITY;
				return aDuration - bDuration || a.index - b.index;
			}
			if (options.sort === 'newest') {
				return (b.podcast.latestPublishedAt ?? 0) - (a.podcast.latestPublishedAt ?? 0) || a.index - b.index;
			}
			if (options.sort === 'rank') {
				return (a.podcast.sourceRank ?? a.index) - (b.podcast.sourceRank ?? b.index);
			}
			return b.mood - a.mood || (a.podcast.sourceRank ?? a.index) - (b.podcast.sourceRank ?? b.index);
		})
		.map(({ podcast }) => podcast);
}

export function formatEpisodeMinutes(durationMs?: number): string | null {
	if (!durationMs || durationMs <= 0) return null;
	const ms = durationMs < 100_000 ? durationMs * 1000 : durationMs;
	const totalMinutes = Math.round(ms / 60_000);
	if (totalMinutes <= 0) return null;
	if (totalMinutes < 60) return `${totalMinutes}m`;
	const hours = Math.floor(totalMinutes / 60);
	const minutes = totalMinutes % 60;
	return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
}
