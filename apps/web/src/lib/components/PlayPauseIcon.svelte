<script lang="ts">
	let { playing = false }: { playing?: boolean } = $props();
</script>

<span class="play-pause-icon" class:playing aria-hidden="true">
	<i class="ph-fill ph-play play-icon"></i>
	<i class="ph-fill ph-pause pause-icon"></i>
</span>

<style>
	.play-pause-icon {
		display: inline-grid;
		width: 1em;
		height: 1em;
		place-items: center;
		line-height: 1;
	}
	i {
		grid-area: 1 / 1;
		display: block;
		line-height: 1;
		transition:
			opacity .18s ease,
			transform .24s var(--ease-spring, ease);
	}
	.play-icon { opacity: 1; transform: scale(1) rotate(0); }
	.pause-icon { opacity: 0; transform: scale(.55) rotate(12deg); }
	.playing .play-icon { opacity: 0; transform: scale(.55) rotate(-12deg); }
	.playing .pause-icon { opacity: 1; transform: scale(1) rotate(0); }

	@media (prefers-reduced-motion: reduce) {
		i { transition: none; }
	}
</style>
