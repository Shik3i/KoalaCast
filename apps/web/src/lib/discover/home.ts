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

function categories(podcast: DiscoverPodcast): string[] {
	return [...(podcast.categories ?? []), podcast.category ?? '']
		.map((category) => category.trim().toLowerCase())
		.filter(Boolean);
}

function moodScore(podcast: DiscoverPodcast, mood: DiscoverMood): number {
	const values = categories(podcast);
	const priorities = MOOD_CATEGORIES[mood];
	const categoryScore = priorities.reduce(
		(score, category, index) => score + (values.includes(category) ? priorities.length - index : 0),
		0
	);
	const text = `${podcast.title} ${podcast.author}`.toLowerCase();
	const textTerms: Record<DiscoverMood, string[]> = {
		calm: ['calm', 'sleep', 'mindful', 'wellness', 'meditation'],
		curious: ['science', 'history', 'learn', 'idea', 'technology'],
		company: ['comedy', 'chat', 'talk', 'friends', 'daily'],
		focus: ['business', 'work', 'code', 'news', 'productivity']
	};
	return categoryScore * 10 + textTerms[mood].filter((term) => text.includes(term)).length;
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
					typeof podcast.latestDurationMs === 'number' &&
					podcast.latestDurationMs > 0 &&
					podcast.latestDurationMs <= sessionMs
			)
		: [...podcasts];

	return filtered
		.map((podcast, index) => ({ podcast, index, mood: moodScore(podcast, options.mood) }))
		.sort((a, b) => {
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
	return `${Math.max(1, Math.round(durationMs / 60_000))}m`;
}
