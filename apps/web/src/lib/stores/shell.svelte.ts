const LEFT_KEY = 'koalacast_left_rail_collapsed';
const RIGHT_KEY = 'koalacast_right_rail_collapsed';

function stored(key: string): boolean {
	if (typeof localStorage === 'undefined') return false;
	return localStorage.getItem(key) === '1';
}

class ShellStore {
	leftCollapsed = $state(stored(LEFT_KEY));
	rightCollapsed = $state(stored(RIGHT_KEY));

	toggleLeft() {
		this.leftCollapsed = !this.leftCollapsed;
		this.persist(LEFT_KEY, this.leftCollapsed);
	}

	toggleRight() {
		this.rightCollapsed = !this.rightCollapsed;
		this.persist(RIGHT_KEY, this.rightCollapsed);
	}

	private persist(key: string, value: boolean) {
		try {
			localStorage.setItem(key, value ? '1' : '0');
		} catch (_) {}
	}
}

export const shell = new ShellStore();
