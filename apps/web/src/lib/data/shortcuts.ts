import type { MessageKey } from '$lib/i18n';

export interface KeyboardShortcut {
	key: string;
	/** Translation key — the description is rendered through `t()` at display time. */
	descriptionKey: MessageKey;
}

export const KEYBOARD_SHORTCUTS: KeyboardShortcut[] = [
	{ key: 'Space', descriptionKey: 'shortcuts.playPause' },
	{ key: '←', descriptionKey: 'shortcuts.skipBack' },
	{ key: '→', descriptionKey: 'shortcuts.skipForward' },
	{ key: '?', descriptionKey: 'shortcuts.toggleHelp' }
];
