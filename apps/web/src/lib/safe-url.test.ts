import { describe, expect, it } from 'vitest';
import { safeExternalHref } from './safe-url';

describe('safeExternalHref', () => {
	it('accepts absolute HTTP and HTTPS links', () => {
		expect(safeExternalHref('https://example.com/support?q=1')).toBe(
			'https://example.com/support?q=1'
		);
		expect(safeExternalHref(' http://example.com/live ')).toBe('http://example.com/live');
	});

	it('rejects executable, relative and credential-bearing links', () => {
		expect(safeExternalHref('javascript:alert(1)')).toBeNull();
		expect(safeExternalHref('data:text/html,hello')).toBeNull();
		expect(safeExternalHref('/support')).toBeNull();
		expect(safeExternalHref('https://listener:secret@example.com/')).toBeNull();
	});

	it('rejects empty and malformed values', () => {
		expect(safeExternalHref('')).toBeNull();
		expect(safeExternalHref('https://')).toBeNull();
		expect(safeExternalHref(null)).toBeNull();
	});
});
