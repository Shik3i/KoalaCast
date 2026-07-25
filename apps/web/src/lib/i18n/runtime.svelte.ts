// Translation runtime.
//
// No i18n library. Messages are plain JSON, plural selection uses the platform's
// own CLDR data via `Intl.PluralRules`, and the reactive layer is a few lines of
// Svelte 5 runes. That keeps the static SPA bundle small, avoids a build step,
// and — because JSON is what translation platforms (Weblate, Crowdin, Lokalise)
// speak natively — lets contributors submit a language without touching code.

import en from './messages/en.json';
import { DEFAULT_LOCALE, getLocaleConfig, resolveLocale } from './registry';

/** A leaf is either a plain string or a set of CLDR plural forms. */
type MessageNode = string | { [category: string]: string } | { [key: string]: MessageNode };
type Catalogue = Record<string, MessageNode>;

/**
 * The message shape, inferred from the English catalogue. Because
 * `resolveJsonModule` preserves literal keys, this gives `t()` compile-time
 * autocompletion and typo detection without a codegen step.
 */
export type Messages = typeof en;

/** Dotted paths into the catalogue, e.g. "search.clearFilters". */
export type MessageKey = Paths<Messages>;

type Paths<T> = {
	[K in keyof T & string]: T[K] extends string
		? K
		: // A plural node is a leaf, not a branch: "one"/"other" are not addressable
			// keys. Every plural node carries "other" (enforced by the CI check), so
			// that is the marker — matching on the full category set would fail for
			// locales that only need two forms.
			T[K] extends { other: string }
			? K
			: `${K}.${Paths<T[K]>}`;
}[keyof T & string];

const catalogues: Record<string, Catalogue> = { en: en as Catalogue };

class I18n {
	/**
	 * The active locale. Kept here rather than in the preferences store so the
	 * runtime owns its own reactivity; the store drives it via `setLocale`.
	 */
	locale = $state<string>(DEFAULT_LOCALE);

	setLocale(code: string) {
		this.locale = resolveLocale(code);
	}
}

const i18n = new I18n();

/** The active locale code, reactive. */
export function currentLocale(): string {
	return i18n.locale;
}

/**
 * Loads a locale's catalogue and activates it.
 *
 * Await this before first paint (see `+layout.ts`) so a non-English UI never
 * flashes English on load. A locale that fails to load leaves the previous one
 * active rather than blanking the UI.
 */
export async function loadLocale(code: string): Promise<void> {
	const resolved = resolveLocale(code);
	if (!catalogues[resolved]) {
		const config = getLocaleConfig(resolved);
		// No loader means the fallback locale, which is already in `catalogues`.
		if (!config?.load) return;
		try {
			catalogues[resolved] = (await config.load()).default as Catalogue;
		} catch (_) {
			return; // keep the current locale rather than rendering an empty UI
		}
	}
	i18n.setLocale(resolved);
}

function lookup(locale: string, key: string): MessageNode | undefined {
	let node: MessageNode | undefined = catalogues[locale];
	for (const part of key.split('.')) {
		if (typeof node !== 'object' || node === null) return undefined;
		// A plural node is a leaf. Refuse to descend into it, so a dynamically
		// built key like "discover.showCount.one" misses rather than returning the
		// raw form — which would render a literal "{count}" to the user.
		if (isPluralNode(node)) return undefined;
		node = (node as Record<string, MessageNode>)[part];
	}
	return node;
}

const PLURAL_CATEGORIES = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);

function isPluralNode(node: MessageNode): node is Record<string, string> {
	if (typeof node !== 'object' || node === null) return false;
	const keys = Object.keys(node);
	return keys.length > 0 && keys.every((k) => PLURAL_CATEGORIES.has(k));
}

/**
 * Selects a plural form using the platform's CLDR data, so locales with more
 * than two categories (Polish's few/many, Arabic's zero/two) work without any
 * change to this code — the JSON simply carries the extra keys.
 */
function selectPlural(node: Record<string, string>, locale: string, count: number): string {
	const category = new Intl.PluralRules(locale).select(count);
	return node[category] ?? node.other ?? node.one ?? '';
}

function render(
	node: MessageNode | undefined,
	locale: string,
	params?: Record<string, string | number>
): string | undefined {
	if (typeof node === 'string') return node;
	if (node && typeof node === 'object' && typeof params?.count === 'number') {
		return selectPlural(node as Record<string, string>, locale, params.count);
	}
	return undefined;
}

/**
 * Translates a key in the active locale, substituting {placeholders}.
 *
 * Falls back to English when a translation is missing, then to the key itself —
 * an untranslated string shows up as "search.foo" rather than as a blank UI, so
 * gaps are obvious in review instead of invisible in production.
 */
export function t(key: MessageKey, params?: Record<string, string | number>): string {
	const locale = i18n.locale;
	const template =
		render(lookup(locale, key), locale, params) ??
		render(lookup(DEFAULT_LOCALE, key), DEFAULT_LOCALE, params) ??
		key;

	if (!params) return template;
	return template.replace(/\{(\w+)\}/g, (match, name) =>
		name in params ? String(params[name]) : match
	);
}

/** Formats a number in the active locale (thousands separators, etc.). */
export function n(value: number, options?: Intl.NumberFormatOptions): string {
	return new Intl.NumberFormat(i18n.locale, options).format(value);
}
