import { describe, expect, it } from 'vitest';
import { arrangeDiscover, formatEpisodeMinutes, type DiscoverPodcast } from './home';

const podcasts: DiscoverPodcast[] = [
	{ id: 'news', title: 'Daily Focus', author: 'A', categories: ['News'], latestDurationMs: 50 * 60_000, latestPublishedAt: 10, sourceRank: 0 },
	{ id: 'science', title: 'Science Weekly', author: 'B', categories: ['Science'], latestDurationMs: 25 * 60_000, latestPublishedAt: 30, sourceRank: 1 },
	{ id: 'comedy', title: 'Friends Chat', author: 'C', categories: ['Comedy'], latestDurationMs: 35 * 60_000, latestPublishedAt: 20, sourceRank: 2 }
];

describe('arrangeDiscover', () => {
	it('changes recommendations when the selected mood changes', () => {
		expect(arrangeDiscover(podcasts, { mood: 'curious', sort: 'momentum', sessionMinutes: 40, fitsSession: false })[0].id).toBe('science');
		expect(arrangeDiscover(podcasts, { mood: 'company', sort: 'momentum', sessionMinutes: 40, fitsSession: false })[0].id).toBe('comedy');
	});

	it('only applies the session filter when explicitly enabled', () => {
		expect(arrangeDiscover(podcasts, { mood: 'focus', sort: 'rank', sessionMinutes: 40, fitsSession: false })).toHaveLength(3);
		expect(arrangeDiscover(podcasts, { mood: 'focus', sort: 'rank', sessionMinutes: 40, fitsSession: true }).map((podcast) => podcast.id)).toEqual(['science', 'comedy']);
	});

	it('sorts by real duration and publication time', () => {
		expect(arrangeDiscover(podcasts, { mood: 'calm', sort: 'length', sessionMinutes: 60, fitsSession: false }).map((podcast) => podcast.id)).toEqual(['science', 'comedy', 'news']);
		expect(arrangeDiscover(podcasts, { mood: 'calm', sort: 'newest', sessionMinutes: 60, fitsSession: false }).map((podcast) => podcast.id)).toEqual(['science', 'comedy', 'news']);
	});
});

describe('formatEpisodeMinutes', () => {
	it('never invents a duration when metadata is missing', () => {
		expect(formatEpisodeMinutes()).toBeNull();
		expect(formatEpisodeMinutes(25 * 60_000)).toBe('25m');
	});
});
