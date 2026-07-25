// Extracts a representative accent color from podcast/episode artwork so the UI
// can theme itself around the current show (à la Spotify/Apple). Falls back to
// null when the image can't be sampled (decode error) — the caller then keeps the
// default forest-green accent.
//
// The image is loaded through the same-origin privacy proxy (optimizeArtwork), so
// sampling neither leaks the user's IP to third-party CDNs nor taints the canvas
// (remote CDNs rarely send CORS headers, which used to make this silently fail).

import { optimizeArtwork } from '$lib/artwork';

const cache = new Map<string, string | null>();

export async function dominantColor(url: string | undefined | null): Promise<string | null> {
	if (!url || typeof document === 'undefined') return null;
	if (cache.has(url)) return cache.get(url) ?? null;

	const result = await new Promise<string | null>((resolve) => {
		const img = new Image();
		img.crossOrigin = 'anonymous';
		img.decoding = 'async';
		img.onload = () => {
			try {
				const size = 28;
				const canvas = document.createElement('canvas');
				canvas.width = size;
				canvas.height = size;
				const ctx = canvas.getContext('2d', { willReadFrequently: true });
				if (!ctx) return resolve(null);
				ctx.drawImage(img, 0, 0, size, size);
				const { data } = ctx.getImageData(0, 0, size, size);

				// Weight toward saturated, mid-lightness pixels; ignore transparent,
				// near-white, near-black and grey so the accent is actually vivid.
				let r = 0;
				let g = 0;
				let b = 0;
				let total = 0;
				for (let i = 0; i < data.length; i += 4) {
					const cr = data[i];
					const cg = data[i + 1];
					const cb = data[i + 2];
					const ca = data[i + 3];
					if (ca < 125) continue;
					const max = Math.max(cr, cg, cb);
					const min = Math.min(cr, cg, cb);
					const light = max / 255;
					const sat = max === 0 ? 0 : (max - min) / max;
					if (light > 0.92 || light < 0.12 || sat < 0.2) continue;
					const w = sat * 2 + 0.25;
					r += cr * w;
					g += cg * w;
					b += cb * w;
					total += w;
				}
				if (total === 0) return resolve(null);
				resolve(`rgb(${Math.round(r / total)}, ${Math.round(g / total)}, ${Math.round(b / total)})`);
			} catch {
				resolve(null); // tainted canvas (no CORS headers on the CDN)
			}
		};
		img.onerror = () => resolve(null);
		// Sample a small proxied copy — enough for an average color, cheap to decode.
		img.src = optimizeArtwork(url, 96);
	});

	cache.set(url, result);
	return result;
}
