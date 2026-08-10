/**
 * Keeps a listener-chosen OPML file in step with the subscription list.
 *
 * A browser cannot silently write to disk, and it should not be able to. What it
 * can do — where the File System Access API exists — is hold on to a handle the
 * listener granted once and reuse it. So the shape of the feature is: pick the
 * file yourself, and from then on it is rewritten whenever the library changes.
 *
 * Everywhere else (Safari, Firefox) the capability is absent, the entry point is
 * not rendered at all, and the manual export remains the way to get a copy out.
 */

import { getLocalDB, getLocalSubscriptions } from '$lib/idb/db';
import { buildOpml } from '$lib/opml';

const HANDLE_KEY = 'opml_backup_handle';
const WRITTEN_AT_KEY = 'opml_backup_written_at';
/** One write a day is plenty for a list that changes a few times a month. */
const MIN_INTERVAL_MS = 24 * 60 * 60 * 1000;

interface SaveFilePickerOptions {
	suggestedName?: string;
	types?: Array<{ description: string; accept: Record<string, string[]> }>;
}

type BackupFileHandle = FileSystemFileHandle & {
	queryPermission?: (descriptor: { mode: 'readwrite' }) => Promise<PermissionState>;
	requestPermission?: (descriptor: { mode: 'readwrite' }) => Promise<PermissionState>;
};

export function opmlBackupSupported(): boolean {
	return typeof window !== 'undefined' && 'showSaveFilePicker' in window;
}

class OpmlBackupStore {
	/** True once a destination has been chosen and is still writable. */
	enabled = $state(false);
	lastWrittenAt = $state<number | null>(null);
	fileName = $state('');

	async load() {
		if (!opmlBackupSupported()) return;
		const handle = await storedHandle();
		if (!handle) return;
		this.enabled = true;
		this.fileName = handle.name;
		this.lastWrittenAt = await storedWrittenAt();
	}

	/** Asks for a destination. Must be called from a user gesture. */
	async choose(): Promise<boolean> {
		if (!opmlBackupSupported()) return false;
		const picker = (window as unknown as {
			showSaveFilePicker: (options: SaveFilePickerOptions) => Promise<BackupFileHandle>;
		}).showSaveFilePicker;
		let handle: BackupFileHandle;
		try {
			handle = await picker({
				suggestedName: 'koalacast_subscriptions.opml',
				types: [{ description: 'OPML', accept: { 'text/x-opml': ['.opml'] } }]
			});
		} catch {
			// The listener cancelled the picker; nothing to report.
			return false;
		}
		const db = await getLocalDB();
		await db.put('settings', { key: HANDLE_KEY, value: handle });
		this.enabled = true;
		this.fileName = handle.name;
		await this.write({ force: true });
		return true;
	}

	async disable() {
		const db = await getLocalDB();
		await db.delete('settings', HANDLE_KEY);
		await db.delete('settings', WRITTEN_AT_KEY);
		this.enabled = false;
		this.fileName = '';
		this.lastWrittenAt = null;
	}

	/**
	 * Writes the current subscription list, unless one was written recently.
	 * Never throws: a backup that interrupts the app is worse than a late one.
	 */
	async write(options: { force?: boolean } = {}): Promise<boolean> {
		if (!opmlBackupSupported()) return false;
		try {
			const handle = await storedHandle();
			if (!handle) {
				this.enabled = false;
				return false;
			}
			const writtenAt = await storedWrittenAt();
			if (!options.force && writtenAt && Date.now() - writtenAt < MIN_INTERVAL_MS) return false;

			// Permission survives a reload but not indefinitely, and it can only be
			// re-requested from a gesture. Without one we stay quiet and try again the
			// next time the listener is actually interacting with the app.
			const granted = await ensurePermission(handle, options.force === true);
			if (!granted) return false;

			const subscriptions = await getLocalSubscriptions();
			const writable = await handle.createWritable();
			await writable.write(buildOpml(subscriptions));
			await writable.close();

			const db = await getLocalDB();
			const now = Date.now();
			await db.put('settings', { key: WRITTEN_AT_KEY, value: now });
			this.lastWrittenAt = now;
			this.enabled = true;
			this.fileName = handle.name;
			return true;
		} catch {
			return false;
		}
	}
}

async function storedHandle(): Promise<BackupFileHandle | null> {
	try {
		const db = await getLocalDB();
		const record = await db.get('settings', HANDLE_KEY);
		return (record?.value as BackupFileHandle) ?? null;
	} catch {
		return null;
	}
}

async function storedWrittenAt(): Promise<number | null> {
	try {
		const db = await getLocalDB();
		const record = await db.get('settings', WRITTEN_AT_KEY);
		return typeof record?.value === 'number' ? record.value : null;
	} catch {
		return null;
	}
}

async function ensurePermission(handle: BackupFileHandle, mayPrompt: boolean): Promise<boolean> {
	const state = (await handle.queryPermission?.({ mode: 'readwrite' })) ?? 'granted';
	if (state === 'granted') return true;
	if (!mayPrompt) return false;
	return (await handle.requestPermission?.({ mode: 'readwrite' })) === 'granted';
}

export const opmlBackup = new OpmlBackupStore();
