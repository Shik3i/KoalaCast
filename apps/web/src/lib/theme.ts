export type ThemeMode = 'system' | 'dark' | 'light';

export function getStoredTheme(): ThemeMode {
	if (typeof window === 'undefined') return 'system';
	const saved = localStorage.getItem('koalacast_theme');
	if (saved === 'dark' || saved === 'light') return saved;
	return 'system';
}

export function setTheme(mode: ThemeMode) {
	if (typeof window === 'undefined') return;
	if (mode === 'system') {
		localStorage.removeItem('koalacast_theme');
		document.documentElement.removeAttribute('data-theme');
	} else {
		localStorage.setItem('koalacast_theme', mode);
		document.documentElement.setAttribute('data-theme', mode);
	}
}
