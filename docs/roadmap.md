# Roadmap

Planned work only. Shipped behavior belongs in
[current-status.md](current-status.md); protocol invariants belong in their
specification. Scope and priority can change.

## Weblate integration

**Status:** planned

Expose `apps/web/src/lib/i18n/messages/*.json` through Weblate so translators do
not need a GitHub workflow for small fixes. The English catalogue remains the
monolingual base, CLDR plural validation remains enforced by `npm test` and
`npm run check:i18n`, and generated changes arrive as reviewable commits.

Decisions required:

1. hosted-for-libre-projects versus self-hosted Weblate;
2. maintainer, bot or generated registration of new locale files in
   `apps/web/src/lib/i18n/registry.ts`;
3. review requirements for languages no maintainer reads.

Legal content remains English-only and outside the catalogue file mask; see
[i18n.md](i18n.md#legal-content-is-not-translated).

## Audio visualiser hardening

**Status:** all five Android choices shipped; measurement/tuning remains.

Off, Level, Waveform, Bars and Pulse are implemented. Remaining work is physical
device battery/CPU measurement, broader signal tuning and regression checks for
plain-bar fallback on remote output paths. See
[roadmaps/audio-visualizer.md](roadmaps/audio-visualizer.md).

## Privacy-first discovery

**Status:** proposal.

The discovery proposal separates catalog transport from ranking and allows a
listener or self-hoster to choose more private data sources without silently
changing product semantics. See
[roadmaps/privacy-first-discover.md](roadmaps/privacy-first-discover.md).

## Batched Inbox endpoint

**Status:** planned.

Web and Android currently refresh each subscribed feed separately and merge the
results locally. Add one authenticated endpoint that accepts the account's
subscriptions plus per-feed watermarks, preserves the existing incremental
semantics, and returns a bounded publication-date-ordered page. The endpoint
must not turn ordinary subscriptions into unconditional background refreshes.

## Instance-owned legal metadata

**Status:** planned.

Android currently links official-instance operator information even after a
listener selects a self-hosted server. Add an unauthenticated `GET /instance`
contract so each deployment can provide its own identity and legal links:

```json
{
  "service_name": "KoalaCast",
  "operator_name": "Example e.V.",
  "privacy_policy_url": "https://cast.example.org/privacy",
  "legal_notice_url": "https://cast.example.org/legal",
  "registration_enabled": true
}
```

Requirements:

- values come from validated server configuration, never request headers;
- legal URLs are absolute HTTPS, except loopback HTTP in development;
- operator name and privacy URL are required;
- Android displays the selected instance's metadata before registration and in
  Settings;
- the OpenAPI contract and official/self-host tests ship with the endpoint.

## Language filtering accuracy on search

**Status:** known limitation; see
[i18n.md](i18n.md#known-limitation-itunes-search).

iTunes search usually returns too little text for reliable language detection.
Unknown results are retained rather than hidden. Preferred options:

1. document Podcast Index credentials as the exact-language configuration;
2. persist every language learned from ingested feeds;
3. only if latency and privacy budgets permit, resolve a bounded number of top
   search results from their feeds.
