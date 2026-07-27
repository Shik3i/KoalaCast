# Roadmap

Planned work, not yet implemented. Items here are proposals — scope and priority
can change. For what already exists, see [current-status.md](current-status.md).

---

## Weblate integration for translations

**Status:** planned
**Motivation:** today a translator needs a GitHub account, a fork, and a pull
request to fix a single word. That filters out exactly the people best placed to
improve a translation — native speakers who are not developers.

Weblate is a self-hostable, GPL-licensed translation platform. Translators work
in a browser; it commits back to the repository. Since KoalaCast's catalogues are
already plain JSON, one file per language, the format needs no changes.

### Why Weblate specifically

- Self-hostable and open source, consistent with the project's posture. Weblate
  also offers free hosting for libre projects.
- Speaks flat JSON natively — `apps/web/src/lib/i18n/messages/*.json` works as-is.
- Understands CLDR plural forms, which the catalogues already use.
- Commits back as normal git commits, so the repository stays the source of
  truth and nothing is locked inside a SaaS.

### Proposed configuration

A `.weblate` / component config pointing at:

| Setting | Value |
| :--- | :--- |
| File format | JSON (nested) |
| Filemask | `apps/web/src/lib/i18n/messages/*.json` |
| Monolingual base | `apps/web/src/lib/i18n/messages/en.json` |
| Source language | English |
| New language | Add automatically from the base file |

### Open questions to settle before starting

1. **Self-host or use hosted Weblate?** Self-hosting is another service to run
   and back up; hosted-for-libre-projects is free but external.
2. **How do new languages get registered?** Weblate can add a JSON file, but
   `registry.ts` still needs a one-line entry with the endonym and flag. Options:
   a maintainer step, a bot, or generating the registry from the directory
   listing at build time (removes the manual step, costs static typing of the
   locale list).
3. **Does CI gate Weblate's commits?** `npm test` and `npm run check:i18n`
   already catch broken placeholders and plural forms. They should run on
   Weblate's pull requests too, not just human ones.
4. **Review policy.** Weblate can require review before a translation lands, or
   commit directly. Direct commits are faster; review protects against
   well-meaning but wrong changes in a language no maintainer reads.

### Explicitly out of scope

Legal text stays English-only and must **not** be exposed to Weblate — see
[i18n.md](i18n.md#legal-content-is-not-translated). The privacy policy lives in
`apps/web/src/lib/data/privacy.ts` and is not part of the message catalogues, so
the filemask above already excludes it. Keep it that way.

### Rough steps

1. Decide hosting (question 1) and language-registration flow (question 2).
2. Add the Weblate component config and point it at the filemask.
3. Wire CI to run on Weblate's branches.
4. Add a translation status badge to the README.
5. Announce in the README that translations are open for contribution.

---

## Native Android P7

**Status:** P0–P6 shipped. The remaining platform and delight work is tracked in
[`apps/android/README.md`](../apps/android/README.md): Android Auto, a
home-screen widget, chapter UI, dynamic artwork palettes, advanced download
policies, and broader UI/integration test coverage. Architecture:
[android-architecture.md](android-architecture.md).

---

## Language filtering accuracy on search

**Status:** known limitation, see
[i18n.md](i18n.md#known-limitation-itunes-search).

The iTunes search endpoint returns only a title and author, which is too little
text for reliable language detection, so most search results come back with an
unknown language and are kept by the filter. Discover is unaffected (chart
endpoints include descriptions).

Options, roughly in order of preference:

1. **Document Podcast Index as the recommended configuration.** Setting
   `PODCAST_INDEX_KEY` / `PODCAST_INDEX_SECRET` makes the filter exact, because
   Podcast Index reports a real language per feed. Cheapest fix; already works.
2. **Resolve languages from the feed itself for the top N search results**, with
   a strict time budget and a persistent cache. Costs latency and outbound
   requests per search — weigh against the privacy-first posture.
3. **Cache every language we ever learn** in the `podcasts` table so the filter
   self-improves as feeds get ingested. Partially implemented already
   (`resolveLanguages`), but only covers feeds someone has added.
