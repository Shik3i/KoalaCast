import { describe, expect, it, vi } from 'vitest';
import {
	audioEffectsProxyUrl,
	isCrossOriginAudio,
	publisherAllowsAudioEffects,
	resolveAudioSourceForEffects
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

describe('publisherAllowsAudioEffects', () => {
	it('asks for a single byte instead of downloading the episode', async () => {
		let seen: RequestInit | undefined;
		const fetcher = (async (_url: string, init?: RequestInit) => {
			seen = init;
			return new Response('', { status: 206 });
		}) as unknown as typeof fetch;
		await publisherAllowsAudioEffects('https://cdn.publisher.test/a.mp3', origin, fetcher);
		expect((seen?.headers as Record<string, string>).Range).toBe('bytes=0-0');
		expect(seen?.mode).toBe('cors');
	});
});

describe('resolveAudioSourceForEffects', () => {
	const publisher = 'https://dts.podtrac.com/redirect.mp3/cdn.publisher.test/a.mp3';
	const finalUrl = 'https://cdn.publisher.test/a.mp3';

	it('uses the publisher directly when it already allows CORS', async () => {
		const resolve = vi.fn();
		const result = await resolveAudioSourceForEffects(publisher, origin, {
			allows: async () => true,
			resolve: resolve as never
		});
		expect(result).toMatchObject({ url: publisher, crossOrigin: true, via: 'direct' });
		// No point asking the server where a working URL leads.
		expect(resolve).not.toHaveBeenCalled();
	});

	/**
	 * The case this whole path exists for: a tracker prefix that blocks CORS in
	 * front of a CDN that allows it. Before, this fell straight through to the
	 * proxy and pulled the episode through the instance.
	 */
	it('follows a blocked tracker prefix to a CDN that allows CORS', async () => {
		const allows = vi.fn(async (url: string) => url === finalUrl);
		const result = await resolveAudioSourceForEffects(publisher, origin, {
			allows: allows as never,
			resolve: async () => ({ url: finalUrl, corsAllowed: true })
		});
		expect(result).toMatchObject({ url: finalUrl, crossOrigin: true, via: 'redirect-resolved' });
	});

	it('still proxies when the resolved host also refuses the browser', async () => {
		const result = await resolveAudioSourceForEffects(publisher, origin, {
			allows: async () => false,
			resolve: async () => ({ url: finalUrl, corsAllowed: true })
		});
		expect(result.via).toBe('proxy');
		expect(result.crossOrigin).toBe(false);
	});

	it('proxies when the redirect lookup fails outright', async () => {
		const result = await resolveAudioSourceForEffects(publisher, origin, {
			allows: async () => false,
			resolve: async () => null
		});
		expect(result.via).toBe('proxy');
	});

	it('leaves same-origin and offline audio alone', async () => {
		const result = await resolveAudioSourceForEffects('/offline/audio/x', origin, {
			allows: async () => false,
			resolve: async () => null
		});
		expect(result).toMatchObject({ url: '/offline/audio/x', crossOrigin: false, via: 'same-origin' });
	});
});
