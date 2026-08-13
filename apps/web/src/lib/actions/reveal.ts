import type { Action } from 'svelte/action';

interface RevealParams {
	/** Stagger delay in ms before this element animates in. */
	delay?: number;
	/** Vertical offset the element rises from. */
	y?: number;
	/** Start the animation immediately instead of waiting for viewport intersection. */
	immediate?: boolean;
	/** Transition duration in ms. */
	duration?: number;
}

// Scroll-reveal: fades + rises an element into view the first time it intersects
// the viewport. Honors prefers-reduced-motion and degrades to visible when
// IntersectionObserver is unavailable (SSR / old browsers).
export const reveal: Action<HTMLElement, RevealParams | undefined> = (node, params) => {
	const immediate = params?.immediate ?? false;
	if (typeof IntersectionObserver === 'undefined' && !immediate) return;

	const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
	if (reduce) {
		node.style.opacity = '1';
		return;
	}

	const delay = params?.delay ?? 0;
	const y = params?.y ?? 16;
	const duration = params?.duration ?? 550;

	node.style.opacity = '0';
	node.style.transform = `translateY(${y}px)`;
	node.style.willChange = 'opacity, transform';
	node.style.transition =
		`opacity ${duration}ms var(--ease-out, ease), transform ${duration}ms var(--ease-out, ease)`;
	node.style.transitionDelay = `${delay}ms`;

	const show = () => {
		node.style.opacity = '1';
		node.style.transform = 'none';
	};

	if (immediate) {
		const frame = requestAnimationFrame(show);
		return {
			destroy() {
				cancelAnimationFrame(frame);
			}
		};
	}

	const io = new IntersectionObserver(
		(entries) => {
			for (const entry of entries) {
				if (entry.isIntersecting) {
					show();
					io.unobserve(node);
				}
			}
		},
		{ threshold: 0.08, rootMargin: '0px 0px -40px 0px' }
	);
	io.observe(node);

	// Safety net: never let content stay invisible if the observer never fires
	// (background/hidden tab, throttled rendering, etc.).
	const fallback = setTimeout(() => {
		show();
		io.disconnect();
	}, 1600);

	return {
		destroy() {
			clearTimeout(fallback);
			io.disconnect();
		}
	};
};
