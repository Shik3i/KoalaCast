<script lang="ts">
	import { shell, railSizes } from '$lib/stores/shell.svelte';

	let {
		side,
		label,
		controls
	}: {
		side: 'left' | 'right';
		label: string;
		controls: string;
	} = $props();

	let dragging = $state(false);
	let moved = false;
	let startX = 0;
	let startWidth = 0;

	const config = $derived(railSizes[side]);
	const width = $derived(side === 'left' ? shell.leftWidth : shell.rightWidth);
	const collapsed = $derived(side === 'left' ? shell.leftCollapsed : shell.rightCollapsed);

	function setWidth(next: number, final = false) {
		if (side === 'left') shell.setLeftWidth(next, final);
		else shell.setRightWidth(next, final);
	}

	function toggle() {
		if (side === 'left') shell.toggleLeft();
		else shell.toggleRight();
	}

	function reset() {
		if (side === 'left') shell.resetLeft();
		else shell.resetRight();
	}

	function pointerDown(event: PointerEvent) {
		if (event.button !== 0) return;
		(event.currentTarget as HTMLDivElement).focus();
		event.preventDefault();
		dragging = true;
		moved = false;
		startX = event.clientX;
		startWidth = width;
		(event.currentTarget as HTMLDivElement).setPointerCapture(event.pointerId);
		document.documentElement.classList.add('is-resizing-rails');
	}

	function pointerMove(event: PointerEvent) {
		if (!dragging) return;
		const travel = event.clientX - startX;
		if (Math.abs(travel) > 2) moved = true;
		setWidth(startWidth + (side === 'left' ? travel : -travel));
	}

	function finishPointer(event: PointerEvent) {
		if (!dragging) return;
		dragging = false;
		document.documentElement.classList.remove('is-resizing-rails');
		const target = event.currentTarget as HTMLDivElement;
		if (target.hasPointerCapture(event.pointerId)) {
			target.releasePointerCapture(event.pointerId);
		}
		if (moved) setWidth(width, true);
		else toggle();
	}

	function cancelPointer(event: PointerEvent) {
		if (!dragging) return;
		dragging = false;
		document.documentElement.classList.remove('is-resizing-rails');
		const target = event.currentTarget as HTMLDivElement;
		if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
		setWidth(startWidth, true);
	}

	function keyDown(event: KeyboardEvent) {
		let next: number | null = null;
		const step = event.shiftKey ? 32 : 8;
		if (event.key === 'ArrowLeft') next = width + (side === 'left' ? -step : step);
		else if (event.key === 'ArrowRight') next = width + (side === 'left' ? step : -step);
		else if (event.key === 'Home') next = config.collapsed;
		else if (event.key === 'End') next = config.max;
		else if (event.key === 'Enter' || event.key === ' ') {
			event.preventDefault();
			toggle();
			return;
		}
		if (next === null) return;
		event.preventDefault();
		setWidth(next, true);
	}
</script>

<!-- The WAI-ARIA window-splitter pattern is intentionally a focusable, interactive separator. -->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
	class="rail-resizer {side}"
	class:dragging
	class:collapsed
	role="separator"
	tabindex="0"
	aria-orientation="vertical"
	aria-label={label}
	aria-controls={controls}
	aria-valuemin={config.collapsed}
	aria-valuemax={config.max}
	aria-valuenow={width}
	aria-valuetext={`${width} px`}
	title={`${label} · ${width}px`}
	onpointerdown={pointerDown}
	onpointermove={pointerMove}
	onpointerup={finishPointer}
	onpointercancel={cancelPointer}
	ondblclick={reset}
	onkeydown={keyDown}
>
	<span aria-hidden="true">
		<i class="ph {side === 'left'
			? collapsed ? 'ph-caret-right' : 'ph-dots-three-vertical'
			: collapsed ? 'ph-caret-left' : 'ph-dots-three-vertical'}"></i>
	</span>
</div>
