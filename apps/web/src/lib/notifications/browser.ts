export async function setBrowserNotificationsEnabled(enabled: boolean): Promise<boolean> {
	if (!enabled) return false;
	if (typeof Notification === 'undefined' || !('serviceWorker' in navigator)) return false;
	const permission = Notification.permission === 'default'
		? await Notification.requestPermission()
		: Notification.permission;
	return permission === 'granted';
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
	const body = episodes.length === 1
		? episodes[0].title
		: navigator.language.startsWith('de')
			? `${episodes.length} neue Folgen`
			: `${episodes.length} new episodes`;
	await registration.showNotification(podcastTitle, {
		body,
		icon: '/icon-192.png',
		badge: '/icon-96.png',
		tag: `new-episodes-${podcastId}`,
		data: { url: `/podcast/${encodeURIComponent(podcastId)}` }
	});
}
