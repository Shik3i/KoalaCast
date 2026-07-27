// Lightweight global toast store. Replaces the native alert()/confirm() calls
// scattered across pages. A single <Toast /> is mounted in the root layout.

export type ToastType = 'info' | 'success' | 'error';

export interface ToastItem {
	id: number;
	message: string;
	type: ToastType;
}

class ToastStore {
	items = $state<ToastItem[]>([]);
	#seq = 0;

	show(message: string, type: ToastType = 'info', durationMs = 3400) {
		const id = ++this.#seq;
		this.items = [...this.items, { id, message, type }];
		if (durationMs > 0) setTimeout(() => this.dismiss(id), durationMs);
		return id;
	}

	info(message: string) {
		return this.show(message, 'info');
	}
	success(message: string) {
		return this.show(message, 'success');
	}
	error(message: string) {
		return this.show(message, 'error', 5000);
	}

	dismiss(id: number) {
		this.items = this.items.filter((t) => t.id !== id);
	}
}

export const toast = new ToastStore();
