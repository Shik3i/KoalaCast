import {
	AUDIO_DOWNLOAD_CACHE,
	offlineAudioPath,
	removeAudioDownload
} from '$lib/downloads/offline-audio';

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

const STORAGE_KEY = 'koalacast_audio_downloads_v2';

class AudioDownloadManager {
	items = $state<AudioDownload[]>([]);
	usageBytes = $state(0);
	quotaBytes = $state(0);
	loaded = $state(false);
	private controllers = new Map<string, AbortController>();

	async load() {
		if (this.loaded || typeof window === 'undefined') return;
		this.loaded = true;
		try {
			const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]') as AudioDownload[];
			this.items = parsed.map((item) => ({
				...item,
				state: item.state === 'downloading' ? 'cancelled' : item.state
			}));
		} catch {
			this.items = [];
		}
		if (typeof caches !== 'undefined') {
			const cache = await caches.open(AUDIO_DOWNLOAD_CACHE);
			const keys = await cache.keys();
			for (const request of keys) {
				const match = new URL(request.url).pathname.match(/^\/offline\/audio\/(.+)$/);
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
			const response = await fetch(
				`/api/v1/proxy/audio?url=${encodeURIComponent(request.enclosure_url)}`,
				{ cache: 'no-store', signal: controller.signal }
			);
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
			const cache = await caches.open(AUDIO_DOWNLOAD_CACHE);
			await cache.put(offlineAudioPath(request.episode_id), cachedResponse);
			this.patch(request.episode_id, {
				state: 'downloaded',
				bytesDownloaded: totalBytes || bytesDownloaded,
				totalBytes: totalBytes || bytesDownloaded,
				error: ''
			});
		} catch (error: any) {
			const cancelled = error?.name === 'AbortError';
			this.patch(request.episode_id, {
				state: cancelled ? 'cancelled' : 'failed',
				error: cancelled ? '' : String(error?.message || error)
			});
			if (!cancelled) throw error;
		} finally {
			this.controllers.delete(request.episode_id);
			await this.refreshStorage();
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
			localStorage.setItem(STORAGE_KEY, JSON.stringify(this.items));
		} catch {}
	}
}

export const audioDownloads = new AudioDownloadManager();
