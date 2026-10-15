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

**Status:** server endpoint and web client shipped; Android adoption planned.

`POST /api/v1/inbox/episodes` takes up to 100 `{podcast_id, since}` watermarks and
returns the newest episodes of each feed in one response. Every feed keeps the
per-feed semantics (on-demand refresh only when the stored copy is older than 15
minutes, inclusive `since`, newest first), so ordinary subscriptions are still not
refreshed unconditionally. Results are grouped and bounded per feed rather than
returned as one global page: watermarks are per feed, and a global cut-off could
hide a quiet show's new episodes behind a busy show's backlog. The endpoint is
public like the per-feed route, because local-first listeners use the Inbox too,
and is throttled per client IP. The web Inbox uses it when two or more feeds are
stale and falls back to the per-feed route for any feed the batch does not
return.

Remaining work: Android refreshes its Inbox through the batched endpoint.

## Instance-owned legal metadata

**Status:** server endpoint shipped; Android adoption planned.

`GET /api/v1/instance` returns the deployment's `service_name`, `operator_name`,
`privacy_policy_url`, `legal_notice_url` and the effective `registration_enabled`
state, from validated server configuration only (never request headers). Legal
URLs are absolute HTTPS, except loopback HTTP in development. So that existing
deployments keep starting, only `service_name` and `registration_enabled` are
always present; `privacy_policy_url` defaults to the instance's own `/privacy`
page and `operator_name` / `legal_notice_url` are omitted until configured.

Remaining work: Android shows the selected instance's metadata before
registration and in Settings instead of the official instance's operator
information.

## Language filtering accuracy on search

**Status:** known limitation; see
[i18n.md](i18n.md#known-limitation-itunes-search).

iTunes search usually returns too little text for reliable language detection.
Unknown results are retained rather than hidden. Preferred options:

1. document Podcast Index credentials as the exact-language configuration;
2. persist every language learned from ingested feeds;
3. only if latency and privacy budgets permit, resolve a bounded number of top
   search results from their feeds.
