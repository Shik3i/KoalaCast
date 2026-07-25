// Small global preferences store (persisted to localStorage). Currently holds the
// date display mode so the whole app formats episode dates consistently and can
// switch between an absolute date and a relative "x days ago" style.

export type DateFormat = 'absolute' | 'relative';

const KEY = 'koalacast_date_format';
const INTERESTS_KEY = 'koalacast_interests';
const HIDDEN_KEY = 'koalacast_hidden_genres';
const LANGUAGES_KEY = 'koalacast_preferred_languages';
const ONBOARDED_KEY = 'koalacast_onboarded';

function initialLanguages(): string[] {
	if (typeof localStorage === 'undefined') return ['us', 'de'];
	try {
		const v = JSON.parse(localStorage.getItem(LANGUAGES_KEY) || '[]');
		return Array.isArray(v) && v.length > 0 ? v : ['us', 'de'];
	} catch (_) {
		return ['us', 'de'];
	}
}

function initialFormat(): DateFormat {
	if (typeof localStorage === 'undefined') return 'absolute';
	return localStorage.getItem(KEY) === 'relative' ? 'relative' : 'absolute';
}

function initialInterests(): string[] {
	if (typeof localStorage === 'undefined') return [];
	try {
		const v = JSON.parse(localStorage.getItem(INTERESTS_KEY) || '[]');
		return Array.isArray(v) ? v : [];
	} catch (_) {
		return [];
	}
}

function initialHidden(): string[] {
	if (typeof localStorage === 'undefined') return [];
	try {
		const v = JSON.parse(localStorage.getItem(HIDDEN_KEY) || '[]');
		return Array.isArray(v) ? v : [];
	} catch (_) {
		return [];
	}
}

function initialOnboarded(): boolean {
	if (typeof localStorage === 'undefined') return true; // never block SSR
	return localStorage.getItem(ONBOARDED_KEY) === '1';
}

class Prefs {
	dateFormat = $state<DateFormat>(initialFormat());
	// Chosen genre interests (explicit personalization seed). Also editable later
	// in Settings; kept on-device for privacy.
	interests = $state<string[]>(initialInterests());
	// Vetoed genres — podcasts in these are hidden from discover and search.
	hiddenGenres = $state<string[]>(initialHidden());
	// Preferred content languages/regions for trends and discovery.
	languages = $state<string[]>(initialLanguages());
	onboarded = $state<boolean>(initialOnboarded());

	#persistLanguages() {
		try {
			localStorage.setItem(LANGUAGES_KEY, JSON.stringify(this.languages));
		} catch (_) {}
	}

	toggleLanguage(langCode: string) {
		const has = this.languages.includes(langCode);
		if (has && this.languages.length === 1) {
			// At least one language must remain active
			return;
		}
		this.languages = has ? this.languages.filter((l) => l !== langCode) : [...this.languages, langCode];
		this.#persistLanguages();
	}

	setDateFormat(mode: DateFormat) {
		this.dateFormat = mode;
		try {
			localStorage.setItem(KEY, mode);
		} catch (_) {}
	}

	#persistInterests() {
		try {
			localStorage.setItem(INTERESTS_KEY, JSON.stringify(this.interests));
		} catch (_) {}
	}

	toggleInterest(genre: string) {
		const has = this.interests.includes(genre);
		this.interests = has ? this.interests.filter((g) => g !== genre) : [...this.interests, genre];
		// A genre can't be both an interest and hidden.
		if (!has && this.hiddenGenres.includes(genre)) {
			this.hiddenGenres = this.hiddenGenres.filter((g) => g !== genre);
			this.#persistHidden();
		}
		this.#persistInterests();
	}

	#persistHidden() {
		try {
			localStorage.setItem(HIDDEN_KEY, JSON.stringify(this.hiddenGenres));
		} catch (_) {}
	}

	toggleHidden(genre: string) {
		this.hiddenGenres = this.hiddenGenres.includes(genre)
			? this.hiddenGenres.filter((g) => g !== genre)
			: [...this.hiddenGenres, genre];
		if (this.interests.includes(genre)) {
			this.interests = this.interests.filter((g) => g !== genre);
			this.#persistInterests();
		}
		this.#persistHidden();
	}

	// True if a podcast's categories intersect the hidden set.
	isHidden(categories: string[] | undefined): boolean {
		if (!categories || this.hiddenGenres.length === 0) return false;
		const lower = this.hiddenGenres.map((g) => g.toLowerCase());
		return categories.some((c) => lower.includes((c || '').toLowerCase()));
	}

	completeOnboarding() {
		this.onboarded = true;
		try {
			localStorage.setItem(ONBOARDED_KEY, '1');
		} catch (_) {}
	}

	// Format an episode pub date (unix seconds) per the current setting.
	formatDate(sec?: number | null): string {
		if (!sec) return '';
		const date = new Date(sec * 1000);
		if (this.dateFormat === 'relative') return relative(date);
		return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
	}
}

function relative(date: Date): string {
	const diffMs = Date.now() - date.getTime();
	const sec = Math.round(diffMs / 1000);
	if (sec < 45) return 'just now';
	const min = Math.round(sec / 60);
	if (min < 60) return `${min} minute${min === 1 ? '' : 's'} ago`;
	const hours = Math.round(min / 60);
	if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
	const days = Math.round(hours / 24);
	if (days === 1) return 'yesterday';
	if (days < 7) return `${days} days ago`;
	const weeks = Math.round(days / 7);
	if (weeks < 5) return `${weeks} week${weeks === 1 ? '' : 's'} ago`;
	const months = Math.round(days / 30);
	if (months < 12) return `${months} month${months === 1 ? '' : 's'} ago`;
	const years = Math.round(days / 365);
	return `${years} year${years === 1 ? '' : 's'} ago`;
}

export const prefs = new Prefs();
