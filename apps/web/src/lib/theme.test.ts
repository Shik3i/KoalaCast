import { describe, expect, it } from 'vitest';
import { COLOR_PALETTES, isPaletteId } from './theme';

describe('color palettes', () => {
	it('exposes nine unique selectable palettes including eucalyptus and monochrome obsidian', () => {
		const ids = COLOR_PALETTES.map((palette) => palette.id);

		expect(ids).toHaveLength(9);
		expect(new Set(ids).size).toBe(9);
		expect(ids).toContain('eucalyptus');
		expect(ids).toContain('obsidian');
		expect(COLOR_PALETTES.find((palette) => palette.id === 'obsidian')?.swatches.every((swatch) => {
			const [, red, green, blue] = swatch.match(/^#(..)(..)(..)$/) ?? [];
			return red === green && green === blue;
		})).toBe(true);
	});

	it('rejects invalid persisted palette values', () => {
		expect(isPaletteId('fjord')).toBe(true);
		expect(isPaletteId('unknown')).toBe(false);
		expect(isPaletteId(null)).toBe(false);
	});
});
