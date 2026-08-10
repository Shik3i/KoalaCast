/**
 * Installability of the web app as a standalone window.
 *
 * Everything a desktop install needs — the manifest, the service worker, the
 * offline shell, Media Session for the keyboard's media keys — has been in place
 * for a while. What was missing was any way to actually trigger the install:
 * Chromium fires `beforeinstallprompt`, cancels its own UI if the page calls
 * preventDefault, and then does nothing unless the saved event is used. Browsers
 * that do not fire it (Safari, Firefox) simply never report as installable and
 * the entry point stays hidden rather than lying about what it can do.
 */

interface BeforeInstallPromptEvent extends Event {
	prompt: () => Promise<void>;
	userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

class InstallStore {
	/** True once the browser has offered an install and it has not been used yet. */
	available = $state(false);
	/** True when the app is already running as an installed window. */
	installed = $state(false);

	#deferred: BeforeInstallPromptEvent | null = null;
	#listening = false;

	listen() {
		if (this.#listening || typeof window === 'undefined') return;
		this.#listening = true;
		this.installed = window.matchMedia?.('(display-mode: standalone)').matches === true;
		window.addEventListener('beforeinstallprompt', (event) => {
			event.preventDefault();
			this.#deferred = event as BeforeInstallPromptEvent;
			this.available = true;
		});
		window.addEventListener('appinstalled', () => {
			this.#deferred = null;
			this.available = false;
			this.installed = true;
		});
	}

	/** Returns true when the listener accepted. The event is single-use either way. */
	async promptInstall(): Promise<boolean> {
		const event = this.#deferred;
		if (!event) return false;
		this.#deferred = null;
		this.available = false;
		try {
			await event.prompt();
			const choice = await event.userChoice;
			return choice.outcome === 'accepted';
		} catch {
			return false;
		}
	}
}

export const install = new InstallStore();
