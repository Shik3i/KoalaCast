export interface FeaturedPodcast {
	id: string;
	title: string;
	author: string;
	description: string;
	artwork_url: string;
	feed_url: string;
	category: string;
	episodeCount: number;
}

export const FEATURED_PODCASTS: FeaturedPodcast[] = [
	{
		id: 'syntax-fm',
		title: 'Syntax - Tasty Web Development',
		author: 'Wes Bos & Scott Tolinski',
		description: 'Full stack developers Wes Bos and Scott Tolinski break down web development concepts, JavaScript frameworks, CSS tricks, and developer lifestyle.',
		artwork_url: 'https://images.syntax.fm/syntax-banner.png',
		feed_url: 'https://feed.syntax.fm',
		category: 'Technology',
		episodeCount: 820
	},
	{
		id: 'changelog',
		title: 'The Changelog: Software Development',
		author: 'Changelog Media',
		description: 'Conversations with the hackers, leaders, and innovators of software development, open source, AI, and technology culture.',
		artwork_url: 'https://cdn.changelog.com/uploads/covers/the-changelog-original.png',
		feed_url: 'https://changelog.com/podcast/feed',
		category: 'Software Engineering',
		episodeCount: 590
	},
	{
		id: 'shoptalk',
		title: 'ShopTalk Show',
		author: 'Dave Rupert & Chris Coyier',
		description: 'A weekly podcast about front-end web design, UX, CSS, JavaScript, and web performance hosted by Dave Rupert and Chris Coyier.',
		artwork_url: 'https://shoptalkshow.com/wp-content/themes/shoptalk2021/images/shoptalk-logo.jpg',
		feed_url: 'https://shoptalkshow.com/feed/podcast/',
		category: 'Design & Code',
		episodeCount: 620
	},
	{
		id: 'ted-radio-hour',
		title: 'TED Radio Hour',
		author: 'NPR',
		description: 'Unlocking big ideas from the world’s most fascinating thinkers. Exploring life’s biggest questions through TED Talks and interviews.',
		artwork_url: 'https://media.npr.org/assets/img/2022/09/20/ted_radio_hour_sq_tile-4e2a716c5efdf393b4e6734c56e2eb9b32525dfd.jpg',
		feed_url: 'https://feeds.npr.org/510298/podcast.xml',
		category: 'Ideas & Science',
		episodeCount: 450
	},
	{
		id: 'bbc-global-news',
		title: 'Global News Podcast',
		author: 'BBC World Service',
		description: 'The biggest stories of the day from the BBC World Service. Top international news, reports, and analysis.',
		artwork_url: 'https://ichef.bbci.co.uk/images/ic/640x640/p02nq0gn.jpg',
		feed_url: 'https://podcasts.files.bbci.co.uk/p02nq0gn.rss',
		category: 'News & World',
		episodeCount: 1200
	},
	{
		id: 'huberman-lab',
		title: 'Huberman Lab',
		author: 'Dr. Andrew Huberman',
		description: 'Neuroscience and science-based tools for everyday life, sleep, focus, physical performance, and mental health.',
		artwork_url: 'https://hubermanlab.com/wp-content/uploads/2021/01/huberman-lab-artwork.jpg',
		feed_url: 'https://feeds.megaphone.fm/hubermanlab',
		category: 'Health & Science',
		episodeCount: 180
	}
];
