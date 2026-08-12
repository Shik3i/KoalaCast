import { t } from '$lib/i18n';

export async function setBrowserNotificationsEnabled(enabled: boolean): Promise<boolean> {
	if (!enabled) {
		await disableBrowserNotifications();
		return false;
	}
	if (
		typeof Notification === 'undefined' ||
		!('serviceWorker' in navigator) ||
		!('PushManager' in window)
	) return false;
	const permission = Notification.permission === 'default'
		? await Notification.requestPermission()
		: Notification.permission;
	if (permission !== 'granted') return false;
	try {
		const configResponse = await fetch('/api/v1/push/config');
		if (!configResponse.ok) return false;
		const config = await configResponse.json();
		if (!config.configured || !config.vapid_public_key) return false;
		const registration = await navigator.serviceWorker.ready;
		const existing = await registration.pushManager.getSubscription();
		const subscription = existing || await registration.pushManager.subscribe({
			userVisibleOnly: true,
			applicationServerKey: urlBase64ToUint8Array(config.vapid_public_key)
		});
		const payload = subscription.toJSON();
		const saveResponse = await fetch('/api/v1/push/subscriptions', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ ...payload, locale: navigator.language })
		});
		return saveResponse.ok;
	} catch (_) {
		return false;
	}
}

/**
 * Turning notifications off has to reach the server too. Dropping the local
 * subscription alone would leave the endpoint registered and pushes arriving;
 * dropping only the server row would leave a subscription the browser keeps
 * renewing. Both go, and the server first, so a failure there does not strand a
 * row that nothing can delete afterwards.
 */
export async function disableBrowserNotifications(): Promise<void> {
	if (!('serviceWorker' in navigator)) return;
	try {
		// `ready` never resolves when no service worker has been registered. A data
		// reset must not hang forever in that common browser state.
		const registration = await navigator.serviceWorker.getRegistration();
		if (!registration) return;
		const subscription = await registration.pushManager?.getSubscription();
		if (!subscription) return;
		await fetch('/api/v1/push/subscriptions', {
			method: 'DELETE',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ endpoint: subscription.endpoint })
		}).catch(() => undefined);
		await subscription.unsubscribe();
	} catch (_) {
		// Best effort: the toggle must still flip even if the browser refuses.
	}
}

function urlBase64ToUint8Array(value: string): Uint8Array<ArrayBuffer> {
	const padding = '='.repeat((4 - (value.length % 4)) % 4);
	const base64 = (value + padding).replace(/-/g, '+').replace(/_/g, '/');
	const raw = atob(base64);
	const bytes = new Uint8Array(new ArrayBuffer(raw.length));
	for (let index = 0; index < raw.length; index++) bytes[index] = raw.charCodeAt(index);
	return bytes;
}

export async function notifyNewPodcastEpisodes(
	podcastId: string,
	podcastTitle: string,
	episodes: Array<{ id: string; title: string }>
) {
	if (
		!episodes.length ||
		typeof Notification === 'undefined' ||
		Notification.permission !== 'granted'
	) return;
	const registration = await navigator.serviceWorker.ready;
	// A real server push is already queued for this device; do not show the same
	// episode a second time from the foreground Inbox refresh.
	if ('pushManager' in registration && await registration.pushManager.getSubscription()) return;
	// Hand-rolled from navigator.language, this ignored the interface language the
	// listener actually chose and hardcoded the only two locales that existed at
	// the time. The catalogue already pluralises.
	const body = episodes.length === 1
		? episodes[0].title
		: t('inbox.newEpisodesCount', { count: episodes.length });
	await registration.showNotification(podcastTitle, {
		body,
		icon: '/icon-192.png',
		badge: '/icon-72.png',
		tag: `new-episodes-${podcastId}`,
		data: { url: `/podcast/${encodeURIComponent(podcastId)}` }
	});
}
