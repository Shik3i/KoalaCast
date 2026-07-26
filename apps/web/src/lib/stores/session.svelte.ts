type SessionMinutes = 25 | 40 | 60;

class SessionStore {
	minutes = $state<SessionMinutes | null>(null);

	load() {
		try {
			const stored = localStorage.getItem('koalacast_session_minutes');
			if (!stored || stored === 'any') {
				this.minutes = null;
				return;
			}
			const value = Number(stored);
			if (value === 25 || value === 40 || value === 60) this.minutes = value;
		} catch (_) {}
	}

	set(minutes: SessionMinutes | null) {
		this.minutes = minutes;
		try {
			localStorage.setItem('koalacast_session_minutes', minutes === null ? 'any' : String(minutes));
		} catch (_) {}
	}
}

export const listeningSession = new SessionStore();
export type { SessionMinutes };
