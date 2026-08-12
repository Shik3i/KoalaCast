import { expect, test } from '@playwright/test';

function toneWav() {
	const sampleRate = 8_000;
	// Long enough for effect-source negotiation and all four visualizer assertions.
	// The former two-second fixture could end and close the player before the
	// visualizer became visible on a slower CI machine.
	const samples = sampleRate * 30;
	const wav = Buffer.alloc(44 + samples * 2);
	wav.write('RIFF', 0);
	wav.writeUInt32LE(wav.length - 8, 4);
	wav.write('WAVEfmt ', 8);
	wav.writeUInt32LE(16, 16);
	wav.writeUInt16LE(1, 20);
	wav.writeUInt16LE(1, 22);
	wav.writeUInt32LE(sampleRate, 24);
	wav.writeUInt32LE(sampleRate * 2, 28);
	wav.writeUInt16LE(2, 32);
	wav.writeUInt16LE(16, 34);
	wav.write('data', 36);
	wav.writeUInt32LE(samples * 2, 40);
	for (let index = 0; index < samples; index++) {
		wav.writeInt16LE(Math.round(Math.sin(index / 8) * 8_000), 44 + index * 2);
	}
	return wav;
}

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		localStorage.setItem('koalacast_onboarded', '1');
		localStorage.setItem('koalacast_ui_language', 'en');
		localStorage.removeItem('koalacast_volume_boost');
		localStorage.removeItem('koalacast_skip_silence');
		localStorage.removeItem('koalacast_visualizer');

		const NativeAudioContext = window.AudioContext;
		(window as any).__audioContextCount = 0;
		if (NativeAudioContext) {
			window.AudioContext = new Proxy(NativeAudioContext, {
				construct(target, args) {
					(window as any).__audioContextCount += 1;
					return Reflect.construct(target, args);
				}
			});
		}
	});

	await page.route('**/api/v1/podcasts/test-show/episodes*', async (route) => {
		await route.fulfill({
			json: {
				episodes: [
					{
						id: 'test-episode',
						podcast_id: 'test-show',
						title: 'The first useful episode',
						description: 'The episode users came here to hear.',
						pub_date: Math.floor(Date.now() / 1_000),
						duration_ms: 2_000,
					enclosure_url: '/test-audio.wav'
					}
				]
			}
		});
	});
	await page.route('**/test-audio.wav', async (route) => {
		await route.fulfill({ body: toneWav(), contentType: 'audio/wav' });
	});
	await page.route(/\/api\/v1\/podcasts\/test-show(?:\?.*)?$/, async (route) => {
		await route.fulfill({
			json: {
				id: 'test-show',
				title: 'Compact Test Show',
				author: 'KoalaCast QA',
				description: '<p>A description with feed markup.</p>',
				artwork_url: '/cover-placeholder.webp'
			}
		});
	});
});

test('show options and filters start collapsed with an episode in the first viewport', async ({ page }) => {
	await page.goto('/podcast/test-show');

	const showOptions = page.locator('details.show-controls');
	const episodeFilters = page.locator('details.episode-filters');
	await expect(showOptions).not.toHaveAttribute('open', '');
	await expect(episodeFilters).not.toHaveAttribute('open', '');
	await expect(page.locator('.podcast-header .desc')).toHaveText('A description with feed markup.');
	await expect(page.getByText('<p>A description with feed markup.</p>')).toHaveCount(0);

	const firstEpisode = page.locator('.episode-row');
	await expect(firstEpisode).toBeVisible();
	const box = await firstEpisode.boundingBox();
	expect(box?.y ?? Number.POSITIVE_INFINITY).toBeLessThan(620);
});

test('ordinary playback does not route audio through a suspended AudioContext', async ({ page }) => {
	await page.goto('/podcast/test-show');
	await page.getByRole('button', { name: 'Play episode', exact: true }).click();

	await expect.poll(() => page.locator('audio').evaluate((audio) => !(audio as HTMLAudioElement).paused))
		.toBe(true);
	await expect.poll(() => page.evaluate(() => (window as any).__audioContextCount)).toBe(0);
	const transport = page.locator('.play-btn');
	await expect(transport).toHaveAttribute('aria-label', 'Pause');
	await expect(transport.locator('.play-pause-icon')).toHaveClass(/playing/);

	await transport.click();
	await expect(transport).toHaveAttribute('aria-label', 'Play');
	await expect(transport.locator('.play-pause-icon')).not.toHaveClass(/playing/);
});

test('desktop progress track and interaction area both stay compact', async ({
	page
}, testInfo) => {
	test.skip(testInfo.project.name !== 'desktop-player');

	await page.goto('/podcast/test-show');
	await page.getByRole('button', { name: 'Play episode', exact: true }).click();

	const timeline = page.locator(".timeline input[type='range']");
	await expect(timeline).toBeVisible();
	const metrics = await timeline.evaluate((element) => {
		const style = getComputedStyle(element);
		const height = Number.parseFloat(style.height);
		const paddingTop = Number.parseFloat(style.paddingTop);
		const paddingBottom = Number.parseFloat(style.paddingBottom);
		return {
			height,
			paintedTrackHeight: height - paddingTop - paddingBottom,
			backgroundClip: style.backgroundClip
		};
	});

	expect(metrics.height).toBe(20);
	expect(metrics.paintedTrackHeight).toBe(4);
	expect(metrics.backgroundClip).toBe('content-box');
});

test('episode completion is persisted before the final cross-device sync', async ({ page }, testInfo) => {
	test.skip(testInfo.project.name !== 'desktop-player');
	const pushed: Array<{ entity_type: string; payload: { completed?: boolean; event_type?: string } }> = [];
	await page.route('**/api/v1/auth/status', (route) => route.fulfill({
		json: { authenticated: true, user_id: 'completion-sync-user', username: 'Sync QA', role: 'user' }
	}));
	await page.route('**/api/v1/sync**', async (route) => {
		if (route.request().method() === 'GET') {
			return route.fulfill({ json: { changesets: [], next_cursor: 0, has_more: false, data_generation: 0 } });
		}
		const body = route.request().postDataJSON() as { operations?: typeof pushed };
		pushed.push(...(body.operations ?? []));
		return route.fulfill({ json: { applied_ops: body.operations?.length ?? 0, current_cursor: 0, data_generation: 0 } });
	});

	await page.goto('/podcast/test-show');
	await page.getByRole('button', { name: 'Play episode', exact: true }).click();
	await page.locator('audio').dispatchEvent('ended');

	await expect.poll(() => pushed.some((operation) =>
		operation.entity_type === 'playback_state' &&
		operation.payload.completed === true &&
		operation.payload.event_type === 'MARK_PLAYED'
	)).toBe(true);
});

test('each visualizer uses a dedicated stage instead of covering the timeline', async ({ page }, testInfo) => {
	test.skip(testInfo.project.name !== 'desktop-player');
	test.setTimeout(60_000);

	const accountReady = page.waitForResponse((response) => response.url().includes('/api/v1/auth/status'));
	await page.goto('/podcast/test-show');
	await accountReady;
	await page.getByRole('button', { name: 'Play episode', exact: true }).click();
	await page.getByRole('link', { name: 'Settings', exact: true }).click();
	await page.locator('#playback > summary').click();

	for (const [style, label] of Object.entries({
		level: 'Level',
		waveform: 'Waveform',
		bars: 'Bars',
		pulse: 'Pulse',
		spectrum: 'Spectrum',
		ribbon: 'Ribbon',
		vu: 'VU Meter',
		constellation: 'Constellation'
	})) {
		const styleButton = page.getByRole('group', { name: 'Audio visualizer' }).getByRole('button', { name: label, exact: true });
		await styleButton.click();
		await expect(styleButton).toHaveAttribute('aria-pressed', 'true');
		await expect(page.locator('.player-bar')).toBeVisible();
		const preview = page.locator('.visualizer-preview');
		await expect(preview.locator(`[data-visualizer="${style}"]`)).toBeVisible();
		const previewContained = await preview.evaluate((host) => {
			const signal = host.querySelector('[data-visualizer]')!.getBoundingClientRect();
			const bounds = host.getBoundingClientRect();
			return signal.left >= bounds.left && signal.right <= bounds.right && signal.top >= bounds.top && signal.bottom <= bounds.bottom;
		});
		expect(previewContained).toBe(true);

		const stage = page.locator('.compact-visualizer');
		await expect(stage).toBeVisible();
		await expect(stage.locator(`[data-visualizer="${style}"]`)).toBeVisible();
		await expect.poll(() => stage.locator('[data-visualizer]').evaluate((element) =>
			Number.parseFloat((element as HTMLElement).style.getPropertyValue('--level'))
		)).toBeGreaterThan(0);
		await expect(page.locator('.timeline-track [data-visualizer]')).toHaveCount(0);

		const geometry = await page.evaluate(() => {
			const stage = document.querySelector('.compact-visualizer')!.getBoundingClientRect();
			const timeline = document.querySelector('.timeline-track')!.getBoundingClientRect();
			return { stageBottom: stage.bottom, timelineTop: timeline.top };
		});
		expect(geometry.stageBottom).toBeLessThanOrEqual(geometry.timelineTop);
	}

	await page.locator('.art-btn').click();
	const fullStage = page.locator('.np-visualizer');
	await expect(fullStage).toBeVisible();
	await expect(page.locator('.np-slider-host [data-visualizer]')).toHaveCount(0);
	const fullGeometry = await page.evaluate(() => {
		const stage = document.querySelector('.np-visualizer')!.getBoundingClientRect();
		const timeline = document.querySelector('.np-timeline')!.getBoundingClientRect();
		return { stageBottom: stage.bottom, timelineTop: timeline.top };
	});
	expect(fullGeometry.stageBottom).toBeLessThanOrEqual(fullGeometry.timelineTop);
});

test('signed-in settings navigation keeps playback and the rail footer visible', async ({
	page
}, testInfo) => {
	test.skip(testInfo.project.name !== 'desktop-player');
	await page.route('**/api/v1/auth/status', async (route) => {
		await route.fulfill({
			json: {
				authenticated: true,
				user_id: 'player-regression-user',
				username: 'Player QA',
				role: 'user'
			}
		});
	});
	await page.route('**/api/v1/sync**', async (route) => {
		if (route.request().method() === 'GET') {
			await route.fulfill({ json: { changesets: [], next_cursor: 0, has_more: false } });
		} else {
			await route.fulfill({ json: { accepted: [], rejected: [], next_cursor: 0 } });
		}
	});

	await page.goto('/podcast/test-show');
	await page.getByRole('button', { name: 'Play episode', exact: true }).click();
	await expect(page.locator('.player-bar .track-title')).toHaveText('The first useful episode');

	await page.goto('/settings');
	await expect(page.locator('.player-bar .track-title')).toHaveText('The first useful episode');
	await expect(page.locator('audio')).toHaveCount(1);

	const footerGeometry = async () => page.evaluate(() => {
		const footer = document.querySelector('.quiet-rail .rail-bottom small')?.getBoundingClientRect();
		const player = document.querySelector('.player-bar')?.getBoundingClientRect();
		return footer && player ? { footerBottom: footer.bottom, playerTop: player.top } : null;
	});
	let geometry = await footerGeometry();
	expect(geometry).not.toBeNull();
	expect(geometry!.footerBottom).toBeLessThanOrEqual(geometry!.playerTop - 8);

	await page.setViewportSize({ width: 1280, height: 720 });
	await page.locator('audio').evaluate((audio) => {
		(audio as HTMLAudioElement).src = 'https://127.0.0.1:9/missing-audio.mp3';
		(audio as HTMLAudioElement).load();
	});
	await expect(page.getByRole('alert')).toBeVisible();
	await expect.poll(async () => {
		geometry = await footerGeometry();
		return geometry ? geometry.footerBottom <= geometry.playerTop - 8 : false;
	}).toBe(true);
});
