# Verification status

This file records Android acceptance separately from the existing application's
behavior. Physical-phone verification has not been done in this workspace;
emulator results do not establish every phone's touch behavior, battery use,
installed WebView compatibility or long-running network reliability.

Verified locally on October 24, 2026:

- Strict TypeScript checking and self-contained runtime build.
- 20 mobile/runtime/release-policy tests, including unpublished-draft lookup
  regressions. [Source CI](https://github.com/MachoDrone/gridlens-android/actions/runs/40121818743) passed.
- The complete copied application check: 816 Node tests and 332 browser tests.
- Android debug and release builds, 9 native update-policy tests and release lint.
- Signed APK verification and 16 KiB ZIP alignment; ELF load segments in all
  six native libraries across arm64/x86_64 also have at least 16 KiB alignment.
- Android API 35 x86_64 emulator with WebView 124: actual embedded Node startup,
  original Fleet rendering with eight synthetic hosts, denied unauthenticated
  loopback access, authenticated reads, valid/invalid roster imports, private
  persistence, pause/resume, actual Android APK rejection and the read-only
  update provider boundary.

The initial emulator run exposed a runtime entry-path bug: Android's canonical
module path could differ from its startup argument, silently skipping the entry
guard. A dedicated unconditional mobile entry fixed it. A symlink regression test
and the repeated emulator smoke both pass. The desktop checkout was unchanged.
Automated application/emulator smoke tests use synthetic inputs.

## Published APK and actual update

[Version 0.1.0](https://github.com/MachoDrone/gridlens-android-releases/releases/tag/v0.1.0)
is Android version code 2. GitHub built and signed its APK from source revision
`06b403fa9aa1a1c9fbb6e3f907c79797553e1617`. It is 194,744,285 bytes with SHA-256
`ae8d52c1b6ab3f4d1c2a4b5913b66614ce2e2a3139d0cd420ce885706e45f7d6`.
The public latest manifest, anonymous APK download, package identity, signing
certificate and every bundled runtime-file hash were verified.

The first publication exposed runner-tool assumptions and GitHub's unpublished
draft lookup behavior. Explicit SDK setup, a Node-based certificate check and
authenticated draft listing corrected these. The corrected publisher finalized
the already-built, verified APK. The subsequent [release workflow](https://github.com/MachoDrone/gridlens-android-releases/actions/runs/40123016761)
successfully verified the completed publication without replacing its assets.

The actual Update button was exercised on the API 35 emulator using a separately
built, signed older fixture (code 1, name 0.0.0). It fetched the public GitHub
manifest, downloaded the published APK, passed integrity/package/signature checks,
opened Android's installation-permission settings and completed Android's normal
update confirmation. After reopening, version code 2/name 0.1.0 was installed.
Both synthetic roster files retained their original hashes/counts; the selected
Fresh scenario, saved loopback port and Duration animation Off preference survived.
Pressing Update again reported version 0.1.0 as up to date without downloading
another APK. No operator roster or live Nosana-provider scenario was used.

The first download attempt returned the updater's generic failure message. An
unchanged retry completed successfully; its original cause was not established.
This is recorded separately from the successful installation and persistence
checks rather than attributed to a specific network or provider cause.

Remaining acceptance is installing from GitHub on the operator's
phone, checking all Fleet actions in portrait/landscape, background/resume and
live internet data with the laptop off, then installing a later signed update
through the Update button while preserving its actual lists and preferences.

## Operator device targets

The first physical installation/test is on a Pixel 10 Pro XL with its factory OS.
GrapheneOS is a required support target; its acceptance remains unverified. The
operator can test Pixel 7, Pixel 9 Pro XL and Pixel 10 Pro XL on GrapheneOS, with a
possible factory-OS Galaxy S22 Ultra check. GrapheneOS-specific investigation is
deferred until the operator has installed and tested the app there; existing
protections are not changed preemptively.

During initial download the operator reported a rounded `194.74/194.74 MB` count
with only Cancel visible. This is consistent with the browser's download UI;
GridLens's in-app updater displays percentage progress. A fresh anonymous download
reached EOF with exactly 194,744,285 bytes and the published checksum. That confirms
the public artifact can transfer completely, but does not establish the state or
cause of the phone's unfinished download. Check the browser Downloads list or the
Files app for a completed APK before cancelling/retrying; an incomplete download
must not be renamed to make it appear complete.

## Network Discovery candidate 0.2.0 (version code 3)

This candidate adds native network discovery and saved fleets. It is prepared for
an explicitly requested GitHub prerelease download on the factory-OS Pixel 10 Pro
XL. It is not stable promotion: source `main`, GitHub Latest and the normal Update
channel remain on the previous release until physical acceptance.

Verified locally for this candidate:

- 31 mobile/runtime/release tests, including saved-fleet persistence/recovery,
  native-only mutations, known-ID selection, retained membership and idle launch.
- Full `npm run check` passed: 816 inherited application Node tests and all
  332 browser tests, alongside the 31 mobile/runtime tests above.
- 37 native unit tests in each debug and release configuration; release lint
  reports no issues. Debug, instrumentation and signed release APK builds pass.
- An isolated API 36 x86_64 emulator ran the actual Node/WebView smoke and the
  new Android Keystore checks: encrypted persistence, forget, explicit recovery
  after key invalidation (including a save with previously cached credentials),
  preserved network profile/trust, and generated
  RSA/ECDSA/Ed25519 plain/encrypted imports and signature verification.
  Launch, pause/resume and idle Network Discovery refresh kept native search and
  connection counters at zero. Native Scenario navigation from Fresh to the
  empty Supplied Host Addresses roster was also exercised with touch input.
- The release APK has the existing signing certificate, valid signatures and
  16 KiB ZIP alignment. Every embedded runtime file matches its integrity
  manifest and the tested runtime output. No fleet/credential stores are bundled.

The first emulator smoke timed out waiting for a WebView JavaScript callback;
an unchanged test rerun passed after startup. The emulator also showed a System
UI not-responding dialog during later manual interaction. Those observations are
not claimed as physical-device results or attributed to an application defect.
The first full browser run passed 331/332; one compositor-marker assertion failed
although the captured image still showed navigation. The final full check passed without concurrent emulator/native-build work,
with no assertion or timeout weakening. Record both observations rather than treating the first runs as passes.

Pure route-policy and socket/SSH fixture checks cover /16 expansion, full-tunnel
ambiguity, explicit IPv6, network identity changes, concurrency, host-key changes,
authentication failure, sudo denial, wrappers, API fallback and partial inventory.
They do not establish Android VPN routing or callback behavior on actual phones.
Physical Wi-Fi/VPN transitions, authorized real-PC discovery, GrapheneOS, and this
candidate's real app-update preservation remain pending. Use
[DISCOVERY_ACCEPTANCE.md](DISCOVERY_ACCEPTANCE.md); retain normal OS protections.

Candidate APK: 197,209,359 bytes; SHA-256
`3e67ac56815a8b0eea568acb0d90371366f6a59bab3b711c40865f93de79cdf0`.
It is distributed as a prerelease download, without an Update manifest.

## Android 0.2.1 verification — October 24, 2026

Version code 4 connects dated native PC/IP metadata, adds an explicit optional
Docker GPU recorder, and highlights a validated newer update in green after a
bounded foreground check. The operator requested installation through Update;
this advances the normal channel without claiming the entire physical acceptance
matrix is complete. The operator previously confirmed eight hosts and saved-fleet
reopening on the factory-OS Pixel 10 Pro XL.

Completed on the final code and pinned toolchains:

- `npm run check` passes: 41 Android runtime, persistence, release-policy,
  provenance and phone-browser tests; 17 Python collector tests; 816 inherited
  application Node tests; all 332 inherited browser tests (11.6 minutes).
- Clean Android build: all 56 JVM tests pass in both debug and release variants;
  release lint and debug/instrumentation/release APK assembly pass.
- The APK declares `com.gridlens.android`, version `0.2.1`, code `4`. Its release
  certificate matches the existing pinned signing identity; APK signatures and
  16 KiB ZIP alignment verify. All 45 bundled runtime files match their integrity
  manifest; the packaged collector matches the reviewed source exactly.
- The complete mobile adaptation patch reproduces all 194 vendored files from
  192 independently hash-verified original source files. No desktop files or
  processes were changed. Local rosters, credentials and build artifacts were
  excluded from source publication.
- JVM fixtures verify ownership/configuration checks and fixed Docker operations.
  The real Java command builders preserve the complete Python source through shell
  quoting; actual Go template execution verifies all 28 inspection fields against
  synthetic Docker structures. This is not an actual Docker/NVIDIA startup test.

An initial incremental Android build failed with D8 nest-host/classpath errors
while native verification overlapped. A subsequent clean build ran after all
other Gradle tasks ended and passed. The optional new emulator fixture could not
boot because the installed image required more free disk space; that fixture was
removed. No emulator or physical-device acceptance is claimed for this revision.
Docker is unavailable in this development workspace, and no production PC was
contacted or modified. NVIDIA container compatibility and device installation
remain physical checks for the native enable flow.

Exact automatic rewards remain unavailable: no supported authorization was
established, existing node keys were not accessed, and no amount is substituted.
GPU records remain PC-level; no host-to-GPU mapping or job causation is claimed.
See [PC telemetry](PC_TELEMETRY.md) for setup and source limitations.

Verified APK size: 197237842 bytes. SHA-256:
`496f7c4caee8fb6b87e4744cb497cc5056bfb7df57726c913af602e4c8f866c7`.

## Android 0.2.2 — app-owned monitoring, October 25, 2026

Version code 5 moves PC verification and recurring monitoring into the native app.
Enable / repair monitoring reuses saved endpoints and encrypted credentials,
verifies exact node/container/GPU identities, and records bounded local permission.
The approved node performs its fixed rewards GET internally; only sanitized
amount/status/time leave it. GPU conditions use verified public job intervals,
with current-assignment proof required for an open-ended RUNNING record.
No developer-side PC authorization or live-wallet test is part of this workflow.

Verification on the final implementation and pinned toolchains:

- 95 Android/runtime/reward-reader/persistence/phone-browser tests pass. The reward
  and browser fixture checks were also rerun after replacing an example amount
  with a deliberately generic synthetic value; no operator readings are fixtures.
- 27 Python recorder tests and 816 inherited application Node tests pass.
- The inherited browser run passed 327 tests; five long interaction/large-roster
  cases timed out. All five passed unchanged with one worker. The final job-time
  guard and independent rewards freshness were then checked in the Android tests,
  and all 11 affected inherited job-history browser tests passed. Test timeouts
  and assertions were not relaxed; initial failures remain recorded locally.
- All 88 Android unit tests pass in both debug and release variants. Clean builds,
  release lint and final APK repackaging pass. The signed package remains
  com.gridlens.android, version 0.2.2/code 5, with the existing release certificate.
- The complete mobile adaptation patch reproduces 196 files from 192 verified
  originals. All 47 bundled runtime files match their integrity manifest; helper
  assets match the reviewed sources. APK signature and 16 KiB alignment verify.
- Real Android API 36 x86_64 emulator verification passed: native-only Keystore
  and monitoring permissions in 3.5 seconds, then full Smoke in 7.0 seconds.
  Full Smoke covers embedded Node/WebView, eight synthetic hosts, loopback
  authentication, roster persistence, lifecycle, updater rejection checks and
  both native storage verification suites. Native discovery/SSH counters stay zero.

The isolated emulator initially suffered System UI/Google-service ANRs before
GridLens launched; two UI checks timed out. The final run used a smaller 540x1200
fixture, allowed boot settling, and ran with greater available host memory. The
startup test probe was made safe before document.body exists; its five-second
JavaScript callback timeout was unchanged. Several conditions changed, so no
single cause is established. The final run had no observed ANR/FATAL entries.
Only the owned emulator/test data were used; physical phones and operator PCs
were not contacted. Factory Pixel and GrapheneOS acceptance remain distinct.

The native-only mode is explicitly scoped and does not stand in for full Smoke:

```sh
adb -s emulator-5562 shell am instrument -w -e nativeOnly true \
  com.gridlens.android.test/com.gridlens.android.SmokeInstrumentation
```

The app performs actual per-PC verification after the user's native monitoring
approval. Unsupported layouts or denied Nosana reads remain explicit; an app
build/test result is not a claim that every operator PC has already been checked.
See [the Android rewards/GPU flow](REWARDS_AND_GPU_TELEMETRY_GAPS.md).

## Android 0.2.3 column alignment — October 26, 2026

Version code 6 uses normal 11px regular PC text, the reference four-mode IP cycle,
and nearest-whole-NOS green reward links. Exact rewards and their original times
remain available for sorting/details. Verified zero and unknown remain distinct.
Public egress is read inside a verified node through the fixed ipify HTTPS helper;
private IP comes from reported PC interfaces. One persisted lookup per PC per
30 minutes serves all its hosts, including cached failures. Public/private source
times are independent and old observations are not renewed by display or retry.

Verification on the final candidate:

- `npm run check` passes: 104 app/runtime/helper/browser tests, 27 recorder tests,
  816 inherited Node tests, and all 332 inherited browser tests (11.8 minutes).
- All 100 Android unit tests pass in each debug and release variant. Clean APK
  builds and release lint pass. Fixed helper execution, no redirects, response
  bounds, global/private IP classification, cached failures, restart persistence,
  concurrent per-PC sharing and identity-check ordering use isolated fixtures.
- Phone-browser tests cover all four IP modes, saved mode after reload, retained
  addresses/source times after failure, ordinary PC/IP typography, exact reward
  sorting despite equal rounded displays, half-NOS boundaries, green host-page
  links, gray retained links, verified zero and unknown values.
- The APK retains the existing application ID and release certificate. Signature,
  16 KiB alignment, bundled runtime hashes and helper-source hashes verify.

The reference formatter/styles were checked against the original public worker
at commit 5384d91f0e02738a654540624bdcb355c9442fe0. Its false-zero fallback is not
copied: missing rewards remain unknown. Non-Android snapshot behavior is preserved
through the trusted Android marker and explicit hashed source adaptations.
No developer connection to an operator PC, live wallet read, workload restart,
host installation or network configuration change was made for this update.

## Android 0.2.4 version and update visibility — October 26, 2026

Version code 7 shows the installed APK version beneath GridLens, a separate update
status/check time, and app information when the brand is tapped. The installed
version comes from the APK, never the release feed. A neutral button is not used
as evidence of an up-to-date installation. Five-minute foreground checks replace
the old six-hour resume-only policy; manual checks bypass the local interval but
honor explicit provider delays. Downloads and installation remain user confirmed.

Verification:

- `npm run check` passes: 104 app/runtime/helper/browser tests, 27 recorder tests,
  816 inherited Node tests and all 332 browser tests (11.6 minutes).
- All 113 Android unit tests pass in both debug and release variants. Updater
  tests cover status distinctions, retained availability, timer eligibility,
  provider delays, stale metadata writers, late responses across controllers and
  successful checks that must not erase a future provider deadline. Clean builds,
  final rebuild, release lint and APK assembly pass.
- Isolated Android API 36 x86_64 Smoke passes with unchanged five-second JavaScript
  callback timeout. It verifies installed-version visibility, independent latest
  release display, status transitions, green availability retained on failure,
  exact 390dp toolbar layout, caption accessibility, opaque colors, at least
  4.5:1 button contrast and preservation of the native background drawable.
- Final visual review confirms visible v0.2.4, explicit update status and a readable
  gray/white Update button alongside Fleets/Hosts. The fixture's screenshot width
  is 586 pixels (391dp after emulator rounding); the test separately measures the
  toolbar at exactly 390dp. Only synthetic app data were used.

An initial screenshot exposed a blank-looking neutral button: resetting a null
background tint cleared its themed fill while retaining white text. The final
build explicitly uses #595959 with white text for neutral states, preserving
bright green/black for known available updates. The earlier failed visual evidence
is retained locally; the corrected build passed the strengthened checks.

Provider retry state has one serialized, monotonic writer shared across controller
instances. Ordinary status writes cannot overwrite it; dispatch reads current
shared storage and success does not clear a later deadline. Retry times are
eligibility bounds, not promises of recovery. No PC/Nosana polling, credentials,
wallet access or collection behavior changed. No physical phone or operator PC
was accessed during these checks; actual installed phone version cannot be inferred
from the public release manifest or an unhighlighted button.

## Android 0.2.5 — reward contrast and update download lifetime, October 26, 2026

Version code 8 uses #166534 for current Rewards to claim, darker than NOS #15803d.
Rounded display, exact sorting, retained-value gray and source timestamps remain
unchanged. NAT public-IP lookup already originates inside a verified node; this
release documents that behavior without changing the reader or its cache policy.
No cause for a particular phone's earlier download failure is inferred from the
fact that switching screens preceded it.

An explicitly approved APK download now belongs to a private Android dataSync
foreground service. Downloaded MB, total MB and percent remain available in the
native UI and notification. Activity destruction detaches the screen; only an
explicit Install action can open Android's installer. Per-job partial files,
atomic promotion and service ownership guards prevent stale cancellation,
cleanup or notification actions from replacing a newer transfer.

Verification on the final implementation:

- `npm run check` passes: 104 app/runtime/helper/browser tests, 27 Python recorder
  tests, 816 inherited Node tests and all 332 inherited browser tests (11.0 minutes).
  The focused Android browser test verifies the darker reward color independently
  of unchanged NOS and retained-value colors.
- All 124 Android unit tests pass in each debug and release variant. Clean builds,
  release lint and debug/instrumentation/release APK assembly pass. Transfer tests
  cover redirects, size limits, truncated/tampered bodies, cancellation, timeouts,
  byte display, stale ownership and failed replacement-start cleanup.
- The signed APK remains com.gridlens.android with the pinned release certificate.
  APK signature and 16 KiB alignment verify. All 47 runtime files and four helper
  assets match their reviewed sources; no synthetic APK fixture is packaged.
- The complete adaptation patch reproduces all 196 vendored files from the 192
  independently verified originals. No desktop files or operator PCs were changed.
- The actual dataSync service passed an isolated Android API 36 background-transfer
  check in 22.9 seconds with notification permission denied. Bytes advanced after
  Activity destruction and Home; reopening attached to the same job and displayed
  downloaded/total MB and percentage. The real hash/package/signing checks reached
  READY, the native UI showed completion, and the installer monitor recorded zero
  launches. The synthetic APK was never installed. Passive screenshots confirm
  readable version and progress; no discovery/SSH connection was started.
- Full Android smoke passed in 7.8 seconds, covering Node/WebView, version and
  progress presentation, the exact 390dp toolbar checks, update-store recovery,
  APK rejection/provider checks and the native vault/monitoring suites. Its first
  run timed out at the unchanged five-second WebView JavaScript probe during
  initial emulator startup; the unchanged retry passed after the fixture settled.
  Initial WebView/cache warnings and host memory pressure were observed, with no
  confirmed app/System UI ANR. A single cause is not established. Both runs are
  retained in local evidence. The owned emulator was stopped after verification.

The initial background fixture stalled before any transfer because its
installer-blocking monitor also matched an explicit Activity launch with no
action. The fixture now uses ACTION_MAIN; the installer guard and test timeouts
remain unchanged. This instrumentation-only correction did not change the signed
production APK. A local results parser was corrected to recognize the explicit
background-only PASS label while retaining the original raw success log; this
reporting correction did not rerun or change the test.

The service is not a guarantee against process death, force-stop, reboot or
network/storage failures. It does not silently restart or install; interrupted
transfers can be retried. Monitoring and manifest-check background policies are
unchanged. Physical-device and GrapheneOS acceptance remain separate from fixture
testing. Keep older GridLens versions open for the download that installs 0.2.5;
the changed lifetime applies after this version is installed.

Verified APK size: 197317360 bytes. SHA-256:
`059cf4b46a238e86f19203675eeb73d5e38b2a377661a6445fadc162864dca62`.
