// Genre catalogue for the interests / hidden-genres picker. The `name` doubles as
// the value sent to /api/v1/podcasts/discover?category=… and matched against a
// podcast's own categories for filtering. This mirrors Apple Podcasts' top-level
// categories (which Podcast Index also understands).
export interface Genre {
	name: string;
	icon: string;
}

export const GENRES: Genre[] = [
	{ name: 'Arts', icon: 'ph-palette' },
	{ name: 'Business', icon: 'ph-briefcase' },
	{ name: 'Comedy', icon: 'ph-smiley' },
	{ name: 'Education', icon: 'ph-graduation-cap' },
	{ name: 'Fiction', icon: 'ph-book-open' },
	{ name: 'Government', icon: 'ph-bank' },
	{ name: 'Health & Fitness', icon: 'ph-heartbeat' },
	{ name: 'History', icon: 'ph-scroll' },
	{ name: 'Kids & Family', icon: 'ph-baby' },
	{ name: 'Leisure', icon: 'ph-game-controller' },
	{ name: 'Music', icon: 'ph-music-notes' },
	{ name: 'News', icon: 'ph-newspaper' },
	{ name: 'Religion & Spirituality', icon: 'ph-hands-praying' },
	{ name: 'Science', icon: 'ph-atom' },
	{ name: 'Society & Culture', icon: 'ph-users-three' },
	{ name: 'Sports', icon: 'ph-basketball' },
	{ name: 'Technology', icon: 'ph-cpu' },
	{ name: 'TV & Film', icon: 'ph-film-slate' },
	{ name: 'True Crime', icon: 'ph-fingerprint' }
];
