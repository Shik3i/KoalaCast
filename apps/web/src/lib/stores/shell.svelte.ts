const LEFT_COLLAPSED_KEY = 'koalacast_left_rail_collapsed';
const RIGHT_COLLAPSED_KEY = 'koalacast_right_rail_collapsed';
const LEFT_WIDTH_KEY = 'koalacast_left_rail_width';
const RIGHT_WIDTH_KEY = 'koalacast_right_rail_width';

export const railSizes = {
	left: { collapsed: 64, min: 176, default: 206, max: 360, snap: 136 },
	right: { collapsed: 48, min: 180, default: 220, max: 420, snap: 132 }
} as const;

function initialWidth(side: 'left' | 'right'): number {
	const config = railSizes[side];
	if (typeof localStorage === 'undefined') return config.default;
	const widthKey = side === 'left' ? LEFT_WIDTH_KEY : RIGHT_WIDTH_KEY;
	const collapsedKey = side === 'left' ? LEFT_COLLAPSED_KEY : RIGHT_COLLAPSED_KEY;
	const saved = Number(localStorage.getItem(widthKey));
	if (Number.isFinite(saved) && saved >= config.collapsed && saved <= config.max) return saved;
	return localStorage.getItem(collapsedKey) === '1' ? config.collapsed : config.default;
}

class ShellStore {
	leftWidth = $state(initialWidth('left'));
	rightWidth = $state(initialWidth('right'));
	private leftRestoreWidth: number = this.leftWidth >= railSizes.left.min ? this.leftWidth : railSizes.left.default;
	private rightRestoreWidth: number = this.rightWidth >= railSizes.right.min ? this.rightWidth : railSizes.right.default;

	get leftCollapsed() {
		return this.leftWidth === railSizes.left.collapsed;
	}

	get rightCollapsed() {
		return this.rightWidth === railSizes.right.collapsed;
	}

	get leftCompact() {
		return this.leftWidth < railSizes.left.min;
	}

	get rightCompact() {
		return this.rightWidth < railSizes.right.min;
	}

	setLeftWidth(width: number, final = false) {
		this.leftWidth = this.normalize('left', width, final);
		if (!this.leftCollapsed) this.leftRestoreWidth = Math.max(railSizes.left.min, this.leftWidth);
		if (final) this.persistWidth(LEFT_WIDTH_KEY, LEFT_COLLAPSED_KEY, this.leftWidth, this.leftCollapsed);
	}

	setRightWidth(width: number, final = false) {
		this.rightWidth = this.normalize('right', width, final);
		if (!this.rightCollapsed) this.rightRestoreWidth = Math.max(railSizes.right.min, this.rightWidth);
		if (final) this.persistWidth(RIGHT_WIDTH_KEY, RIGHT_COLLAPSED_KEY, this.rightWidth, this.rightCollapsed);
	}

	toggleLeft() {
		this.setLeftWidth(this.leftCollapsed ? this.leftRestoreWidth : railSizes.left.collapsed, true);
	}

	toggleRight() {
		this.setRightWidth(this.rightCollapsed ? this.rightRestoreWidth : railSizes.right.collapsed, true);
	}

	resetLeft() {
		this.setLeftWidth(railSizes.left.default, true);
	}

	resetRight() {
		this.setRightWidth(railSizes.right.default, true);
	}

	private normalize(side: 'left' | 'right', width: number, final: boolean) {
		const config = railSizes[side];
		const bounded = Math.min(config.max, Math.max(config.collapsed, Math.round(width)));
		if (final && bounded < config.snap) return config.collapsed;
		if (final && bounded < config.min) return config.min;
		return bounded;
	}

	private persistWidth(widthKey: string, collapsedKey: string, width: number, collapsed: boolean) {
		try {
			localStorage.setItem(widthKey, String(width));
			localStorage.setItem(collapsedKey, collapsed ? '1' : '0');
		} catch (_) {}
	}
}

export const shell = new ShellStore();
