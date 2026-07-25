export interface LanguageConfig {
	code: string;
	name: string;
	flag: string;
}

export const SUPPORTED_LANGUAGES: LanguageConfig[] = [
	{ code: 'us', name: 'English', flag: '🇬🇧' },
	{ code: 'de', name: 'Deutsch', flag: '🇩🇪' },
	{ code: 'fr', name: 'Français', flag: '🇫🇷' },
	{ code: 'es', name: 'Español', flag: '🇪🇸' }
];

export const DEFAULT_LANGUAGES = ['us', 'de'];
