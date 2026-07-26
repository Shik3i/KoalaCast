export interface ProxyItem {
	label: string;
	text: string;
}

export interface PrivacySection {
	id: string;
	title: string;
	icon: string;
	highlight?: boolean;
	paragraphs?: string[];
	items?: { label?: string; text: string }[];
	proxyComparison?: {
		proxied: {
			title: string;
			items: ProxyItem[];
		};
		direct: {
			title: string;
			items: ProxyItem[];
		};
	};
	legalBasis?: string;
	legalLinks?: { href: string; label: string; icon: string }[];
}

export const PRIVACY_SECTIONS: PrivacySection[] = [
	{
		id: 'operator-hosting',
		title: '1. Operator and hosting',
		icon: 'ph-buildings',
		paragraphs: [
			'The operator named in the Legal Notice (Impressum) is responsible for this service. KoalaCast is hosted on infrastructure provided by Hetzner Online GmbH, Industriestr. 25, 91710 Gunzenhausen, Germany. The regular hosting location is within the EU/EEA.'
		],
		legalBasis: 'Art. 6(1)(f) GDPR, legitimate interest in reliable service delivery, technical troubleshooting, and secure operation.'
	},
	{
		id: 'connection-security',
		title: '2. Connection and security data',
		icon: 'ph-shield-warning',
		paragraphs: [
			'The web server keeps access logs for seven days. These logs may contain the IP address, request date and time, requested HTTP method and path, response status, referrer, and browser user agent. They are used to operate and troubleshoot the service and to detect and block bad actors, spam, denial-of-service (DoS) attacks, and other abuse. Access logs are not used for advertising, profiling, or audience analytics and are deleted automatically after seven days.',
			'KoalaCast also uses the current network address temporarily in memory for rate limiting and abuse prevention. When a network address is stored with a session or security audit event, IPv4 addresses are reduced to their /24 network (e.g. 192.168.1.0) and IPv6 addresses to at most the first three groups (e.g. 2001:db8::). A shortened browser user agent may also be stored for session security.'
		],
		legalBasis: 'Art. 6(1)(f) GDPR, legitimate interest in reliable service delivery, technical troubleshooting, secure operation, and abuse prevention.'
	},
	{
		id: 'local-mode',
		title: '3. Local Browser Mode',
		icon: 'ph-hard-drive',
		paragraphs: [
			'KoalaCast is 100% functional without an account. By default, your data is stored locally in your browser:'
		],
		items: [
			{ label: 'Subscriptions & Queue', text: 'Your podcast subscriptions, episode queue, and playback progress are stored locally on your device in IndexedDB.' },
			{ label: 'Listening History', text: 'Your history remains on your local device unless you explicitly opt into cross-device sync.' },
			{ label: 'Preferences', text: 'Theme choices and genre preferences are saved in LocalStorage.' }
		],
		legalBasis: 'Art. 6(1)(b) GDPR, providing the requested local application functionality.'
	},
	{
		id: 'server-proxying',
		title: '4. Server Proxying & External Metadata',
		icon: 'ph-arrows-merge',
		paragraphs: [
			'To protect your IP address from third-party networks and bypass CORS restrictions, KoalaCast handles external metadata through the backend proxy:'
		],
		proxyComparison: {
			proxied: {
				title: 'Proxied by KoalaCast Backend:',
				items: [
					{ label: 'Artwork Proxy (/api/v1/proxy/image)', text: 'Podcast cover images are fetched by the server, Catmull-Rom downscaled, compressed to JPEG, and cached in a 100MB RAM LRU cache. Third-party image hosts do not see client IP addresses.' },
					{ label: 'Search & Discovery', text: 'Search queries (iTunes Search API & PodcastIndex API) are executed by the Go backend. Search APIs see only the KoalaCast server IP address.' },
					{ label: 'RSS Feed Parsing', text: 'Podcast RSS XML feed files are fetched and parsed on the server side.' },
					{ label: 'Chapters & Transcripts', text: 'Episode chapter JSON and WebVTT/SRT transcript files are fetched via CORS-safe proxy endpoints (/api/v1/proxy/chapters, /api/v1/proxy/transcript).' }
				]
			},
			direct: {
				title: 'Direct Connection (NOT Proxied):',
				items: [
					{ label: 'Audio Streams (MP3 / AAC)', text: "Audio media files are streamed directly from podcast publishers' CDNs (e.g. Libsyn, Megaphone, Podbean, Anchor, AWS S3) to your browser HTML5 player." },
					{ label: 'Reasoning', text: 'KoalaCast servers do not proxy or re-encode gigabytes of audio data, ensuring zero server latency and minimal resource overhead.' },
					{ label: 'Publisher Metadata', text: "When playing an episode, the publisher's CDN receives standard HTTP GET requests directly from your browser, containing your IP address and User-Agent header as required for web audio delivery." }
				]
			}
		},
		legalBasis: 'Art. 6(1)(f) GDPR, privacy-preserving proxying and CORS compatibility.'
	},
	{
		id: 'accounts-sessions',
		title: '5. Accounts and sessions',
		icon: 'ph-user-circle',
		paragraphs: [
			'Account and session records are personal data when they relate to an identifiable user. For registered accounts, KoalaCast stores a public username, account status, role, account creation/login timestamps, and a bcrypt password hash. It does not require an email address and never stores the password itself. Account data is retained until deletion is requested or required for account administration.',
			'Login uses one essential HttpOnly session cookie (koalacast_session). Only a SHA-256 hash of its random token is stored on the server. Sessions expire after 30 days by default, are invalidated on logout, and are removed after expiry. This cookie is strictly necessary for requested login functionality, so no consent banner is required for it.',
			'Optional cross-device sync synchronizes user subscriptions, episode queue, playback progress, and listening-session statistics to the server\'s SQLite database.'
		],
		legalBasis: 'Art. 6(1)(b) GDPR, providing the requested account and sync service, and Art. 6(1)(f) GDPR, account security.'
	},
	{
		id: 'no-tracking',
		title: '6. No advertising or client tracking',
		icon: 'ph-lock-key',
		highlight: true,
		paragraphs: [
			'KoalaCast does not use third-party analytics (no Google Analytics, no Plausible/Matomo scripts), tracking cookies, advertising networks, social media embeds, or behavioral profiling. It does not use automated decision-making within the meaning of Art. 22 GDPR.'
		]
	},
	{
		id: 'external-links',
		title: '7. External links',
		icon: 'ph-arrow-square-out',
		paragraphs: [
			'GitHub, license, and publisher links are ordinary external links, not embedded trackers. Opening one sends the usual connection data to that provider under its own privacy policy.'
		]
	},
	{
		id: 'your-rights',
		title: '8. Your rights & Data Control',
		icon: 'ph-scales',
		paragraphs: [
			'Under the GDPR, you have the right to access (Art. 15), rectification (Art. 16), erasure (Art. 17), restriction of processing (Art. 18), data portability (Art. 20), objection (Art. 21), and lodging a complaint with a supervisory authority.'
		],
		items: [
			{ label: 'OPML Export', text: 'Export or import your subscriptions at any time in Settings.' },
			{ label: 'Local Data Wipe', text: 'Clear all client-side stored IndexedDB/LocalStorage data in Settings.' },
			{ label: 'Session Management', text: 'Review and revoke active account sessions at any time in Settings.' }
		],
		legalLinks: [
			{ href: 'https://koalastuff.net/legal', label: 'Legal Notice (Impressum)', icon: 'ph-scales' },
			{ href: 'https://koalastuff.net/privacy', label: 'koalastuff.net Privacy', icon: 'ph-arrow-square-out' }
		]
	}
];
