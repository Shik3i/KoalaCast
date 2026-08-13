// Cross-device sync engine for the web client.
//
// Design: the server's sync_log stores each operation's payload verbatim and the
// Pull endpoint returns it, so we push the *full* local record as the payload and
// reconstruct records on other devices directly — no per-item hydration fetches.
// Deletions propagate via local tombstones (see idb/db). Materialized server
// tables are secondary; the sync_log is the source of truth for the client.
//
// Sync is best-effort and last-writer-wins (ordered by the server cursor). It runs
// on login, on an interval, when the tab becomes visible, and on demand.

import {
	getLocalSubscriptions,
	getLocalFavorites,
	getAllLocalPlaybackStates,
	getLocalListeningSessions,
	getLocalListeningSession,
	getLocalQueue,
	getLocalQueueUpdatedAt,
	getTombstones,
	getTombstone,
	acknowledgeTombstone,
	getLocalPlaybackState,
	saveLocalSubscription,
	saveLocalPlaybackState,
	saveLocalListeningSession,
	addLocalFavorite,
	removeLocalSubscriptionSilent,
	removeLocalFavoriteSilent,
	replaceLocalQueueFromSync,
	replaceLocalSyncSnapshot,
	type LocalSubscription,
	type LocalFavorite,
	type LocalPlaybackState,
	type LocalListeningSession
} from '$lib/idb/db';
import {
	applySyncedPodcastPlaybackSettings,
	clearPodcastPlaybackSettingsContext,
	getAllPodcastPlaybackSettings,
	removePodcastPlaybackSettings,
	type PodcastPlaybackSettings
} from '$lib/stores/podcast-settings';
import { prefs } from '$lib/stores/prefs.svelte';
import { shouldUploadListeningSession } from '$lib/stores/sync-selection';
import { normalizeListeningSessionForSync } from '$lib/sync-payload';

export type SyncStatus = 'off' | 'idle' | 'syncing' | 'error';

const PAGE_LIMIT = 500; // must match the server Pull LIMIT
const INTERVAL_MS = 45_000;

interface SyncOperation {
	client_op_id: string;
	device_id: string;
	entity_type:
		| 'subscription'
		| 'favorite'
		| 'playback_state'
		| 'listening_session'
		| 'queue'
		| 'podcast_settings'
		| 'settings';
	action: 'upsert' | 'delete';
	entity_id: string;
	payload: unknown;
	client_timestamp: number;
}

interface Changeset {
	entity_type: string;
	entity_id: string;
	action: string;
	payload: any;
	client_timestamp: number;
	server_cursor: number;
}

type GeneralSyncEntity = Exclude<SyncOperation['entity_type'], 'listening_session'>;
type PushWatermarks = Record<GeneralSyncEntity, number>;
const GENERAL_SYNC_ENTITIES: GeneralSyncEntity[] = [
	'subscription',
	'favorite',
	'playback_state',
	'queue',
	'podcast_settings',
	'settings'
];

function deviceId(): string {
	if (typeof localStorage === 'undefined') return 'ssr';
	let id = localStorage.getItem('koalacast_device_id');
	if (!id) {
		id = crypto.randomUUID();
		localStorage.setItem('koalacast_device_id', id);
	}
	return id;
}

class SyncStore {
	status = $state<SyncStatus>('off');
	lastSyncedAt = $state<number | null>(null);
	userId = $state<string | null>(null);
	/**
	 * Why the last run failed, or null after one succeeds. The Android client has
	 * always surfaced this; a bare red dot in Settings told a listener whose data
	 * never arrived nothing at all about whether it was the network, the session
	 * or a rejected record.
	 */
	lastError = $state<string | null>(null);
	/** Records the server sent that this build could not read. */
	skippedChangesets = $state(0);

	#timer: ReturnType<typeof setInterval> | null = null;
	#onVisible: (() => void) | null = null;
	#inFlight = false;
	#generation = 0;
	#controller: AbortController | null = null;

	get enabled(): boolean {
		return this.userId !== null;
	}

	#cursorKey(userId: string): string {
		return `koalacast_sync_cursor_${userId}`;
	}
	#getCursor(userId: string): number {
		try {
			return Number(localStorage.getItem(this.#cursorKey(userId)) || '0') || 0;
		} catch {
			return 0;
		}
	}
	#setCursor(userId: string, v: number) {
		try {
			localStorage.setItem(this.#cursorKey(userId), String(v));
		} catch {
			/* ignore */
		}
	}
	#pushWatermarkKey(userId: string): string {
		return `koalacast_sync_push_watermark_${userId}`;
	}
	#getPushWatermark(userId: string): number {
		try {
			return Number(localStorage.getItem(this.#pushWatermarkKey(userId)) || '0') || 0;
		} catch {
			return 0;
		}
	}
	#pushWatermarksKey(userId: string): string {
		return `koalacast_sync_push_watermarks_v2_${userId}`;
	}
	#getPushWatermarks(userId: string): PushWatermarks {
		const legacy = this.#getPushWatermark(userId);
		const fallback = Object.fromEntries(
			GENERAL_SYNC_ENTITIES.map((entity) => [entity, entity === 'subscription' ? 0 : legacy])
		) as PushWatermarks;
		try {
			const parsed = JSON.parse(localStorage.getItem(this.#pushWatermarksKey(userId)) || 'null');
			if (!parsed || typeof parsed !== 'object') return fallback;
			return Object.fromEntries(
				GENERAL_SYNC_ENTITIES.map((entity) => [
					entity,
					Number.isFinite(parsed[entity]) ? Math.max(0, Number(parsed[entity])) : fallback[entity]
				])
			) as PushWatermarks;
		} catch {
			return fallback;
		}
	}
	#setPushWatermarks(userId: string, value: PushWatermarks) {
		try {
			localStorage.setItem(this.#pushWatermarksKey(userId), JSON.stringify(value));
		} catch {
			/* ignore */
		}
	}
	#sessionWatermarkKey(userId: string): string {
		return `koalacast_synced_listening_sessions_${userId}`;
	}
	#getSessionWatermarks(userId: string): Record<string, number> {
		try {
			return JSON.parse(localStorage.getItem(this.#sessionWatermarkKey(userId)) || '{}');
		} catch {
			return {};
		}
	}
	#setSessionWatermarks(userId: string, value: Record<string, number>) {
		try {
			localStorage.setItem(this.#sessionWatermarkKey(userId), JSON.stringify(value));
		} catch {
			/* ignore */
		}
	}
	#dataGenerationKey(userId: string): string {
		return `koalacast_data_generation_${userId}`;
	}
	#getDataGeneration(userId: string): number {
		try {
			return Math.max(0, Number(localStorage.getItem(this.#dataGenerationKey(userId))) || 0);
		} catch {
			return 0;
		}
	}
	#setDataGeneration(userId: string, value: number) {
		try {
			localStorage.setItem(this.#dataGenerationKey(userId), String(Math.max(0, value)));
		} catch {
			/* ignore */
		}
	}
	#clearSyncMetadata(userId: string) {
		try {
			for (const key of [
				this.#cursorKey(userId),
				this.#pushWatermarkKey(userId),
				this.#pushWatermarksKey(userId),
				this.#sessionWatermarkKey(userId)
			]) localStorage.removeItem(key);
		} catch {
			/* ignore */
		}
	}

	async #adoptDataGeneration(
		userId: string,
		serverGeneration: unknown,
		runGeneration?: number,
		signal?: AbortSignal
	): Promise<boolean> {
		const incoming = Number(serverGeneration);
		if (!Number.isSafeInteger(incoming) || incoming < 0) {
			throw new Error('sync response missing data generation');
		}
		const local = this.#getDataGeneration(userId);
		if (incoming < local) throw new Error('server data generation regressed');
		if (incoming === local) return false;
		if (runGeneration !== undefined && signal) this.#assertRun(userId, runGeneration, signal);
		// Import lazily: account-context owns the complete browser wipe and imports
		// this store to stop sync during account switches. A static import here would
		// create a module cycle during application startup.
		const { resetAllLocalData } = await import('$lib/stores/account-context');
		await resetAllLocalData();
		if (runGeneration !== undefined && signal) this.#assertRun(userId, runGeneration, signal);
		this.#clearSyncMetadata(userId);
		this.#setDataGeneration(userId, incoming);
		return true;
	}

	/** Apply the generation returned by DELETE /auth/data without signing out. */
	async acceptDataReset(userId: string, serverGeneration: number): Promise<void> {
		if (this.userId !== null && this.userId !== userId) {
			throw new Error('account changed during data reset');
		}
		this.#generation++;
		this.#controller?.abort();
		this.#controller = null;
		this.#inFlight = false;
		await this.#adoptDataGeneration(userId, serverGeneration);
		this.status = 'idle';
		this.lastSyncedAt = Date.now();
		this.lastError = null;
	}

	// Begin syncing for a signed-in user. Idempotent.
	enable(userId: string) {
		if (this.userId === userId && this.#timer) return;
		if (this.userId && this.userId !== userId) this.disable();
		this.#generation++;
		this.userId = userId;
		this.status = 'idle';
		// Only tick while the tab is actually in front. A backgrounded phone browser
		// woke the radio every 45 seconds for a sync nobody was waiting on; becoming
		// visible again already triggers one through the listener below.
		if (!this.#timer) {
			this.#timer = setInterval(() => {
				if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return;
				void this.syncNow();
			}, INTERVAL_MS);
		}
		if (!this.#onVisible) {
			this.#onVisible = () => {
				if (document.visibilityState === 'visible') this.syncNow();
			};
			document.addEventListener('visibilitychange', this.#onVisible);
		}
		this.syncNow();
	}

	// Stop syncing (on logout / lost session). Keeps the per-user cursor so a later
	// login by the same user resumes incrementally.
	disable() {
		this.#generation++;
		this.#controller?.abort();
		this.#controller = null;
		this.#inFlight = false;
		if (this.#timer) {
			clearInterval(this.#timer);
			this.#timer = null;
		}
		if (this.#onVisible) {
			document.removeEventListener('visibilitychange', this.#onVisible);
			this.#onVisible = null;
		}
		this.userId = null;
		this.status = 'off';
	}

	async syncNow(): Promise<void> {
		if (!this.enabled || this.#inFlight) return;
		const userId = this.userId;
		if (!userId) return;
		const generation = this.#generation;
		const controller = new AbortController();
		this.#controller = controller;
		this.#inFlight = true;
		this.status = 'syncing';
		this.skippedChangesets = 0;
		try {
			await this.#pull(userId, generation, controller.signal);
			this.#assertRun(userId, generation, controller.signal);
			await this.#push(userId, generation, controller.signal);
			this.#assertRun(userId, generation, controller.signal);
			this.status = 'idle';
			this.lastSyncedAt = Date.now();
			// A skipped record is not a failed sync — the rest went through — but it
			// must still be said out loud, or the data silently goes missing.
			this.lastError =
				this.skippedChangesets > 0
					? `${this.skippedChangesets} unreadable record(s) skipped, rest synced`
					: null;
		} catch (err) {
			if (err instanceof DOMException && err.name === 'AbortError') return;
			if (generation !== this.#generation || this.userId !== userId) return;
			// A 401 means the session is gone — stop rather than spin.
			if (err instanceof SyncAuthError) {
				this.lastError = 'session expired';
				this.disable();
			} else {
				this.lastError = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
				this.status = 'error';
			}
		} finally {
			if (this.#controller === controller) this.#controller = null;
			if (generation === this.#generation) this.#inFlight = false;
		}
	}

	#assertRun(userId: string, generation: number, signal: AbortSignal) {
		if (signal.aborted || generation !== this.#generation || this.userId !== userId) {
			throw new DOMException('Sync superseded', 'AbortError');
		}
	}

	async #pull(userId: string, generation: number, signal: AbortSignal): Promise<void> {
		let since = this.#getCursor(userId);
		let recoveredSnapshot = false;
		while (true) {
			this.#assertRun(userId, generation, signal);
			const res = await fetch(`/api/v1/sync?since_cursor=${since}&limit=${PAGE_LIMIT}`, { signal });
			if (res.status === 401) throw new SyncAuthError();
			if (res.status === 410) {
				if (recoveredSnapshot) throw new Error('sync snapshot recovery repeated');
				recoveredSnapshot = true;
				since = await this.#replaceFromSnapshot(userId, generation, signal);
				continue;
			}
			if (!res.ok) throw new Error(`sync pull failed: ${res.status}`);
			const data = await res.json();
			if (await this.#adoptDataGeneration(userId, data.data_generation, generation, signal)) {
				since = 0;
				recoveredSnapshot = false;
				continue;
			}
			const changesets: Changeset[] = data.changesets || [];
			for (const cs of changesets) {
				this.#assertRun(userId, generation, signal);
				// A malformed record must never stop the pull. Throwing here left the
				// cursor un-advanced, so the same bad row came back every 45 seconds
				// and sync was wedged for good. Skip it, count it, keep going — the
				// Android client has isolated bad operations this way for a while.
				try {
					await applyChangeset(cs);
				} catch (err) {
					if (err instanceof DOMException && err.name === 'AbortError') throw err;
					this.skippedChangesets++;
					console.warn('sync: skipping unusable changeset', cs?.entity_type, cs?.entity_id, err);
				}
			}
			const lastCursor = changesets.at(-1)?.server_cursor;
			const nextCursor = Number.isFinite(data.next_cursor)
				? Number(data.next_cursor)
				: typeof lastCursor === 'number'
					? lastCursor
					: since;
			if (nextCursor < since || (data.has_more && nextCursor === since)) {
				throw new Error('sync pull returned a non-advancing cursor');
			}
			since = nextCursor;
			this.#setCursor(userId, since);
			const hasMore = typeof data.has_more === 'boolean'
				? data.has_more
				: changesets.length === PAGE_LIMIT;
			if (!hasMore) break;
		}
	}

	async #replaceFromSnapshot(
		userId: string,
		generation: number,
		signal: AbortSignal
	): Promise<number> {
		const res = await fetch('/api/v1/sync/snapshot', { signal });
		if (res.status === 401) throw new SyncAuthError();
		if (!res.ok) throw new Error(`sync snapshot failed: ${res.status}`);
		const snapshot = await res.json();
		this.#assertRun(userId, generation, signal);
		if (await this.#adoptDataGeneration(userId, snapshot.data_generation, generation, signal)) {
			return 0;
		}
		if (!Number.isFinite(snapshot.cursor)) throw new Error('sync snapshot missing cursor');
		await replaceLocalSyncSnapshot(snapshot, this.#getPushWatermarks(userId));
		const queuePayloads = Array.isArray(snapshot.queue) ? snapshot.queue : [];
		if (queuePayloads.length === 0) {
			await replaceLocalQueueFromSync([], 0, { authoritative: true });
		} else {
			for (const payload of queuePayloads) {
				await applyQueuePayload(payload, true);
			}
		}
		clearPodcastPlaybackSettingsContext();
		for (const payload of snapshot.podcast_settings || []) {
			applyPodcastSettingsPayload(payload, '', true);
		}
		prefs.resetSynced();
		for (const payload of snapshot.settings || []) {
			applySettingsPayload(payload, true);
		}
		this.#assertRun(userId, generation, signal);
		const cursor = Number(snapshot.cursor);
		this.#setCursor(userId, cursor);
		return cursor;
	}

	async #push(userId: string, generation: number, signal: AbortSignal): Promise<void> {
		const dev = deviceId();
		const ops: SyncOperation[] = [];
		const previousWatermarks = this.#getPushWatermarks(userId);

		const subs = await getLocalSubscriptions();
		for (const s of subs) {
			// OPML imports are resolved lazily on first open. Their feed URL is
			// only a local placeholder, not a valid server-side podcast id.
			if (s.podcast_id === s.feed_url) continue;
			const updatedAt = s.updated_at || s.added_at;
			if (updatedAt <= previousWatermarks.subscription) continue;
			ops.push({
				client_op_id: `s:${s.podcast_id}:${updatedAt}`,
				device_id: dev,
				entity_type: 'subscription',
				action: 'upsert',
				entity_id: s.podcast_id,
				payload: s satisfies LocalSubscription,
				client_timestamp: updatedAt
			});
		}

		const favs = await getLocalFavorites();
		for (const f of favs) {
			if (f.added_at <= previousWatermarks.favorite) continue;
			ops.push({
				client_op_id: `f:${f.episode_id}:${f.added_at}`,
				device_id: dev,
				entity_type: 'favorite',
				action: 'upsert',
				entity_id: f.episode_id,
				payload: f satisfies LocalFavorite,
				client_timestamp: f.added_at
			});
		}

		const states = await getAllLocalPlaybackStates();
		for (const p of states) {
			if (p.last_played_at <= previousWatermarks.playback_state) continue;
			ops.push({
				client_op_id: `p:${p.episode_id}:${p.last_played_at}`,
				device_id: dev,
				entity_type: 'playback_state',
				action: 'upsert',
				entity_id: p.episode_id,
				// Extra fields are ignored by the server parser but round-trip back to
				// other devices on pull (so they get title/artwork without a refetch).
				payload: {
					...p,
					event_type: p.event_type || 'PROGRESS_TICK',
					playback_session_id: p.playback_session_id || `sync:${dev}:${p.episode_id}`,
					device_id: dev,
					per_session_seq: p.per_session_seq || p.last_played_at,
					client_timestamp: p.last_played_at
				},
				client_timestamp: p.last_played_at
			});
		}

		const listeningSessions = await getLocalListeningSessions();
		const sessionWatermarks = this.#getSessionWatermarks(userId);
		for (const session of listeningSessions) {
			// Listening-session sync shipped after the general watermark. Its own
			// per-session watermark must decide whether an older local session was
			// uploaded; otherwise upgraded clients can never backfill their history.
			if (!shouldUploadListeningSession(session.ended_at, sessionWatermarks[session.id])) continue;
			const syncSession = normalizeListeningSessionForSync(session);
			ops.push({
				client_op_id: `l:${syncSession.id}:${syncSession.ended_at}`,
				device_id: dev,
				entity_type: 'listening_session',
				action: 'upsert',
				entity_id: syncSession.id,
				payload: syncSession satisfies LocalListeningSession,
				client_timestamp: syncSession.ended_at
			});
		}

		const queueUpdatedAt = await getLocalQueueUpdatedAt();
		if (queueUpdatedAt > previousWatermarks.queue) {
			ops.push({
				client_op_id: `q:main:${queueUpdatedAt}`,
				device_id: dev,
				entity_type: 'queue',
				action: 'upsert',
				entity_id: 'main',
				payload: { items: await getLocalQueue(), updated_at: queueUpdatedAt },
				client_timestamp: queueUpdatedAt
			});
		}

		if (prefs.updatedAt > previousWatermarks.settings) {
			ops.push({
				client_op_id: `g:global:${prefs.updatedAt}`,
				device_id: dev,
				entity_type: 'settings',
				action: 'upsert',
				entity_id: 'global',
				payload: prefs.syncPayload(),
				client_timestamp: prefs.updatedAt
			});
		}

		for (const setting of getAllPodcastPlaybackSettings()) {
			if (setting.updatedAt <= previousWatermarks.podcast_settings) continue;
			const { podcastId, ...payload } = setting;
			ops.push({
				client_op_id: `ps:${podcastId}:${setting.updatedAt}`,
				device_id: dev,
				entity_type: 'podcast_settings',
				action: 'upsert',
				entity_id: podcastId,
				payload: { podcast_id: podcastId, ...payload, updated_at: setting.updatedAt },
				client_timestamp: setting.updatedAt
			});
		}

		const tombstones = await getTombstones();
		for (const t of tombstones) {
			if (t.entity_type !== 'subscription' && t.entity_type !== 'favorite') continue;
			if (t.deleted_at <= previousWatermarks[t.entity_type]) continue;
			ops.push({
				client_op_id: `d:${t.entity_type}:${t.entity_id}:${t.deleted_at}`,
				device_id: dev,
				entity_type: t.entity_type,
				action: 'delete',
				entity_id: t.entity_id,
				payload: {},
				client_timestamp: t.deleted_at
			});
		}

		if (ops.length === 0) {
			return;
		}

		for (let index = 0; index < ops.length; index += 250) {
			this.#assertRun(userId, generation, signal);
			const batch = ops.slice(index, index + 250);
			const res = await fetch('/api/v1/sync', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					operations: batch,
					client_schema_version: 2,
					data_generation: this.#getDataGeneration(userId)
				}),
				signal
			});
			if (res.status === 401) throw new SyncAuthError();
			if (res.status === 409) {
				const conflict = await res.json().catch(() => null);
				if (conflict?.code !== 'DATA_GENERATION_MISMATCH') {
					throw new Error('sync push conflict without generation');
				}
				await this.#adoptDataGeneration(
					userId,
					conflict.data_generation,
					generation,
					signal
				);
				return;
			}
			if (!res.ok) throw new Error(`sync push failed: ${res.status}`);
			for (const op of batch) {
				if (op.entity_type === 'listening_session') sessionWatermarks[op.entity_id] = op.client_timestamp;
			}
			this.#setSessionWatermarks(userId, sessionWatermarks);
		}
		const nextWatermarks = { ...previousWatermarks };
		for (const op of ops) {
			if (op.entity_type === 'listening_session') continue;
			nextWatermarks[op.entity_type] = Math.max(
				nextWatermarks[op.entity_type],
				op.client_timestamp
			);
		}
		this.#setPushWatermarks(userId, nextWatermarks);
		// Deliberately do NOT advance the cursor here: letting the next pull re-read
		// our own ops (idempotent) avoids skipping a concurrent device's ops that
		// landed at a lower cursor.
	}
}

class SyncAuthError extends Error {}

async function applyChangeset(cs: Changeset): Promise<void> {
	if (
		!cs ||
		!Number.isSafeInteger(cs.server_cursor) ||
		cs.server_cursor < 1 ||
		typeof cs.entity_id !== 'string' ||
		!cs.entity_id.trim() ||
		!['upsert', 'delete'].includes(cs.action) ||
		![
			'subscription',
			'favorite',
			'playback_state',
			'listening_session',
			'queue',
			'podcast_settings',
			'settings'
		].includes(cs.entity_type)
	) {
		throw new Error('invalid sync changeset');
	}
	const isDelete = cs.action === 'delete';
	if (cs.entity_type === 'subscription') {
		if (isDelete) {
			await removeLocalSubscriptionSilent(cs.entity_id);
			await acknowledgeTombstone('subscription', cs.entity_id);
		} else if (cs.payload && typeof cs.payload === 'object') {
			// Pull precedes push. Keep a local deletion until its own delete
			// operation has reached the server and comes back through the log.
			if (await getTombstone('subscription', cs.entity_id)) return;
			const p = cs.payload as Partial<LocalSubscription>;
			if (p.podcast_id !== undefined && p.podcast_id !== cs.entity_id) {
				throw new Error('subscription changeset identity mismatch');
			}
			await saveLocalSubscription({
				podcast_id: p.podcast_id || cs.entity_id,
				feed_url: p.feed_url || '',
				title: p.title || 'Podcast',
				artwork_url: p.artwork_url || '',
				added_at: p.added_at || cs.client_timestamp || Date.now(),
				updated_at: p.updated_at || cs.client_timestamp || p.added_at || Date.now(),
				inbox_mode: p.inbox_mode,
				folder: p.folder
			});
		} else throw new Error('invalid subscription changeset payload');
	} else if (cs.entity_type === 'favorite') {
		if (isDelete) {
			await removeLocalFavoriteSilent(cs.entity_id);
			await acknowledgeTombstone('favorite', cs.entity_id);
		} else if (cs.payload && typeof cs.payload === 'object') {
			if (await getTombstone('favorite', cs.entity_id)) return;
			const p = cs.payload as Partial<LocalFavorite>;
			if (p.episode_id !== undefined && p.episode_id !== cs.entity_id) {
				throw new Error('favorite changeset identity mismatch');
			}
			await addLocalFavorite({
				episode_id: p.episode_id || cs.entity_id,
				added_at: p.added_at || cs.client_timestamp || Date.now(),
				podcast_id: p.podcast_id,
				title: p.title,
				podcast_title: p.podcast_title,
				artwork_url: p.artwork_url,
				enclosure_url: p.enclosure_url,
				duration_ms: p.duration_ms,
				categories: p.categories
			});
		} else throw new Error('invalid favorite changeset payload');
	} else if (cs.entity_type === 'playback_state') {
		if (isDelete) return;
		if (!cs.payload || typeof cs.payload !== 'object') {
			throw new Error('invalid playback changeset payload');
		}
		const p = cs.payload as Partial<LocalPlaybackState> & { client_timestamp?: number };
		const episodeId = p.episode_id || cs.entity_id;
		const incomingWhen = p.last_played_at || p.client_timestamp || cs.client_timestamp || 0;
		const existing = await getLocalPlaybackState(episodeId);
		// Last-writer-wins by timestamp — never let an older remote state clobber a
		// newer local one.
		if (existing && (existing.last_played_at || 0) >= incomingWhen) return;
		await saveLocalPlaybackState({
			episode_id: episodeId,
			podcast_id: p.podcast_id || '',
			position_ms: p.position_ms || 0,
			completed: !!p.completed,
			progress_percent: p.progress_percent || 0,
			last_played_at: incomingWhen || Date.now(),
			title: p.title,
			podcast_title: p.podcast_title,
			artwork_url: p.artwork_url,
			enclosure_url: p.enclosure_url,
			duration_ms: p.duration_ms,
			categories: p.categories,
			event_type: p.event_type,
			playback_session_id: p.playback_session_id,
			per_session_seq: p.per_session_seq
		});
	} else if (cs.entity_type === 'listening_session') {
		if (isDelete) return;
		if (!cs.payload || typeof cs.payload !== 'object') {
			throw new Error('invalid listening changeset payload');
		}
		const p = cs.payload as Partial<LocalListeningSession>;
		const id = p.id || cs.entity_id;
		const incomingWhen = p.ended_at || cs.client_timestamp || 0;
		const existing = await getLocalListeningSession(id);
		if (existing && existing.ended_at >= incomingWhen) return;
		await saveLocalListeningSession({
			id,
			episode_id: p.episode_id || '',
			podcast_id: p.podcast_id || '',
			title: p.title || 'Episode',
			podcast_title: p.podcast_title || 'Podcast',
			categories: Array.isArray(p.categories) ? p.categories.filter((value): value is string => typeof value === 'string') : undefined,
			started_at: p.started_at || incomingWhen || Date.now(),
			ended_at: incomingWhen || Date.now(),
			wall_clock_ms: Math.max(0, p.wall_clock_ms || 0),
			audio_listened_ms: Math.max(0, p.audio_listened_ms || 0),
			speed_saved_ms: Math.max(0, p.speed_saved_ms || 0),
			silence_saved_ms: Math.max(0, p.silence_saved_ms || 0),
			manual_skipped_ms: Math.max(0, p.manual_skipped_ms || 0),
			intro_outro_skipped_ms: Math.max(0, p.intro_outro_skipped_ms || 0),
			speed_weighted_ms: Math.max(0, p.speed_weighted_ms || 0)
		});
	} else if (cs.entity_type === 'queue') {
		if (!isDelete) await applyQueuePayload(cs.payload);
	} else if (cs.entity_type === 'podcast_settings') {
		if (isDelete) removePodcastPlaybackSettings(cs.entity_id);
		else applyPodcastSettingsPayload(cs.payload, cs.entity_id);
	} else if (cs.entity_type === 'settings') {
		if (!isDelete) applySettingsPayload(cs.payload);
	}
}

async function applyQueuePayload(payload: any, authoritative = false) {
	if (!payload || !Array.isArray(payload.items) || !Number.isFinite(payload.updated_at)) {
		throw new Error('invalid queue changeset payload');
	}
	await replaceLocalQueueFromSync(payload.items, Number(payload.updated_at), { authoritative });
}

function applyPodcastSettingsPayload(payload: any, fallbackPodcastId = '', authoritative = false) {
	if (!payload || typeof payload !== 'object' || !Number.isFinite(payload.updated_at)) {
		throw new Error('invalid podcast settings changeset payload');
	}
	const podcastId = String(payload.podcast_id || fallbackPodcastId);
	if (!podcastId) throw new Error('podcast settings changeset missing podcast id');
	applySyncedPodcastPlaybackSettings(podcastId, {
		...(payload as Partial<PodcastPlaybackSettings>),
		skipIntroSeconds: payload.skip_intro_seconds ?? payload.skipIntroSeconds,
		skipOutroSeconds: payload.skip_outro_seconds ?? payload.skipOutroSeconds,
		volumeBoost: payload.volume_boost ?? payload.volumeBoost,
		skipSilence: payload.skip_silence ?? payload.skipSilence,
		autoQueueNew: payload.auto_queue_new ?? payload.autoQueueNew,
		autoDownload: payload.auto_download ?? payload.autoDownload,
		notifyNewEpisodes: payload.notify_new_episodes ?? payload.notifyNewEpisodes,
		updatedAt: Number(payload.updated_at)
	}, { authoritative });
}

function applySettingsPayload(payload: any, authoritative = false) {
	if (!payload || typeof payload !== 'object' || !Number.isFinite(payload.updated_at)) {
		throw new Error('invalid settings changeset payload');
	}
	prefs.applySynced(payload, { authoritative });
}

export const sync = new SyncStore();
