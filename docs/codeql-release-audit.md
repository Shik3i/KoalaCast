# Full default-branch CodeQL audit (2026-09-07)

The first PR scan reported no new results. After PR #55 merged, the full `main`
scan produced 19 baseline alerts (18 initially, then one Kotlin backup-policy
alert). Android release run `34154629466` was cancelled
before publication; no `android-v0.11.6` GitHub Release exists. Its pushed tag is
retained unchanged. The next candidate is Android `0.11.7 (47)`.

## Corrections

- All initial requests now pass URL validation in `safeRoundTripper`, not only
  redirect requests. The network dialer cannot see URL userinfo; this closes an
  initial embedded-credential relay gap for chapter/transcript/resolve clients.
- Static web files are opened with `os.OpenInRoot` and served from that same
  handle. This rejects traversal and symlink escapes without a check/reopen race.
  The SPA fallback is opened under the same root; assets retain range and cache
  behavior. See the [Go traversal-resistant API guidance](https://go.dev/blog/osroot).
- The SEO source check now compares a complete canonical source line rather
  than accepting a URL substring in arbitrary text.
- The Android publishing workflow requires all four successful full `main`
  CodeQL analyses on the exact current `main`/release SHA and zero open alerts before
  loading signing secrets. PR-only, stale, missing, failed or warning-bearing
  analyses fail closed. An old tag cannot be retried after newer main fixes close
  its alerts. Seven regression tests cover these states; a separate test locks
  the Android backup include lists.

## Alert-by-alert disposition and evidence

| Alerts | Rule | Disposition |
| --- | --- | --- |
| #1 | `js/incomplete-url-substring-sanitization` | Exact-line SEO assertion corrected. This is a local metadata check, not a runtime URL authorization boundary. |
| #2-4 | HTML filtering rules in `find-untranslated.mjs` | False positives: a local translation-report heuristic reads tracked Svelte files, removes script/style sections for phrase discovery and writes text to stdout. Its result is never rendered as HTML or used as a sanitizer. |
| #5 | `go/path-injection` | Root-bound open/serve replaces request-derived `os.Stat` plus unrestricted `http.Dir`. Traversal, Windows-style paths, symlink escape, normal asset, SPA and range tests cover the replacement. |
| #6 | `go/weak-sensitive-data-hashing` | Protocol-specific false positive: Podcast Index requires SHA-1 of API key + API secret + epoch for the Authorization header. This is not stored-password hashing; substituting a KDF breaks upstream authentication. |
| #7-12 | `go/request-forgery` | The production clients use `rss.NewSafeHTTPClient`, not the default transport. All DNS answers are checked, private/special/mapped/NAT64 addresses are blocked, the dial uses the checked IP (no second DNS lookup), redirects are revalidated, and environment proxies are not enabled. The separate initial-userinfo gap found during review is fixed above. Tests exercise the public first hop before a blocked redirect, mixed DNS answers, exact dial address and initial credentials before any dial. Test-only client overrides/AllowLoopback are not configured by production constructors. |
| #13-18 | `go/log-injection` | False positives under the actual production logger: `cmd/server/main.go` installs `slog.NewJSONHandler`; structured string/error fields are JSON escaped, not concatenated into raw records. Regression tests inject CR/LF, quotes and terminal escapes through request fields, podcast IDs and error values and assert one intact JSON record. |
| #19 | `java/android/backup-enabled` | Backups are deliberately limited to one non-credential DataStore preferences file by explicit include lists in both legacy and Android 12+ rules. The credential-bearing `secure_account.xml`, database and downloaded audio are outside those lists. A regression test locks the include lists and manifest bindings. This is not unrestricted credential backup. |

Podcast Index protocol evidence: [official authentication examples](https://github.com/Podcastindex-org/example-code/blob/master/README.md).

Android documents that an explicit include list disables the default inclusion
of other files: [Auto Backup inclusion rules](https://developer.android.com/identity/data/autobackup).
The symlink test cannot run on this Windows account without an additional OS
privilege. It was run successfully in a separate Linux container using the same
`static.go` and `static_test.go`, with no test skip and no OS settings changed.

No CodeQL query, language, source directory or test is excluded to make this audit
pass. Proven false positives are triaged individually with these explanations;
the full scan and publication gate remain enabled. Changes still require local
regression gates and fresh PR/default-branch scans before the new release.

## Authorized final verification and dual release

The user explicitly authorized reviewing all alerts, fixing genuine defects,
dismissing proven false positives individually, and publishing both release
channels. A fresh review of all 19 alerts confirms the dispositions above:
alerts #1 and #5 were automatically marked `fixed` by the full main scan on
`61bbe1c930af5d61583fe92cdffd0159d2335f98`, with no manual dismissal. The remaining
17 alerts were dismissed as `false positive` after re-verification, each with
its own explanation referencing this evidence. A fresh API read confirms zero
open main alerts. None is hidden by a query exclusion or a weaker scanner
configuration.

Both release targets are now `0.11.7`: web/Docker `v0.11.7`, Android
`android-v0.11.7` with versionCode 47. Existing tags stay unchanged. The Docker
publishing job now enforces the same exact-main-commit security gate before
registry login; a regression test checks both publishing jobs. This closes the
previous release-policy gap where the Android job checked baseline alerts but
the Docker job only required successful test jobs.

GitHub publication does not submit anything to Google Play. Cast receiver and
Android Auto head-unit tests remain unverified; no claim of those hardware tests
is made by a successful release workflow.
