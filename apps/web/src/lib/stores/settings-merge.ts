// Which keys of the synced `settings` blob belong to this client, and how to keep
// the other client's keys alive across a push.
//
// Both clients sync their preferences as a single `settings` entity and the server
// keeps only the newest write of the whole thing — it does not merge. Their key
// sets do not overlap: the web client owns `date_format` and `ui_language`, the
// Android client owns the theme, palette, start screen and download policy.
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
	'interests',
	'hidden_genres',
	'hidden_podcasts',
	'default_inbox_mode',
	'languages',
	'ui_language',
	'volume_boost',
	'skip_silence',
	'playback_speed',
	'updated_at'
]);

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
