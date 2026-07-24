// Small global preferences store (persisted to localStorage). Currently holds the
// date display mode so the whole app formats episode dates consistently and can
// switch between an absolute date and a relative "x days ago" style.

export type DateFormat = 'absolute' | 'relative';

const KEY = 'koalacast_date_format';

function initialFormat(): DateFormat {
	if (typeof localStorage === 'undefined') return 'absolute';
	return localStorage.getItem(KEY) === 'relative' ? 'relative' : 'absolute';
}

class Prefs {
	dateFormat = $state<DateFormat>(initialFormat());

	setDateFormat(mode: DateFormat) {
		this.dateFormat = mode;
		try {
			localStorage.setItem(KEY, mode);
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
