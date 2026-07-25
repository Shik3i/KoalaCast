export interface KeyboardShortcut {
	key: string;
	description: string;
}

export const KEYBOARD_SHORTCUTS: KeyboardShortcut[] = [
	{ key: 'Space', description: 'Play / Pause' },
	{ key: '←', description: 'Skip 10 seconds back' },
	{ key: '→', description: 'Skip 30 seconds forward' },
	{ key: '?', description: 'Toggle this help' }
];
