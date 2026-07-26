<script lang="ts">
	import { onMount } from 'svelte';
	import { player, type CurrentTrack } from '$lib/stores/player.svelte';
	import { reorderLocalQueue } from '$lib/idb/db';
	import { t } from '$lib/i18n';
	import { listeningSession } from '$lib/stores/session.svelte';

	let now = $state(Date.now());
	let dragIndex = $state<number | null>(null);

	onMount(() => {
		player.loadQueue();
		listeningSession.load();
		const timer = window.setInterval(() => (now = Date.now()), 60_000);
		return () => window.clearInterval(timer);
	});

	const queueMs = $derived(player.queue.reduce((sum, item) => sum + Math.max(0, item.duration_ms || 0), 0));
	const currentRemainingMs = $derived(player.current ? Math.max(0, (player.durationMs || player.current.duration_ms) - player.positionMs) : 0);
	const totalRemainingMs = $derived(currentRemainingMs + queueMs);
	const naturalEndsAt = $derived(now + totalRemainingMs / player.playbackSpeed);
	const endsAtMs = $derived(player.sleepTimerEndsAt ? Math.min(naturalEndsAt, player.sleepTimerEndsAt) : naturalEndsAt);

	function duration(ms: number) {
		const minutes = Math.max(0, Math.round(ms / 60_000));
		return minutes >= 60 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : `${minutes}m`;
	}

	function finishTime(index: number) {
		const elapsed = currentRemainingMs + player.queue.slice(0, index + 1).reduce((sum, item) => sum + (item.duration_ms || 0), 0);
		return new Date(now + elapsed / player.playbackSpeed).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
	}

	async function remove(item: CurrentTrack) {
		await player.removeFromQueue(item.episode_id);
	}

	function dragStart(index: number) {
		dragIndex = index;
	}

	async function drop(target: number) {
		if (dragIndex === null || dragIndex === target) {
			dragIndex = null;
			return;
		}
		const queue = [...player.queue];
		const [item] = queue.splice(dragIndex, 1);
		queue.splice(target, 0, item);
		await reorderLocalQueue(queue.map((entry) => entry.episode_id));
		await player.loadQueue();
		dragIndex = null;
	}

	async function move(index: number, direction: -1 | 1) {
		const target = index + direction;
		if (target < 0 || target >= player.queue.length) return;
		dragIndex = index;
		await drop(target);
	}

	async function handleQueueKey(event: KeyboardEvent, item: CurrentTrack, index: number) {
		if (event.key === 'Delete' || event.key === 'Backspace') {
			event.preventDefault();
			await remove(item);
		} else if (event.key === 'ArrowUp') {
			event.preventDefault();
			await move(index, -1);
		} else if (event.key === 'ArrowDown') {
			event.preventDefault();
			await move(index, 1);
		}
	}

	async function trim() {
		if (listeningSession.minutes === null) return;
		let sum = 0;
		for (const item of player.queue) {
			const adjusted = (item.duration_ms || 0) / player.playbackSpeed;
			if (sum + adjusted <= listeningSession.minutes * 60_000) sum += adjusted;
			else await player.removeFromQueue(item.episode_id);
		}
	}

	async function shuffle() {
		const ids = player.queue.map((item) => item.episode_id);
		for (let index = ids.length - 1; index > 0; index--) {
			const target = Math.floor(Math.random() * (index + 1));
			[ids[index], ids[target]] = [ids[target], ids[index]];
		}
		await reorderLocalQueue(ids);
		await player.loadQueue();
	}
</script>

<aside class="running-order" id="running-order" aria-label={t('quiet.queue.title')}>
	<header>
		<div>
			<h2>{t('quiet.queue.title')}</h2>
			<span>{player.queue.length} · {duration(queueMs / player.playbackSpeed)}</span>
		</div>
	</header>

	{#if player.current}
		<div class="queue-now">
			<span class="equalizer" aria-hidden="true"><i></i><i></i><i></i></span>
			<div>
				<strong title={player.current.title}>{player.current.title}</strong>
					<span>{t('quiet.queue.playingNow')} · {t('quiet.queue.remaining', { duration: duration(currentRemainingMs / player.playbackSpeed) })}</span>
			</div>
		</div>
	{/if}

	<ol class="queue-list">
		{#each player.queue as item, index (item.episode_id)}
			<li
				draggable="true"
				ondragstart={() => dragStart(index)}
				ondragover={(event) => event.preventDefault()}
				ondrop={() => drop(index)}
			>
				<button class="drag" onkeydown={(event) => handleQueueKey(event, item, index)} aria-label={`${t('quiet.queue.drag')}; ${t('quiet.queue.keyboardMoveRemove')}`} title={`${t('quiet.queue.drag')}; ${t('quiet.queue.keyboardMoveRemove')}`}>
					<i class="ph ph-dots-six-vertical" aria-hidden="true"></i>
				</button>
				<span class="queue-number">{index + 1}.</span>
				<div>
					<strong title={item.title}>{item.title}</strong>
					<span title={item.podcast_title}>{item.podcast_title} · {t('quiet.queue.ends')} {finishTime(index)}</span>
				</div>
				<span>{duration(item.duration_ms)}</span>
				<button class="remove" onclick={() => remove(item)} aria-label={t('quiet.queue.remove')} title={t('quiet.queue.remove')}>
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
			</li>
		{:else}
			<li class="queue-empty">{t('quiet.queue.empty')}</li>
		{/each}
	</ol>

	{#if player.queue.length > 0 || player.current}
		<div class="queue-actions">
			{#if listeningSession.minutes !== null}
				<button onclick={trim}>{t('quiet.queue.trim', { count: listeningSession.minutes })}</button>
			{/if}
			{#if player.queue.length > 1}<button onclick={shuffle}>{t('quiet.queue.shuffle')}</button>{/if}
			{#if player.queue.length > 0}<button onclick={() => player.clearQueue()}>{t('quiet.queue.clear')}</button>{/if}
		</div>
		{#if player.queue.length > 1}<p class="queue-hint">{t('quiet.queue.hint')}</p>{/if}

		<footer>
			<span>{t('quiet.queue.endsAt')}</span>
			<strong>{new Date(endsAtMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</strong>
			<small>{player.playbackSpeed}× · {t('quiet.queue.shows', { count: player.queue.length })} · {player.sleepTimerEndsAt || player.sleepAtEpisodeEnd ? t('quiet.queue.sleepOn') : t('quiet.queue.sleepOff')}</small>
		</footer>
	{/if}
</aside>
