export function dedupeInboxEpisodes<T extends { id?: string | null }>(episodes: T[]): T[] {
	const byId = new Map<string, T>();
	for (const episode of episodes) {
		const id = typeof episode.id === 'string' ? episode.id.trim() : '';
		if (!id) continue;
		byId.set(id, id === episode.id ? episode : ({ ...episode, id } as T));
	}
	return [...byId.values()];
}
