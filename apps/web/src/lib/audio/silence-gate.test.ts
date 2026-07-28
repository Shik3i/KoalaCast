import { describe, expect, it } from 'vitest';
import { SilenceGate } from './silence-gate';

describe('SilenceGate', () => {
	it('waits for sustained silence before activating', () => {
		const gate = new SilenceGate();
		expect(gate.update(0.005, 0)).toBe(false);
		expect(gate.update(0.005, 249)).toBe(false);
		expect(gate.update(0.005, 250)).toBe(true);
	});

	it('uses hysteresis so a single speech peak does not flap the rate', () => {
		const gate = new SilenceGate();
		gate.update(0.005, 0);
		expect(gate.update(0.005, 250)).toBe(true);
		expect(gate.update(0.03, 300)).toBe(true);
		expect(gate.update(0.03, 379)).toBe(true);
		expect(gate.update(0.03, 380)).toBe(false);
	});

	it('resets invalid samples and pending transitions', () => {
		const gate = new SilenceGate();
		gate.update(0.005, 0);
		expect(gate.update(Number.NaN, 200)).toBe(false);
		expect(gate.update(0.005, 300)).toBe(false);
		expect(gate.update(0.005, 550)).toBe(true);
		expect(gate.reset()).toBe(false);
	});
});
