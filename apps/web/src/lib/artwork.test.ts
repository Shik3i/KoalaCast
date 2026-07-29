import { describe, expect, it } from 'vitest';
import { optimizeArtwork } from './artwork';

describe('optimizeArtwork', () => {
	it('keeps app-local paths local', () => {
		expect(optimizeArtwork('/cover.webp', 96)).toBe('/cover.webp');
	});

	it('routes protocol-relative publisher artwork through the proxy', () => {
		expect(optimizeArtwork('//cdn.publisher.test/cover.jpg', 96)).toBe(
			'/api/v1/proxy/image?url=https%3A%2F%2Fcdn.publisher.test%2Fcover.jpg&w=96'
		);
	});
});
