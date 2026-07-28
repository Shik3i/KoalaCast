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
	getTombstones,
	getLocalPlaybackState,
	saveLocalSubscription,
	saveLocalPlaybackState,
	saveLocalListeningSession,
	addLocalFavorite,
	removeLocalSubscriptionSilent,
	removeLocalFavoriteSilent,
	type LocalSubscription,
	type LocalFavorite,
	type LocalPlaybackState,
	type LocalListeningSession
} from '$lib/idb/db';

export type SyncStatus = 'off' | 'idle' | 'syncing' | 'error';

const PAGE_LIMIT = 500; // must match the server Pull LIMIT
const INTERVAL_MS = 45_000;

interface SyncOperation {
	client_op_id: string;
	device_id: string;
	entity_type: 'subscription' | 'favorite' | 'playback_state' | 'listening_session';
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

	#timer: ReturnType<typeof setInterval> | null = null;
	#onVisible: (() => void) | null = null;
	#inFlight = false;

	get enabled(): boolean {
		return this.userId !== null;
	}

	#cursorKey(): string {
		return `koalacast_sync_cursor_${this.userId}`;
	}
	#getCursor(): number {
		try {
			return Number(localStorage.getItem(this.#cursorKey()) || '0') || 0;
		} catch {
			return 0;
		}
	}
	#setCursor(v: number) {
		try {
			localStorage.setItem(this.#cursorKey(), String(v));
		} catch {
			/* ignore */
		}
	}
	#pushWatermarkKey(): string {
		return `koalacast_sync_push_watermark_${this.userId}`;
	}
	#getPushWatermark(): number {
		try {
			return Number(localStorage.getItem(this.#pushWatermarkKey()) || '0') || 0;
		} catch {
			return 0;
		}
	}
	#setPushWatermark(value: number) {
		try {
			localStorage.setItem(this.#pushWatermarkKey(), String(value));
		} catch {
			/* ignore */
		}
	}
	#sessionWatermarkKey(): string {
		return `koalacast_synced_listening_sessions_${this.userId}`;
	}
	#getSessionWatermarks(): Record<string, number> {
		try {
			return JSON.parse(localStorage.getItem(this.#sessionWatermarkKey()) || '{}');
		} catch {
			return {};
		}
	}
	#setSessionWatermarks(value: Record<string, number>) {
		try {
			localStorage.setItem(this.#sessionWatermarkKey(), JSON.stringify(value));
		} catch {
			/* ignore */
		}
	}

	// Begin syncing for a signed-in user. Idempotent.
	enable(userId: string) {
		if (this.userId === userId && this.#timer) return;
		this.userId = userId;
		this.status = 'idle';
		if (!this.#timer) this.#timer = setInterval(() => this.syncNow(), INTERVAL_MS);
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
		this.#inFlight = true;
		this.status = 'syncing';
		try {
			await this.#pull();
			await this.#push();
			this.status = 'idle';
			this.lastSyncedAt = Date.now();
		} catch (err) {
			// A 401 means the session is gone — stop rather than spin.
			if (err instanceof SyncAuthError) {
				this.disable();
			} else {
				this.status = 'error';
			}
		} finally {
			this.#inFlight = false;
		}
	}

	async #pull(): Promise<void> {
		let since = this.#getCursor();
		while (true) {
			const res = await fetch(`/api/v1/sync?since_cursor=${since}`);
			if (res.status === 401) throw new SyncAuthError();
			if (res.status === 410) {
				// Server pruned history past our cursor — restart a full pull.
				since = 0;
				this.#setCursor(0);
				continue;
			}
			if (!res.ok) throw new Error(`sync pull failed: ${res.status}`);
			const data = await res.json();
			const changesets: Changeset[] = data.changesets || [];
			for (const cs of changesets) {
				try {
					await applyChangeset(cs);
				} catch {
					/* skip a single bad changeset, keep going */
				}
			}
			if (changesets.length > 0) {
				since = changesets[changesets.length - 1].server_cursor;
				this.#setCursor(since);
			} else if (typeof data.current_cursor === 'number' && data.current_cursor > since) {
				since = data.current_cursor;
				this.#setCursor(since);
			}
			if (changesets.length < PAGE_LIMIT) break;
		}
	}

	async #push(): Promise<void> {
		const dev = deviceId();
		const ops: SyncOperation[] = [];
		const previousWatermark = this.#getPushWatermark();
		const nextWatermark = Date.now();

		const subs = await getLocalSubscriptions();
		for (const s of subs) {
			// OPML imports are resolved lazily on first open. Their feed URL is
			// only a local placeholder, not a valid server-side podcast id.
			if (s.podcast_id === s.feed_url) continue;
			if (s.added_at <= previousWatermark) continue;
			ops.push({
				client_op_id: `s:${s.podcast_id}:${s.added_at}`,
				device_id: dev,
				entity_type: 'subscription',
				action: 'upsert',
				entity_id: s.podcast_id,
				payload: s satisfies LocalSubscription,
				client_timestamp: s.added_at
			});
		}

		const favs = await getLocalFavorites();
		for (const f of favs) {
			if (f.added_at <= previousWatermark) continue;
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
			if (p.last_played_at <= previousWatermark) continue;
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
					event_type: 'PROGRESS_TICK',
					playback_session_id: '',
					device_id: dev,
					per_session_seq: 0,
					client_timestamp: p.last_played_at
				},
				client_timestamp: p.last_played_at
			});
		}

		const listeningSessions = await getLocalListeningSessions();
		const sessionWatermarks = this.#getSessionWatermarks();
		for (const session of listeningSessions) {
			if (session.ended_at <= previousWatermark) continue;
			if ((sessionWatermarks[session.id] || 0) >= session.ended_at) continue;
			ops.push({
				client_op_id: `l:${session.id}:${session.ended_at}`,
				device_id: dev,
				entity_type: 'listening_session',
				action: 'upsert',
				entity_id: session.id,
				payload: session satisfies LocalListeningSession,
				client_timestamp: session.ended_at
			});
		}

		const tombstones = await getTombstones();
		for (const t of tombstones) {
			if (t.entity_type !== 'subscription' && t.entity_type !== 'favorite') continue;
			if (t.deleted_at <= previousWatermark) continue;
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
			this.#setPushWatermark(nextWatermark);
			return;
		}

		for (let index = 0; index < ops.length; index += 250) {
			const batch = ops.slice(index, index + 250);
			const res = await fetch('/api/v1/sync', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ operations: batch, client_schema_version: 2 })
			});
			if (res.status === 401) throw new SyncAuthError();
			if (!res.ok) throw new Error(`sync push failed: ${res.status}`);
			for (const op of batch) {
				if (op.entity_type === 'listening_session') sessionWatermarks[op.entity_id] = op.client_timestamp;
			}
			this.#setSessionWatermarks(sessionWatermarks);
		}
		this.#setPushWatermark(nextWatermark);
		// Deliberately do NOT advance the cursor here: letting the next pull re-read
		// our own ops (idempotent) avoids skipping a concurrent device's ops that
		// landed at a lower cursor.
	}
}

class SyncAuthError extends Error {}

async function applyChangeset(cs: Changeset): Promise<void> {
	const isDelete = cs.action === 'delete';
	if (cs.entity_type === 'subscription') {
		if (isDelete) {
			await removeLocalSubscriptionSilent(cs.entity_id);
		} else if (cs.payload && typeof cs.payload === 'object') {
			const p = cs.payload as Partial<LocalSubscription>;
			await saveLocalSubscription({
				podcast_id: p.podcast_id || cs.entity_id,
				feed_url: p.feed_url || '',
				title: p.title || 'Podcast',
				artwork_url: p.artwork_url || '',
				added_at: p.added_at || cs.client_timestamp || Date.now(),
				inbox_mode: p.inbox_mode
			});
		}
	} else if (cs.entity_type === 'favorite') {
		if (isDelete) {
			await removeLocalFavoriteSilent(cs.entity_id);
		} else if (cs.payload && typeof cs.payload === 'object') {
			const p = cs.payload as Partial<LocalFavorite>;
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
		}
	} else if (cs.entity_type === 'playback_state') {
		if (isDelete || !cs.payload || typeof cs.payload !== 'object') return;
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
			categories: p.categories
		});
	} else if (cs.entity_type === 'listening_session') {
		if (isDelete || !cs.payload || typeof cs.payload !== 'object') return;
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
	}
}

export const sync = new SyncStore();
