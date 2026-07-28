# Privacy-first Discover roadmap

Status: planning only. No recommendation tracking or ranking code is enabled by this document.

## Product contract

- Useful without an account.
- Listening behaviour stays on the listener's device by default.
- Every recommendation explains which local or public signal caused it.
- No third-party analytics, fingerprinting, advertising profile, or sale/export of listening history.
- Syncing recommendation preferences is a separate, explicit opt-in from account sync.
- A listener can inspect, correct, export, pause, and delete the local model.

## Phase 0 — measurement without surveillance

- Define offline evaluation fixtures from synthetic libraries and opt-in donated test sets.
- Add deterministic ranking tests for cold start, sparse history, language, explicit-content, and accessibility cases.
- Establish quality metrics: useful opens, hides, topic diversity, freshness, repeat suppression, and explanation accuracy.
- Establish privacy budgets: zero raw listening events leave the device; no stable cross-service identifier.

Exit gate: repeatable benchmark suite and reviewed threat model.

## Phase 1 — transparent local signals

- Build an on-device preference profile from subscriptions, completed episodes, manual saves/hides, language, and published RSS categories.
- Use coarse aggregates rather than a permanent event log.
- Add “Why this?”, “Less like this”, “Hide show”, and “Reset recommendations”.
- Keep editorial charts and local personalization visibly separate.

Exit gate: all ranking inputs visible in Settings; deleting the profile restores a cold start immediately.

## Phase 2 — candidate generation

- Fetch broad candidates from public charts, followed-show adjacency, and category/language feeds.
- Send only coarse, non-user-specific catalogue queries to the server.
- Cache candidate pools for hours; rank and filter locally.
- Enforce diversity caps so one publisher, category, or popularity tier cannot dominate.

Exit gate: useful offline reranking from the last cached candidate pool.

## Phase 3 — optional private sync

- Define a compact preference vector with versioning, expiry, and no raw episode history.
- Encrypt it end-to-end before it leaves the device.
- Make recommendation sync a separate switch with a clear data preview.
- Support key rotation, device revocation, export, and verified deletion.

Exit gate: independent crypto/privacy review and cross-device conflict tests.

## Phase 4 — privacy-preserving learning experiments

- Evaluate federated or aggregate learning only if local heuristics plateau.
- Require clipping, minimum cohort sizes, differential privacy, short retention, and public documentation.
- Ship experiments behind explicit consent and remote kill switches.
- Never weaken the Phase 1 local-only experience for non-participants.

Exit gate: published design review, measurable benefit over local ranking, and a reversible rollout.

## Delivery sequence

1. Threat model and synthetic evaluation harness.
2. Local profile schema and user controls.
3. Explainable local ranker.
4. Cached candidate service.
5. Accessibility, bias, and adversarial-feed audit.
6. Optional encrypted sync.
7. Only then consider aggregate learning.

## Explicit non-goals

- Server-side raw listening histories for recommendations.
- Inferred sensitive traits.
- Hidden engagement optimisation.
- Sponsored ranking without a permanent, unmistakable label.
- Recommendation quality that depends on creating an account.
