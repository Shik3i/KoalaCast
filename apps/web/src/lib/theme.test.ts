import { describe, expect, it } from 'vitest';
import { COLOR_PALETTES, isPaletteId } from './theme';

describe('color palettes', () => {
	it('exposes six unique selectable palettes including eucalyptus', () => {
		const ids = COLOR_PALETTES.map((palette) => palette.id);

		expect(ids).toHaveLength(6);
		expect(new Set(ids).size).toBe(6);
		expect(ids).toContain('eucalyptus');
	});

	it('rejects invalid persisted palette values', () => {
		expect(isPaletteId('fjord')).toBe(true);
		expect(isPaletteId('unknown')).toBe(false);
		expect(isPaletteId(null)).toBe(false);
	});
});
