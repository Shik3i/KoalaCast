export type ThemeMode = 'system' | 'dark' | 'light';

export function getStoredTheme(): ThemeMode {
	if (typeof window === 'undefined') return 'dark';
	const saved = localStorage.getItem('koalacast_theme');
	if (saved === 'dark' || saved === 'light' || saved === 'system') return saved;
	return 'dark';
}

export function setTheme(mode: ThemeMode) {
	if (typeof window === 'undefined') return;
	if (mode === 'system') {
		localStorage.setItem('koalacast_theme', 'system');
		document.documentElement.removeAttribute('data-theme');
	} else {
		localStorage.setItem('koalacast_theme', mode);
		document.documentElement.setAttribute('data-theme', mode);
	}
}
