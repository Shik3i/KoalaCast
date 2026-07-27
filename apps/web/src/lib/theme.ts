import type { MessageKey } from '$lib/i18n';

export type ThemeMode = 'system' | 'dark' | 'light';
export type PaletteId =
	| 'eucalyptus'
	| 'fjord'
	| 'ember'
	| 'lavender'
	| 'aurora'
	| 'sandstone'
	| 'obsidian'
	| 'paper'
	| 'ultraviolet';

export const DEFAULT_PALETTE: PaletteId = 'fjord';

export interface ColorPalette {
	id: PaletteId;
	labelKey: MessageKey;
	descriptionKey: MessageKey;
	swatches: readonly [string, string, string, string];
}

export const COLOR_PALETTES: readonly ColorPalette[] = [
	{
		id: 'eucalyptus',
		labelKey: 'settings.paletteEucalyptus',
		descriptionKey: 'settings.paletteEucalyptusDescription',
		swatches: ['#06100c', '#0a0f0c', '#7fd0aa', '#eaf6f0']
	},
	{
		id: 'fjord',
		labelKey: 'settings.paletteFjord',
		descriptionKey: 'settings.paletteFjordDescription',
		swatches: ['#07101a', '#0b131d', '#79c7e8', '#e9f3fa']
	},
	{
		id: 'ember',
		labelKey: 'settings.paletteEmber',
		descriptionKey: 'settings.paletteEmberDescription',
		swatches: ['#140b08', '#17100d', '#f29a62', '#fff1e8']
	},
	{
		id: 'lavender',
		labelKey: 'settings.paletteLavender',
		descriptionKey: 'settings.paletteLavenderDescription',
		swatches: ['#100b18', '#130e1b', '#c7a3ff', '#f4edff']
	},
	{
		id: 'aurora',
		labelKey: 'settings.paletteAurora',
		descriptionKey: 'settings.paletteAuroraDescription',
		swatches: ['#041114', '#071518', '#55d8cf', '#e6fbfb']
	},
	{
		id: 'sandstone',
		labelKey: 'settings.paletteSandstone',
		descriptionKey: 'settings.paletteSandstoneDescription',
		swatches: ['#130f0a', '#17130e', '#d7b46a', '#f8f0df']
	},
	{
		id: 'obsidian',
		labelKey: 'settings.paletteObsidian',
		descriptionKey: 'settings.paletteObsidianDescription',
		swatches: ['#050505', '#111111', '#d9d9d9', '#ffffff']
	},
	{
		id: 'paper',
		labelKey: 'settings.palettePaper',
		descriptionKey: 'settings.palettePaperDescription',
		swatches: ['#171511', '#211e18', '#d7c6a3', '#f6efdf']
	},
	{
		id: 'ultraviolet',
		labelKey: 'settings.paletteUltraviolet',
		descriptionKey: 'settings.paletteUltravioletDescription',
		swatches: ['#0b0714', '#130d22', '#e35cff', '#fbf4ff']
	}
] as const;

const PALETTE_IDS = new Set<PaletteId>(COLOR_PALETTES.map((palette) => palette.id));

export function isPaletteId(value: string | null): value is PaletteId {
	return value !== null && PALETTE_IDS.has(value as PaletteId);
}

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

export function getStoredPalette(): PaletteId {
	if (typeof window === 'undefined') return DEFAULT_PALETTE;
	const saved = localStorage.getItem('koalacast_palette');
	return isPaletteId(saved) ? saved : DEFAULT_PALETTE;
}

export function setPalette(palette: PaletteId) {
	if (typeof window === 'undefined') return;
	localStorage.setItem('koalacast_palette', palette);
	document.documentElement.setAttribute('data-palette', palette);
}
