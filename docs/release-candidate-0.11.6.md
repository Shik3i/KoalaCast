# Android 0.11.6 (46): pre-release verification

Verified locally on 2026-09-07. This is a candidate record, not a Play production
approval or evidence that the branch has been merged or published.

## Fixes

- Android verification CI is allowed by the release policy without signing
  secrets or release packaging. Android tags and Docker tags remain separate.
- Release version preflight checks all historical Android tags, increasing
  `versionCode`/`versionName` and tag/name agreement; eight regression tests.
- APK and AAB signers must match the configured production key; debug keys are
  rejected. Temporary signing material is removed after builds.
- Windows SOPS recovery now has valid recipient syntax and LF dotenv data.
  `scripts/build-android-release.ps1` performs the complete sequential local gate.
- Missing widget authorization tokens no longer authorize private broadcasts;
  three regression tests cover missing, incorrect and correct tokens.
- Download foreground-promotion failures now enter the bounded retry and state
  handling instead of escaping before the transfer's error handler.
- Guest-mode privacy text and foreground-service declarations match the actual
  network use and periodic WorkManager implementation.
- Navigation Compose 2.10.0, Coil 3.6.0, reviewed action updates, current Node
  image digest and compatible web dependency updates are integrated.
- Go 1.26.8 / x/crypto 0.56.0 locally and Go 1.27.1 in Docker. Source and
  symbol-bearing production binaries have mandatory govulncheck v1.7.0 gates.
- CodeQL is configured for Go, JavaScript/TypeScript, Java/Kotlin and Actions.
  Kotlin uses a traced debug build, not unsupported no-build Kotlin analysis.
- Overlapping sync payload shapes use `anyOf`, not the invalid exclusive
  `oneOf` combination. API tag descriptions are present.
- Current English/German release notes and five 1080 x 1920 release screenshots.

## Passed local gates

| Gate | Result |
| --- | --- |
| Android debug unit tests | 195 passed, no failures |
| Android release unit tests | 101 passed, no failures |
| Android release lint | No errors; 38 warnings and 1 hint |
| Signed APK and AAB | Built; certificate identity verified |
| APK ZIP alignment | `zipalign -c -P 16 4` passed |
| Native ELF alignment | All 8 libraries in each APK/AAB pass 16 KB PT_LOAD alignment |
| App Bundle structure | Official bundletool 1.18.3 validation passed |
| Inbox device instrumentation | 3 passed on Android 36 |
| Web static checks | No Svelte errors or warnings; docs, i18n, policy and SEO passed |
| Web unit tests | 155 passed |
| Web UI tests | 49 passed, 8 explicitly skipped |
| Web production build | Passed |
| npm audit | 0 vulnerabilities |
| Go modules, vet, race tests | Passed |
| Go vulnerability analysis | 0 reachable vulnerabilities in source and pre-strip Docker binary |
| Docker | Built; isolated non-root container healthy; readiness database connected |
| Workflow syntax | actionlint passed |
| OpenAPI | Valid; 7 warnings for endpoints without documented 4xx responses |

The full `connectedDebugAndroidTest` invocation was repeated with
`--no-parallel --max-workers=1` and passed in 4m 24s, including all three
instrumented tests. The preceding parallel invocation hit a pre-test ADB
connection timeout in `AndroidAdditionalTestOutputPlugin`; no test was removed
or disabled. The sequential invocation is documented in the Android README.

The stripped Go executable conservatively reports GO-2026-5932 at module level:
govulncheck cannot extract its removed symbol table. The Docker gate scans the
same build with symbols before stripping, which confirms no reachable OpenPGP
code. No vulnerability was dismissed or suppressed.

## Device evidence and limits

The existing `koala36` AVD and its differently signed installed release were
preserved. A separate `koalacast-release-audit` Android 36 AVD was created for the
published GitHub APK's `45 -> 46` upgrade. Subscription, queue and saved episode
survived; background playback reached `PLAYING` with advancing position.
The final APK also completed an episode download after the app was backgrounded.

The six-hour auto-download job was identified through WorkManager diagnostics.
Forcing its Android scheduler job early is rejected by WorkManager's own timing
check. The subsequent regular run logged `AutoDownloadWorker` `SUCCESS` on
2026-09-07 at 17:28:42 (emulator log time), work ID
`902be876-b9f9-4dcf-9079-3090ea869804`. An automatically transferred new episode
was **not** verified by that scheduler result alone. Cast receiver and
Android Auto end-to-end playback were **not** verified by these emulator tests.
The instrumented tests use the debug inbox test target; they are not a substitute
for release APK feature tests or real Cast/Android Auto equipment.

Docker Desktop was not stopped, killed or restarted. Only newly created,
explicitly named audit containers were used; no existing data volumes were used.

## Remaining release gates

The authorized release recheck on 2026-09-07 found no open GitHub issues and
ten Dependabot update PRs. Node image, Go image, x/crypto, Navigation, Svelte
and @types/node updates are already included in this candidate. The newer
AGP 9.4.0, Vitest 5.0.0, Coil 3.6.1 and QEMU action update are not part of
this release; update PRs are not themselves vulnerability findings.
Dependabot and secret scanning show zero open alerts. Dependabot's displayed
dependency snapshot is still from 2026-08-25, so the fresh local npm audit and
Go source/binary scans are the current evidence, not that stale snapshot.

- Require successful checks on the exact PR/merged ref, including the first
  CodeQL run. A local workflow file is not proof of a successful remote scan.
- Review branch/tag protection and private vulnerability reporting separately;
  these repository settings are not changed by this candidate.
- Complete real-device Cast/Android Auto and new-episode auto-download checks.
- Review current Play Console declarations and store assets, then upload to the
  intended test track. Play submission and production access are separate from
  local build success.

No Android tag, GitHub release, Docker publication or Play upload is performed by
the local build script.
