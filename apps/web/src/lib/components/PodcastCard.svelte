<script lang="ts">
	import { optimizeArtwork } from '$lib/artwork';

	interface PodcastItem {
		id: string;
		title: string;
		author: string;
		feed_url: string;
		artwork_url: string;
		category?: string;
		categories?: string[];
		description?: string;
	}

	let {
		podcast,
		isSubscribed = false,
		onOpen
	}: {
		podcast: PodcastItem;
		isSubscribed?: boolean;
		onOpen: (pod: PodcastItem) => void;
	} = $props();
</script>

<div class="podcast-card" onclick={() => onOpen(podcast)} role="button" tabindex="0" onkeydown={(e) => e.key === 'Enter' && onOpen(podcast)}>
	<div class="art-wrap">
		<img
			src={optimizeArtwork(podcast.artwork_url, 220)}
			alt={podcast.title}
			class="art"
			loading="lazy"
			onerror={(e) => ((e.currentTarget as HTMLImageElement).src = '/placeholder.svg')}
		/>
		{#if isSubscribed}
			<span class="sub-badge" title="Subscribed"><i class="ph-fill ph-check" aria-hidden="true"></i></span>
		{/if}
	</div>
	<div class="info">
		<h4 class="title">{podcast.title}</h4>
		<p class="author">{podcast.author}</p>
	</div>
</div>

<style>
	.podcast-card {
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
		cursor: pointer;
		text-align: left;
		transition: transform 0.25s var(--ease-spring, ease);
		outline: none;
	}
	.podcast-card:hover { transform: translateY(-4px); }
	.art-wrap {
		position: relative;
		aspect-ratio: 1;
		width: 100%;
		border-radius: var(--radius-md, 14px);
		overflow: hidden;
		background: var(--bg-surface);
		box-shadow: var(--shadow-sm);
		border: 1px solid var(--border-subtle);
	}
	.art {
		width: 100%;
		height: 100%;
		object-fit: cover;
		transition: transform 0.3s ease;
	}
	.podcast-card:hover .art { transform: scale(1.04); }
	.sub-badge {
		position: absolute;
		top: 8px;
		right: 8px;
		background: var(--accent-green);
		color: #fff;
		width: 24px;
		height: 24px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		font-size: 0.75rem;
		box-shadow: 0 4px 10px rgba(0, 0, 0, 0.3);
	}
	.info { display: flex; flex-direction: column; gap: 0.2rem; }
	.title {
		font-size: 0.95rem;
		font-weight: 700;
		line-height: 1.25;
		color: var(--text-primary);
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.author {
		font-size: 0.8rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
</style>
