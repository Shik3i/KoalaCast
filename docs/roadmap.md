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

## Audio visualisers on the Android player

**Status:** Off / Level / Waveform shipped; Bars and Blade open. Detailed plan and
findings: [roadmaps/audio-visualizer.md](roadmaps/audio-visualizer.md).

Palette-aware visualisers selectable in Settings beside the colour palette, riding
on the player's progress bar or replacing it. Fed by a custom Media3
`AudioProcessor` reading the app's own decoded PCM — deliberately *not* by
`android.media.audiofx.Visualizer`, which would require `RECORD_AUDIO`.

The plan's Phase 0 exists to answer the only open question: whether inserting a
processor into the chain preserves skip-silence and variable speed.

---

## Settings sync no longer drops the other client's keys

**Status:** fixed, and extended. Kept here because the invariant is easy to
break again.

Both clients push the whole `settings` entity and the server keeps the last write
without merging (`services/api/internal/server/handlers/sync.go`), but their
payloads do not overlap: Android sends `theme_mode`, `palette`, `proxy_images`,
`start_screen` and the download policy; the web client sends `date_format` and
`ui_language`. Each push used to erase the other client's keys.

Unknown keys are now remembered when a payload is applied and handed back when one
is pushed — `SyncedSettings.kt` on Android, `settings-merge.ts` on the web.

**The invariant:** a key added to a client's payload must be added to that client's
owned-key set in the same change. Miss it and the client stores its own key as
foreign and writes it twice. Both sides have a test asserting the owned set matches
what the payload writes; keep it that way.

Theme and palette stay readable from unscoped `localStorage` for pre-paint boot,
but their current values now enter the account settings blob and synchronize.

The related failure — one `updated_at` for the whole blob, so the newer write
reverted whatever the other device had just changed — is fixed as well: the blob
is merged per field. Both invariants are covered by unit tests on both clients,
and the wire contract is written down in
[sync-protocol/specification.md](sync-protocol/specification.md#settings-conflict-handling).
A field added to a payload must also be added to that client's owned-key set, or
it is treated as a foreign key and written twice.

---

## Native Android P7

**Status:** P0–P7 shipped. Remaining broader UI/integration coverage is tracked
in [`apps/android/README.md`](../apps/android/README.md). Architecture:
[android-architecture.md](android-architecture.md).

---

## Instance-owned legal metadata

**Status:** planned. Carried over from the API hand-off note that used to live at
the repository root; the sync, chapter, inbox-batching and podcast-settings items
it also listed have all shipped.

**Motivation:** the Android privacy screen can only link to the official
KoalaCast operator. That is wrong the moment somebody selects a self-hosted
instance run by a different operator under a different privacy policy — the app
then points at legal text that does not govern the service it is talking to.

Proposed unauthenticated `GET /instance`:

```json
{
  "service_name": "KoalaCast",
  "operator_name": "Example e.V.",
  "privacy_policy_url": "https://cast.example.org/privacy",
  "legal_notice_url": "https://cast.example.org/legal",
  "registration_enabled": true
}
```

- Values come from server configuration, never from request headers.
- URLs must be absolute HTTPS, except HTTP on loopback for development.
- An empty `legal_notice_url` is allowed; operator and privacy policy are not.
- Android shows this before registration and links it from Settings instead of
  the hard-coded official-instance text.
- `packages/openapi/openapi.yaml` needs the endpoint.

Acceptance: official defaults and a custom self-host configuration; relative or
invalid configured URLs fail startup validation; the response is reachable
without authentication and carries no deployment secrets.

---

## Account data control

**Status:** planned. Also carried over from the API hand-off note.

Two authenticated endpoints, needed before the in-app privacy screen can be
called complete:

- `GET /auth/export` — a downloadable JSON export of the account's metadata and
  every synchronized record. Never password hashes, recovery-code hashes,
  session secrets or device-token hashes.
- `DELETE /auth/account` — requires a password or recovery code in the body,
  deletes the account and all dependent rows in one transaction, and revokes
  every session and device token.

Both are destructive and security-sensitive: rate-limit them, require fresh
credentials, audit the event but never the supplied credentials, and prove by
test that one user cannot export or delete another's account.

Worth noting what already exists, so this is not rebuilt twice: the web client
can already delete everything held locally (see `resetAllLocalData`), and the
listening-data JSON export on the Profile screen covers the analytics half. What
is missing is the server-side account itself.

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
