// Which keys of the synced `settings` blob belong to this client, and how to keep
// the other client's keys alive across a push.
//
// Both clients sync their preferences as a single `settings` entity and the server
// keeps only the newest write of the whole thing — it does not merge. Their key
// sets can grow independently, so unknown keys must survive every write.
//
// A payload rebuilt purely from the fields *this* client understands therefore
// deletes the other client's keys from the server on every push. Nothing breaks on
// the device that pushed — both clients ignore keys they do not know and keep their
// current value — so the damage only shows up on a fresh install, which restores
// whichever half the last writer happened to know about.
//
// Kept in its own module, free of side effects, so it can be tested without
// constructing the store (which reads localStorage at import time).

/**
 * Every key this client writes. A key added to `syncPayload` must be added here in
 * the same change, or this client stores its own key as foreign and writes it twice.
 */
export const OWNED_SETTINGS_KEYS = new Set([
	'date_format',
	'theme_mode',
	'palette',
	'interests',
	'hidden_genres',
	'hidden_podcasts',
	'default_inbox_mode',
	'languages',
	'ui_language',
	'volume_boost',
	'skip_silence',
	'playback_speed',
	'start_screen',
	'visualizer',
	'proxy_images',
	'download_wifi_only',
	'auto_download_count',
	'download_retention',
	'download_concurrency',
	'download_budget_bytes',
	'updated_at',
	// The per-field timestamps this client maintains. Owned, so it is never stored
	// as somebody else's key and written back twice.
	'field_updated_at'
]);

/**
 * The settings that carry a value, in the order they are merged. This is
 * `OWNED_SETTINGS_KEYS` without the two bookkeeping keys: those describe the
 * payload rather than being part of it, and nothing merges them field-wise.
 */
export const SYNCED_SETTINGS_FIELDS: readonly string[] = [...OWNED_SETTINGS_KEYS].filter(
	(key) => key !== 'updated_at' && key !== 'field_updated_at'
);

/** The part of an incoming payload that belongs to another client. */
export function foreignSettingsOf(payload: Record<string, unknown>): Record<string, unknown> {
	return Object.fromEntries(
		Object.entries(payload).filter(([key]) => !OWNED_SETTINGS_KEYS.has(key))
	);
}

/**
 * This client's payload with the other client's keys restored. Owned keys win, so
 * a stale foreign snapshot can never undo a setting the listener just made.
 */
export function mergeForeignSettings(
	owned: Record<string, unknown>,
	foreign: Record<string, unknown>
): Record<string, unknown> {
	return { ...foreignSettingsOf(foreign), ...owned };
}
