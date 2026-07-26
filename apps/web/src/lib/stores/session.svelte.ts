type SessionMinutes = 25 | 40 | 60;

class SessionStore {
	minutes = $state<SessionMinutes>(40);

	load() {
		try {
			const value = Number(localStorage.getItem('koalacast_session_minutes'));
			if (value === 25 || value === 40 || value === 60) this.minutes = value;
		} catch (_) {}
	}

	set(minutes: SessionMinutes) {
		this.minutes = minutes;
		try {
			localStorage.setItem('koalacast_session_minutes', String(minutes));
		} catch (_) {}
	}
}

export const listeningSession = new SessionStore();
export type { SessionMinutes };
