// Genre catalogue for the interests / hidden-genres picker. The `name` doubles as
// the value sent to /api/v1/podcasts/discover?category=… and matched against a
// podcast's own categories for filtering. This mirrors Apple Podcasts' top-level
// categories (which Podcast Index also understands).
//
// `name` is therefore a protocol value and must stay English. `id` is the
// translation key used for display — see `genreLabel`.

import { t, type MessageKey } from '$lib/i18n';

export interface Genre {
	/** Translation key suffix under `genres.*`. Display only. */
	id: string;
	/** Wire value sent to the API. Never translate. */
	name: string;
	icon: string;
}

export const GENRES: Genre[] = [
	{ id: 'arts', name: 'Arts', icon: 'ph-palette' },
	{ id: 'business', name: 'Business', icon: 'ph-briefcase' },
	{ id: 'comedy', name: 'Comedy', icon: 'ph-smiley' },
	{ id: 'education', name: 'Education', icon: 'ph-graduation-cap' },
	{ id: 'fiction', name: 'Fiction', icon: 'ph-book-open' },
	{ id: 'government', name: 'Government', icon: 'ph-bank' },
	{ id: 'healthFitness', name: 'Health & Fitness', icon: 'ph-heartbeat' },
	{ id: 'history', name: 'History', icon: 'ph-scroll' },
	{ id: 'kidsFamily', name: 'Kids & Family', icon: 'ph-baby' },
	{ id: 'leisure', name: 'Leisure', icon: 'ph-game-controller' },
	{ id: 'music', name: 'Music', icon: 'ph-music-notes' },
	{ id: 'news', name: 'News', icon: 'ph-newspaper' },
	{ id: 'religionSpirituality', name: 'Religion & Spirituality', icon: 'ph-hands-praying' },
	{ id: 'science', name: 'Science', icon: 'ph-atom' },
	{ id: 'societyCulture', name: 'Society & Culture', icon: 'ph-users-three' },
	{ id: 'sports', name: 'Sports', icon: 'ph-basketball' },
	{ id: 'technology', name: 'Technology', icon: 'ph-cpu' },
	{ id: 'tvFilm', name: 'TV & Film', icon: 'ph-film-slate' },
	{ id: 'trueCrime', name: 'True Crime', icon: 'ph-fingerprint' }
];

const BY_NAME = new Map(GENRES.map((g) => [g.name.toLowerCase(), g]));

/**
 * Display label for a genre. Takes the wire value ("True Crime") and returns the
 * translated name. Categories that came from a feed and are not in our catalogue
 * are passed through untouched — a publisher's own category is not ours to
 * rewrite.
 */
export function genreLabel(name: string): string {
	if (!name) return '';
	if (name.toLowerCase() === 'all') return t('genres.all');
	const genre = BY_NAME.get(name.toLowerCase());
	return genre ? t(`genres.${genre.id}` as MessageKey) : name;
}
