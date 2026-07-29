export async function setBrowserNotificationsEnabled(enabled: boolean): Promise<boolean> {
	if (!enabled) return false;
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
	const body = episodes.length === 1
		? episodes[0].title
		: navigator.language.startsWith('de')
			? `${episodes.length} neue Folgen`
			: `${episodes.length} new episodes`;
	await registration.showNotification(podcastTitle, {
		body,
		icon: '/icon-192.png',
		badge: '/icon-72.png',
		tag: `new-episodes-${podcastId}`,
		data: { url: `/podcast/${encodeURIComponent(podcastId)}` }
	});
}
