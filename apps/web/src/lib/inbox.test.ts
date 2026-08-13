import { describe, expect, it } from 'vitest';
import { dedupeInboxEpisodes } from './inbox';

describe('dedupeInboxEpisodes', () => {
	it('removes invalid and duplicate episode ids while keeping the newest value', () => {
		const result = dedupeInboxEpisodes([
			{ id: 'episode-1', title: 'old' },
			{ id: '', title: 'invalid' },
			{ id: null, title: 'invalid' },
			{ id: ' episode-1 ', title: 'new' },
			{ id: 'episode-2', title: 'other' }
		]);

		expect(result).toEqual([
			{ id: 'episode-1', title: 'new' },
			{ id: 'episode-2', title: 'other' }
		]);
	});
});
