// Public i18n API.
//
// Components import from here and nowhere deeper:
//
//   import { t } from '$lib/i18n';
//   <h1>{t('discover.forYou')}</h1>
//   <p>{t('discover.showCount', { count: n })}</p>
//
// See docs/i18n.md for how to add a language.

export { t, n, loadLocale, currentLocale, type MessageKey, type Messages } from './runtime.svelte';
export {
	LOCALES,
	DEFAULT_LOCALE,
	isSupportedLocale,
	getLocaleConfig,
	resolveLocale,
	type LocaleConfig
} from './registry';
