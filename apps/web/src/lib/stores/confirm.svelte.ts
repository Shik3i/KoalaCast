type ConfirmationRequest = {
	message: string;
	resolve: (value: boolean) => void;
};

class ConfirmStore {
	request = $state<ConfirmationRequest | null>(null);

	ask(message: string) {
		if (this.request) this.finish(false);
		return new Promise<boolean>((resolve) => {
			this.request = { message, resolve };
		});
	}

	finish(value: boolean) {
		const current = this.request;
		this.request = null;
		current?.resolve(value);
	}
}

export const confirmDialog = new ConfirmStore();
