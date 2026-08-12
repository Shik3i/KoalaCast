/**
 * Installability of the web app as a standalone window.
 *
 * Chromium owns the install promotion. Do not cancel `beforeinstallprompt` here:
 * doing so suppresses Android's native mini-infobar and produces the exact
 * "Banner not shown" diagnostic unless a custom button is used later. The
 * browser-provided UI remains available without an app-side event handler.
 */

class InstallStore {
	/** True when the app is already running as an installed window. */
	installed = $state(false);

	#listening = false;

	listen() {
		if (this.#listening || typeof window === 'undefined') return;
		this.#listening = true;
		this.installed = window.matchMedia?.('(display-mode: standalone)').matches === true;
		window.addEventListener('appinstalled', () => {
			this.installed = true;
		});
	}
}

export const install = new InstallStore();
