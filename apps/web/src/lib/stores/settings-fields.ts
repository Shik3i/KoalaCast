// Per-field conflict resolution for the synced `settings` blob.
//
// The blob has always carried one `updated_at` and the newest write won all of
// it. That is wrong for a settings object whose fields are independent: change
// the interface language on the phone at 10:00 and the colour palette in the
// browser at 10:01, and the browser's payload — newer as a whole — silently
// reverts the language. Nothing is corrupted and nothing is reported; the
// setting simply goes back.
//
// So each field carries its own timestamp, and a payload is merged field by
// field rather than accepted or rejected as one lump. The server stores the
// payload verbatim and never reads it, so this is a client-side contract:
//
//   { …fields…, "updated_at": 1730…, "field_updated_at": { "languages": 1730… } }
//
// Compatibility. A payload without `field_updated_at` is read the old way, with
// every field taking the blob's `updated_at` — which is exactly what a client
// that predates this change means. In the other direction such a client keeps
// the map among the foreign keys it hands back untouched, so the stamps survive
// a round trip through it.
//
// The one rough edge is a client that predates this change *and* edits a
// setting: its payload carries a genuine new value under a stale inherited
// stamp, and this side may keep its own value instead. That is a mixed-version
// window only — both clients ship from this repository and are released
// together — and the failure heals the moment the setting is changed again on
// an updated client. It is a better trade than today's behaviour, where the
// entire payload is discarded and *every* field in it is lost rather than one.

/** Stamps travel under this key; see `OWNED_SETTINGS_KEYS`. */
export const FIELD_UPDATED_AT_KEY = 'field_updated_at';

export type FieldTimestamps = Record<string, number>;

export function parseFieldTimestamps(value: unknown): FieldTimestamps {
	if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
	const parsed: FieldTimestamps = {};
	for (const [field, timestamp] of Object.entries(value as Record<string, unknown>)) {
		const numeric = Number(timestamp);
		if (Number.isFinite(numeric) && numeric > 0) parsed[field] = numeric;
	}
	return parsed;
}

/**
 * When the sending client last changed [field].
 *
 * Falling back to the blob timestamp is what makes an older payload readable:
 * it says "everything in here is as of this moment", which is precisely the
 * guarantee the old format made.
 */
export function incomingFieldTimestamp(
	field: string,
	stamps: FieldTimestamps,
	blobUpdatedAt: number
): number {
	return stamps[field] ?? blobUpdatedAt;
}

/**
 * When this client last changed [field].
 *
 * The same fallback runs the other way for an installation upgrading into this
 * format: it has no per-field history, and its whole state is as of the blob
 * timestamp it already stores. Without this, every local field would look
 * untouched and the first payload to arrive would overwrite all of them.
 */
export function localFieldTimestamp(
	field: string,
	stamps: FieldTimestamps,
	blobUpdatedAt: number
): number {
	return stamps[field] ?? blobUpdatedAt;
}

export interface FieldDecision {
	/** Fields whose incoming value wins and should be written. */
	accepted: Set<string>;
	/** Timestamps to record for the accepted fields. */
	stamps: FieldTimestamps;
}

/**
 * Decides, field by field, which side of a merge wins.
 *
 * @param authoritative a snapshot restore, where the server's state replaces
 *   local state outright and timestamps do not arbitrate.
 */
export function decideFields(
	fields: readonly string[],
	incoming: { stamps: FieldTimestamps; updatedAt: number },
	local: { stamps: FieldTimestamps; updatedAt: number },
	options: { authoritative?: boolean } = {}
): FieldDecision {
	const accepted = new Set<string>();
	const stamps: FieldTimestamps = {};
	for (const field of fields) {
		const incomingAt = incomingFieldTimestamp(field, incoming.stamps, incoming.updatedAt);
		const localAt = localFieldTimestamp(field, local.stamps, local.updatedAt);
		if (options.authoritative || incomingAt > localAt) {
			accepted.add(field);
			stamps[field] = incomingAt;
		}
	}
	return { accepted, stamps };
}
