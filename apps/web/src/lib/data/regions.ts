export interface RegionConfig {
	code: string;
	name: string;
	flag: string;
}

export const SUPPORTED_REGIONS: RegionConfig[] = [
	{ code: 'us', name: 'Global / English', flag: '🇺🇸' },
	{ code: 'de', name: 'Deutsch', flag: '🇩🇪' },
	{ code: 'fr', name: 'Français', flag: '🇫🇷' },
	{ code: 'es', name: 'Español', flag: '🇪🇸' }
];

export const DEFAULT_REGION = 'us';
