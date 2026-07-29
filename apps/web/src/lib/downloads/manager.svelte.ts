import {
	activateOfflineAudioContext,
	audioDownloadCacheName,
	migrateGuestAudioDownloads,
	offlineAudioPath,
	removeAudioDownload
} from '$lib/downloads/offline-audio';
import {
	audioEffectsProxyUrl,
	publisherAllowsAudioEffects
} from '$lib/audio/source';

export type DownloadState = 'downloading' | 'downloaded' | 'cancelled' | 'failed';

export interface AudioDownload {
	episodeId: string;
	podcastId: string;
	title: string;
	podcastTitle: string;
	artworkUrl: string;
	enclosureUrl: string;
	state: DownloadState;
	bytesDownloaded: number;
	totalBytes: number;
	error: string;
	updatedAt: number;
}

export interface DownloadRequest {
	episode_id: string;
	podcast_id?: string;
	title?: string;
	podcast_title?: string;
	artwork_url?: string;
	enclosure_url: string;
}

const STORAGE_KEY = 'koalacast_audio_downloads_v3';
const GUEST_MIGRATION_KEY = 'koalacast_guest_audio_downloads_migrated';
const DEFAULT_BUDGET_BYTES = 2 * 1024 * 1024 * 1024;
let audioProxyEnabledPromise: Promise<boolean> | null = null;

function audioProxyEnabled(): Promise<boolean> {
	if (!audioProxyEnabledPromise) {
		audioProxyEnabledPromise = fetch('/api/v1/config')
			.then(async (response) => {
				if (!response.ok) return false;
				const config = await response.json();
				return config.audio_effects_proxy_enabled === true;
			})
			.catch(() => false);
	}
	return audioProxyEnabledPromise;
}

class AudioDownloadManager {
	items = $state<AudioDownload[]>([]);
	usageBytes = $state(0);
	quotaBytes = $state(0);
	loaded = $state(false);
	private controllers = new Map<string, AbortController>();
	private activeOwner: string | null = null;
	private generation = 0;

	private storageKey(owner = this.activeOwner) {
		return owner ? `${STORAGE_KEY}:account:${encodeURIComponent(owner)}` : `${STORAGE_KEY}:guest`;
	}

	async activateContext(userId: string | null, options: { migrateGuest?: boolean } = {}) {
		if (this.activeOwner === userId && this.loaded) return;
		this.generation += 1;
		for (const controller of this.controllers.values()) controller.abort();
		this.controllers.clear();
		if (
			userId &&
			options.migrateGuest &&
			typeof localStorage !== 'undefined' &&
			localStorage.getItem(GUEST_MIGRATION_KEY) !== '1'
		) {
			const guestKey = this.storageKey(null);
			const targetKey = this.storageKey(userId);
			if (localStorage.getItem(targetKey) === null) {
				const guestValue = localStorage.getItem(guestKey);
				if (guestValue !== null) {
					localStorage.setItem(targetKey, guestValue);
					try {
						const items = JSON.parse(guestValue) as AudioDownload[];
						await migrateGuestAudioDownloads(userId, items.map((item) => item.episodeId));
					} catch (_) {}
				}
			}
			localStorage.setItem(GUEST_MIGRATION_KEY, '1');
		}
		this.activeOwner = userId;
		activateOfflineAudioContext(userId);
		this.items = [];
		this.loaded = false;
		await this.load();
	}

	async load() {
		if (this.loaded || typeof window === 'undefined') return;
		this.loaded = true;
		try {
			const parsed = JSON.parse(localStorage.getItem(this.storageKey()) || '[]') as AudioDownload[];
			this.items = parsed.map((item) => ({
				...item,
				state: item.state === 'downloading' ? 'cancelled' : item.state
			}));
		} catch {
			this.items = [];
		}
		if (typeof caches !== 'undefined') {
			const cache = await caches.open(audioDownloadCacheName());
			const keys = await cache.keys();
			for (const request of keys) {
				const match = new URL(request.url).pathname.match(/^\/offline\/audio\/[^/]+\/(.+)$/);
				if (!match) continue;
				const episodeId = decodeURIComponent(match[1]);
				if (this.get(episodeId)) continue;
				const response = await cache.match(request);
				const bytes = Number(response?.headers.get('content-length')) || 0;
				this.items.push({
					episodeId,
					podcastId: '',
					title: episodeId,
					podcastTitle: '',
					artworkUrl: '',
					enclosureUrl: '',
					state: 'downloaded',
					bytesDownloaded: bytes,
					totalBytes: bytes,
					error: '',
					updatedAt: Date.now()
				});
			}
		}
		await this.refreshStorage();
		this.persist();
	}

	get(episodeId: string) {
		return this.items.find((item) => item.episodeId === episodeId);
	}

	async start(request: DownloadRequest) {
		await this.load();
		const generation = this.generation;
		if (!request.enclosure_url) throw new Error('Episode has no audio URL');
		this.controllers.get(request.episode_id)?.abort();
		const controller = new AbortController();
		this.controllers.set(request.episode_id, controller);
		this.upsert({
			episodeId: request.episode_id,
			podcastId: request.podcast_id || '',
			title: request.title || request.episode_id,
			podcastTitle: request.podcast_title || '',
			artworkUrl: request.artwork_url || '',
			enclosureUrl: request.enclosure_url,
			state: 'downloading',
			bytesDownloaded: 0,
			totalBytes: 0,
			error: '',
			updatedAt: Date.now()
		});

		try {
			const origin = location.origin;
			const directAllowed = await publisherAllowsAudioEffects(request.enclosure_url, origin);
			const source = directAllowed
				? request.enclosure_url
				: (await audioProxyEnabled())
					? audioEffectsProxyUrl(request.enclosure_url)
					: '';
			if (!source) throw new Error('Publisher does not allow browser downloads');
			const response = await fetch(source, { cache: 'no-store', signal: controller.signal });
			if (!response.ok || !response.body) {
				throw new Error(`HTTP ${response.status}`);
			}
			const totalBytes = Number(response.headers.get('content-length')) || 0;
			let bytesDownloaded = 0;
			let lastPublishedAt = 0;
			this.patch(request.episode_id, { totalBytes });
			const progressStream = new TransformStream<Uint8Array, Uint8Array>({
				transform: (chunk, streamController) => {
					bytesDownloaded += chunk.byteLength;
					if (performance.now() - lastPublishedAt >= 200) {
						lastPublishedAt = performance.now();
						this.patch(request.episode_id, { bytesDownloaded, totalBytes });
					}
					streamController.enqueue(chunk);
				}
			});
			const cachedResponse = new Response(response.body.pipeThrough(progressStream), {
				status: response.status,
				statusText: response.statusText,
				headers: response.headers
			});
			const cache = await caches.open(audioDownloadCacheName());
			await cache.put(offlineAudioPath(request.episode_id), cachedResponse);
			if (generation !== this.generation) return;
			this.patch(request.episode_id, {
				state: 'downloaded',
				bytesDownloaded: totalBytes || bytesDownloaded,
				totalBytes: totalBytes || bytesDownloaded,
				error: ''
			});
			await this.enforceBudget();
		} catch (error: any) {
			if (generation !== this.generation) return;
			const cancelled = error?.name === 'AbortError';
			this.patch(request.episode_id, {
				state: cancelled ? 'cancelled' : 'failed',
				error: cancelled ? '' : String(error?.message || error)
			});
			if (!cancelled) throw error;
		} finally {
			if (generation === this.generation) {
				this.controllers.delete(request.episode_id);
				await this.refreshStorage();
			}
		}
	}

	async startAuto(request: DownloadRequest): Promise<boolean> {
		const connection = (navigator as Navigator & {
			connection?: { saveData?: boolean; effectiveType?: string };
		}).connection;
		if (connection?.saveData || connection?.effectiveType === '2g') return false;
		if (this.get(request.episode_id)?.state === 'downloaded') return false;
		await this.start(request);
		return true;
	}

	async enforceBudget(budgetBytes = DEFAULT_BUDGET_BYTES) {
		let used = this.items
			.filter((item) => item.state === 'downloaded')
			.reduce((sum, item) => sum + item.bytesDownloaded, 0);
		const oldestFirst = this.items
			.filter((item) => item.state === 'downloaded')
			.sort((a, b) => a.updatedAt - b.updatedAt);
		for (const item of oldestFirst) {
			if (used <= budgetBytes) break;
			used -= item.bytesDownloaded;
			await this.remove(item.episodeId);
		}
	}

	cancel(episodeId: string) {
		this.controllers.get(episodeId)?.abort();
	}

	async retry(episodeId: string) {
		const item = this.get(episodeId);
		if (!item) return;
		await this.start({
			episode_id: item.episodeId,
			podcast_id: item.podcastId,
			title: item.title,
			podcast_title: item.podcastTitle,
			artwork_url: item.artworkUrl,
			enclosure_url: item.enclosureUrl
		});
	}

	async remove(episodeId: string) {
		this.cancel(episodeId);
		await removeAudioDownload(episodeId);
		this.items = this.items.filter((item) => item.episodeId !== episodeId);
		this.persist();
		await this.refreshStorage();
	}

	async refreshStorage() {
		if (typeof navigator === 'undefined' || !navigator.storage?.estimate) return;
		const estimate = await navigator.storage.estimate();
		this.usageBytes = estimate.usage || 0;
		this.quotaBytes = estimate.quota || 0;
	}

	private upsert(item: AudioDownload) {
		const index = this.items.findIndex((candidate) => candidate.episodeId === item.episodeId);
		this.items = index < 0
			? [item, ...this.items]
			: this.items.map((candidate, candidateIndex) => candidateIndex === index ? item : candidate);
		this.persist();
	}

	private patch(episodeId: string, patch: Partial<AudioDownload>) {
		this.items = this.items.map((item) =>
			item.episodeId === episodeId ? { ...item, ...patch, updatedAt: Date.now() } : item
		);
		this.persist();
	}

	private persist() {
		try {
			localStorage.setItem(this.storageKey(), JSON.stringify(this.items));
		} catch {}
	}
}

export const audioDownloads = new AudioDownloadManager();
