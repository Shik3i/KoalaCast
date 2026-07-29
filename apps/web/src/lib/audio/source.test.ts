import { describe, expect, it, vi } from 'vitest';
import {
	audioEffectsProxyUrl,
	isCrossOriginAudio,
	publisherAllowsAudioEffects
} from './source';

const origin = 'https://cast.example';

describe('audio source selection for Web Audio effects', () => {
	it('proxies cross-origin publisher audio', () => {
		const source = 'https://cdn.publisher.test/episode.mp3?token=a&part=1';
		expect(isCrossOriginAudio(source, origin)).toBe(true);
		expect(audioEffectsProxyUrl(source)).toBe(
			'/api/v1/proxy/audio?url=https%3A%2F%2Fcdn.publisher.test%2Fepisode.mp3%3Ftoken%3Da%26part%3D1'
		);
	});

	it('recognizes same-origin and offline audio', () => {
		expect(isCrossOriginAudio('/audio/episode.mp3', origin)).toBe(false);
		expect(isCrossOriginAudio('/offline/audio/episode-id', origin)).toBe(false);
	});

	it('accepts direct effects only after a successful CORS response', async () => {
		const allowed = vi.fn(async () => new Response('', { status: 206 }));
		const blocked = vi.fn(async () => {
			throw new TypeError('Failed to fetch');
		});
		expect(await publisherAllowsAudioEffects('https://cdn.publisher.test/a.mp3', origin, allowed)).toBe(true);
		expect(await publisherAllowsAudioEffects('https://cdn.publisher.test/a.mp3', origin, blocked)).toBe(false);
	});
});
