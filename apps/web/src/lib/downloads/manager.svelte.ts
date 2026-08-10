import {
	activateOfflineAudioContext,
	audioDownloadCacheName,
	migrateGuestAudioDownloads,
	offlineAudioPath,
	purgeAllAudioDownloads,
	removeAudioDownload
} from '$lib/downloads/offline-audio';
import {
	audioEffectsProxyUrl,
	publisherAllowsAudioEffects
} from '$lib/audio/source';
import { prefs } from '$lib/stores/prefs.svelte';
import { getLocalPlaybackState } from '$lib/idb/db';

export type DownloadState = 'queued' | 'downloading' | 'downloaded' | 'cancelled' | 'failed';

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
const DAY_MS = 24 * 60 * 60 * 1000;
const RETENTION_DAYS: Record<string, number> = { '7d': 7, '14d': 14, '30d': 30 };
/** Progress is published every 200 ms; writing it through to disk that often
 *  serialised the whole list synchronously on the main thread. */
const PERSIST_INTERVAL_MS = 2_000;
let audioProxyEnabledPromise: Promise<boolean> | null = null;

/** Stable, translatable failure codes; see `downloadErrorMessage`. */
export const DOWNLOAD_ERROR = {
	noAudioUrl: 'no-audio-url',
	corsBlocked: 'cors-blocked',
	http: 'http-'
} as const;

/**
 * True when the connection is known to be metered. The Network Information API
 * is the only signal a browser offers, and it is absent on iOS and desktop
 * Safari — there "WLAN only" can be no stricter than "not a slow cellular link
 * and not data-saver", which is what the checks below express.
 */
function onMeteredConnection(): boolean {
	const connection = (navigator as Navigator & {
		connection?: { saveData?: boolean; effectiveType?: string; type?: string };
	}).connection;
	if (!connection) return false;
	if (connection.saveData) return true;
	if (connection.type) return connection.type === 'cellular';
	return connection.effectiveType === '2g' || connection.effectiveType === 'slow-2g';
}

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
	private activeTransfers = 0;
	private waiting: Array<() => void> = [];
	private persistTimer: ReturnType<typeof setTimeout> | null = null;

	/**
	 * Waits for a transfer slot. The limit is read at the moment a slot is handed
	 * out, so raising "parallel downloads" in Settings releases waiting episodes
	 * immediately and lowering it simply stops new ones from starting.
	 */
	private acquireSlot(signal: AbortSignal): Promise<void> {
		if (signal.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
		if (this.activeTransfers < prefs.downloadConcurrency) {
			this.activeTransfers += 1;
			return Promise.resolve();
		}
		return new Promise<void>((resolve, reject) => {
			const onAbort = () => {
				this.waiting = this.waiting.filter((entry) => entry !== grant);
				reject(new DOMException('Aborted', 'AbortError'));
			};
			const grant = () => {
				signal.removeEventListener('abort', onAbort);
				this.activeTransfers += 1;
				resolve();
			};
			signal.addEventListener('abort', onAbort, { once: true });
			this.waiting.push(grant);
		});
	}

	private releaseSlot() {
		this.activeTransfers = Math.max(0, this.activeTransfers - 1);
		while (this.activeTransfers < prefs.downloadConcurrency && this.waiting.length > 0) {
			this.waiting.shift()?.();
		}
	}

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
				state: item.state === 'downloading' || item.state === 'queued' ? 'cancelled' : item.state
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
		await this.enforceRetention();
	}

	get(episodeId: string) {
		return this.items.find((item) => item.episodeId === episodeId);
	}

	async start(request: DownloadRequest) {
		await this.load();
		const generation = this.generation;
		// Failures are stored as stable codes rather than English sentences: this
		// string is rendered in the Downloads list, where a German listener used to
		// be told "Publisher does not allow browser downloads".
		if (!request.enclosure_url) throw new Error(DOWNLOAD_ERROR.noAudioUrl);
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
			state: 'queued',
			bytesDownloaded: 0,
			totalBytes: 0,
			error: '',
			updatedAt: Date.now()
		});

		try {
			await this.acquireSlot(controller.signal);
		} catch {
			this.patch(request.episode_id, { state: 'cancelled' });
			this.controllers.delete(request.episode_id);
			return;
		}

		try {
			this.patch(request.episode_id, { state: 'downloading' });
			const origin = location.origin;
			const directAllowed = await publisherAllowsAudioEffects(request.enclosure_url, origin);
			const source = directAllowed
				? request.enclosure_url
				: (await audioProxyEnabled())
					? audioEffectsProxyUrl(request.enclosure_url)
					: '';
			if (!source) throw new Error(DOWNLOAD_ERROR.corsBlocked);
			const response = await fetch(source, { cache: 'no-store', signal: controller.signal });
			if (!response.ok || !response.body) {
				throw new Error(`${DOWNLOAD_ERROR.http}${response.status}`);
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
			await this.enforceRetention();
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
			this.releaseSlot();
			if (generation === this.generation) {
				this.controllers.delete(request.episode_id);
				await this.refreshStorage();
			}
		}
	}

	async startAuto(request: DownloadRequest): Promise<boolean> {
		// "Download over Wi-Fi only" is a promise about the listener's data plan, so
		// an automatic download is the one place it must be honoured. A manual tap is
		// an explicit decision and stays allowed.
		if (prefs.downloadWifiOnly && onMeteredConnection()) return false;
		if (this.get(request.episode_id)?.state === 'downloaded') return false;
		await this.start(request);
		return true;
	}

	/** 0 means "no budget"; anything else evicts least-recently-touched first. */
	async enforceBudget(budgetBytes = prefs.downloadBudgetBytes) {
		if (!budgetBytes || budgetBytes <= 0) return;
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

	/**
	 * Applies the "keep downloads" setting. Runs on load and after every finished
	 * transfer, which is as often as the answer can change without a timer nobody
	 * would notice running.
	 */
	async enforceRetention(retention = prefs.downloadRetention) {
		if (retention === 'keep') return;
		const downloaded = this.items.filter((item) => item.state === 'downloaded');
		if (downloaded.length === 0) return;
		if (retention === 'finished') {
			for (const item of downloaded) {
				const state = await getLocalPlaybackState(item.episodeId).catch(() => undefined);
				if (state?.completed) await this.remove(item.episodeId);
			}
			return;
		}
		const days = RETENTION_DAYS[retention];
		if (!days) return;
		const cutoff = Date.now() - days * DAY_MS;
		for (const item of downloaded) {
			if (item.updatedAt < cutoff) await this.remove(item.episodeId);
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

	/** Cancels everything in flight and removes every stored file, for every owner. */
	async clearAll() {
		for (const controller of this.controllers.values()) controller.abort();
		this.controllers.clear();
		this.waiting = [];
		this.items = [];
		this.persist();
		await purgeAllAudioDownloads();
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
		// A byte-count tick is worth nothing after a reload, so it may wait. A state
		// change is what tells the next visit whether the file is there, so it may not.
		this.persist(patch.state !== undefined);
	}

	/**
	 * @param immediate write through now. Progress ticks arrive every 200 ms and
	 *   each one serialises the whole list, which is a synchronous main-thread
	 *   write; several parallel downloads made that measurable as jank.
	 */
	private persist(immediate = true) {
		if (!immediate) {
			if (this.persistTimer) return;
			this.persistTimer = setTimeout(() => {
				this.persistTimer = null;
				this.persist();
			}, PERSIST_INTERVAL_MS);
			return;
		}
		if (this.persistTimer) {
			clearTimeout(this.persistTimer);
			this.persistTimer = null;
		}
		try {
			localStorage.setItem(this.storageKey(), JSON.stringify(this.items));
		} catch {}
	}
}

export const audioDownloads = new AudioDownloadManager();
