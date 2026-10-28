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

## Android 0.2.6 — clearer reward contrast and NAT lookup recovery, October 27, 2026

Version code 9 makes current Rewards to claim regular-weight #0f5c2b, against
unchanged regular-weight NOS #15803d. The earlier color-only adjustment kept bold
rewards text, which reduced the visual distinction. Both values are now supplied
by an isolated phone-browser fixture and compared together; reward color relative
luminance is below 55% of NOS, with equal font size/weight. A 390px screenshot was
visually reviewed. Retained rewards remain gray, and numeric precision is unchanged.

A reproducible recovery defect kept one failed public-IP lookup cached for
30 minutes. Failures now normally become eligible after 60 seconds, including old
0.2.5 records; success stays cached per PC for 30 minutes. Longer provider waits
are persisted and honored. The fixed helper explicitly tries IPv4 first and may
run one IPv6 fallback after a network failure/timeout. HTTP refusals and native
cancellation do not trigger a fallback. The phone's public IP is never substituted.

Completed verification on the final implementation:

- `npm run check` passes: 107 app/runtime/helper/phone-browser tests, 27 Python
  recorder tests, 816 inherited Node tests and all 332 inherited browser tests
  (11.6 minutes), without reruns or relaxed timeouts.
- All 131 Android unit tests pass in each debug and release variant. A clean
  build, release lint and debug/instrumentation/release APK assembly pass.
  The 17 reader/cache fixtures cover failure recovery, older cached failures,
  source times, clock regression, cancellation, concurrency and provider waits.
- Five isolated helper tests execute the fixed script, including actual Node -e
  argument handling with an injected HTTPS transport. Family, destination,
  redirects, malformed/oversized responses and HTTP refusal behavior are bounded.
  No live operator network or wallet is used by these tests.
- Phone-browser tests compare actual current Rewards and NOS, exercise all four
  IP modes and retention, and distinguish missing results from failed attempts.
  IP details fit public/private values, original times and retry/status information
  into the shared dialog's four visible facts. Full discovery inventory remains
  behind Source details.
- APK identity is com.gridlens.android, 0.2.6/code 9, with the pinned release
  certificate. Signature and 16 KiB alignment verify. All 47 runtime files and
  four helper assets match their sources. The APK contains the updated reward
  style and native IPv4/cache implementation markers.
- The adaptation patch reproduces all 196 vendored files from 192 independently
  verified originals. No desktop project, operator PC or physical phone was
  accessed. No new emulator run is claimed for this revision; phone UI evidence
  is browser-based and native reader/cache evidence uses isolated fixtures.

The particular cause of the operator's missing IP was not observed directly.
These checks establish the corrected retry and address-family behavior, not that
all remote networks will answer. Original observations stay unknown or retained
when a lookup fails, and the app now shows that distinction and retry information.

Verified APK size: 197317720 bytes. SHA-256:
`d1617feaa329e72ea78373b75147c593fa18fd39d2398f93bfdb0df232b3e19a`.

## Android 0.2.7 — retained reward colour and independent PC readings, October 27, 2026

Version code 10 removes the separate rule that forced retained Rewards to claim
amounts gray. All known amounts use #0f5c2b with regular weight; a visible clock
marks a retained reading whose current amount is unconfirmed. Source times,
exact amounts, sorting and unknown-versus-zero semantics are unchanged. Phone
screenshots show retained rewards beside current NOS and two PCs with the same
public NAT address alongside distinct private addresses.

Three concrete native failures were reproduced against unmodified 0.2.6 source:
GPU recorder Docker denial became a shared SSH credential rejection; a GPU command
timeout discarded an already completed reward read; and cancelled authentication
could create a persistent credential pause. All three old-code regression checks
fail and pass with the fixes. A fourth SSH fixture verifies that new PC/IP data is
attached only to node bindings actually verified in that connection, in both
binding orderings. Valid hosts still share the one per-PC network observation.

Older credential-paused records lack a reliable cause. The explicit Check saved
connections action verifies saved pinned endpoints and node identities without a
scan or source reads, then resumes only unchanged, previously approved targets.
Checks are serialized within each credential profile and up to four profiles run
concurrently. A rejection prevents further checks or resumption on that profile.
Source authorizationId is preserved; a separate recoveryGeneration prevents old
recovery proofs being replayed without losing late rewards denials.

Completed verification on the final implementation:

- `npm run check` passes: 109 app/runtime/phone-browser/helper tests, 27 Python
  recorder tests, 816 inherited Node tests and all 332 inherited browser tests
  (11.1 minutes). Tests cover retained-green clock markers, source timestamps,
  repeated public NAT addresses, GPU-only failure and the explicit recovery link.
- All 140 Android unit tests pass in each debug and release variant. A clean
  build, release lint and debug/instrumentation/release APK assembly pass.
- Native-only Android API 36 instrumentation passed in 12.542 seconds. Real
  Keystore tests cover recovery cancellation, unchanged-approval checks, replay,
  reapproval, revocation, aliases, deletion and forgotten credentials. Existing
  wallet denials, provider delays, attempt times and GPU cursors survive recovery;
  a late denial from the original source authorization remains effective.
- Full Android smoke passed in 14.075 seconds on an unchanged retry after the
  emulator settled. The initial run timed out at the existing five-second WebView
  JavaScript probe. Both runs and their logs are preserved; no assertion or timeout
  was relaxed. A specific cause of that initial emulator timeout is not established.
- The signed package remains com.gridlens.android with the pinned certificate.
  Signature and 16 KiB alignment verify; all 47 runtime files and four helper assets
  match reviewed sources. The APK contains the new native recovery/status markers
  and clock style, and the retained-reward gray override is absent.
- The adaptation patch reproduces all 196 vendored files from 192 verified
  originals. No operator PC or physical phone was accessed; physical acceptance
  remains separate from the isolated SSH, browser and Android fixtures.

The tests establish these corrected failure paths, not a diagnosis read from the
operator's phone. The app owns verification and recovery of its saved PC access.
Existing real credential denials, changed identities, revoked approvals and source
restrictions are not automatically cleared by installing the update.

Verified APK size: 197334532 bytes. SHA-256:
`f8c88d321c1c5fa1aba0e3ecae29eabd2f9885ac1e3e2d3a5c3a53b95fd68a9f`.

## Android 0.2.8 — reward clocks removed and progressive IP delivery, October 27, 2026

Version code 11 removes reward clock elements, their styles and clock help. Every
known current/retained reward remains #0f5c2b at weight 400, matching NOS typography.
Exact amounts, sorting and read status remain intact. Timestamps say **Last read
by GridLens**, not when Nosana changed an amount.

The native connection verifies bindings and emits network observations before
rewards/GPU work. Network, rewards and GPU cross the existing observation API
separately. Source JSON must be one nested object; malformed GPU data cannot
inject sibling reward fields or reject successful IP/reward requests. Delivery
rechecks saved authorization, binding, trust and credentials before each source.
No schema migration, additional credential or lookup-interval change is required.

Completed application, native and artifact verification:

- `npm run check` passes: 110 app/runtime/helper/browser tests, 27 Python recorder
  tests, 816 inherited Node tests and all 332 inherited browser tests (12.1 minutes).
  The expanded persistence assertion was then checked again with newly generated
  native observations, with strict typechecking.
- The actual Java public-IP reader and delivery serializer generate seven isolated
  cases: multiple hosts on one NAT PC, another PC sharing that public IP,
  public-only connectivity, IPv6, failed public lookup and failed private lookup.
  Their separate observations pass through the actual API and storage into the
  rendered column, including a full runtime restart. Malformed GPU submissions
  are rejected independently. Current and retained rewards remain green without
  clocks after reopening. Separate browser tests exercise all IP toggle modes.
- All 144 Android tests pass in both debug and release variants. An isolated SSH
  fixture stalls the first GPU command and verifies every successful host IP was
  delivered before that command or any rewards read. Existing denial, identity,
  cancellation, retry and provider-wait tests remain passing. Release lint and
  debug, instrumentation and signed release APK assembly pass.
- The package is `com.gridlens.android`, 0.2.8/code 11. APK signatures, the pinned
  certificate and continuity with the previous public APK verify. ZIP and native
  ELF alignment support 16 KiB pages. All 47 runtime files and four helper assets
  match reviewed sources; native test fixtures and private inputs are absent.
  Packaged clock code/style is absent and the delivery implementation is present.
- The complete adaptation patch reproduces all 196 vendored files and recorded
  hashes. The desktop application was not modified. No operator PC or physical
  phone was accessed; the app owns verification of its saved PC connections.

Verified signed APK size: 197334488 bytes. SHA-256:
`42bb1e746d9f0fb9aac35ccabbb9211c323c63853d22e4920be68cfb8a66161f`.

Fresh Android API 36 emulator checks also pass: native-only Keystore/monitoring
checks in 12.110 seconds and the complete embedded Node/WebView smoke in 30.770
seconds. The added native cases reject malformed GPU payloads and sibling-field
injection, and prevent delivery across reapproval, revocation and forgotten
credentials. Both app-test runs passed without retry or relaxed assertions.

Two earlier fixture launches stopped before Android boot because the emulator's
userdata allocation estimate exceeded available temporary storage. Preparing a
fresh sparse ext4 image with the SDK tool resolved that fixture issue, with the
same system image and normal Android protections. The owned AVD was stopped and
removed afterward. The final passive screenshot attempt found the emulator
offline, so no new emulator screenshot is claimed. These isolated checks do not
establish physical-phone or GrapheneOS acceptance.

## Android 0.2.9 — independent PC IP collection and six modes, October 28, 2026

Version code 12 collects public egress through installed host curl on the saved,
approved SSH connection, without a node/container dependency, sudo or packages.
One native slot is reserved for IP work and three for rewards/GPU. The IP toggle
cycles hidden, both full addresses, external full, external last octet, internal
full and internal last octet; IPv4 suffixes include the leading dot and IPv6 stays
complete. The existing preference key, source observations and provider waits are
preserved. No desktop checkout or operator PC was changed.

Completed verification:

- `npm run check` passed: 115 Android runtime/helper/browser tests, 27 Python
  recorder tests, 816 inherited Node tests and all 332 inherited browser tests
  (12.3 minutes). After the final strict-enum validation change, typechecking,
  31 relevant contract/storage tests and 11 affected storage/browser/provenance
  checks passed against the rebuilt runtime. Native-generated and checked-in
  observations both passed actual API/storage/render/reopen tests.
- Actual host curl commands ran against an isolated TLS server, covering IPv4,
  IPv6, shared NAT, redirect refusal, ignored curl startup configuration, oversized
  and invalid bodies, HTTP refusal/Retry-After, certificate rejection, missing curl
  and the ten-second request deadline. Separate actual SSH fixtures verified only
  host interface/curl commands with stopped node containers, pin and credential
  rejection, revoked permission, cancellation and retained HTTP provider waits.
- All 154 Android unit tests passed in each debug and release variant. Release
  lint and debug, instrumentation and signed release APK builds passed. Cache
  checks cover thirty-minute success reuse, one-minute failed retry eligibility,
  legacy source/timestamp parsing, provider waits, cancellation, clock regression
  and delivery retry without repeating the read or advancing its source time.
- Fresh API 36 emulator native-only instrumentation passed in 10.493 seconds;
  full embedded Node/WebView smoke passed in 27.713 seconds, both without reruns.
  The real native controller used an isolated encrypted vault, injected source
  transport and the actual observation API/storage/WebView. Four PC readings
  reached five associated hosts while all three node slots were blocked; a
  node-binding pause did not block IP delivery. Malformed GPU requests were
  rejected independently, seeded reward/GPU/binding/status records were unchanged,
  all six displayed modes passed, and a result completing after backgrounding and
  revocation was not delivered. Provider routes were intercepted before reaching
  public readers. No operator-PC or physical-device connection was made. The owned
  emulator and its synthetic data were removed after verification.
- The release remains `com.gridlens.android`, signed by the pinned certificate,
  matching the previous public APK. Signature, package/version, ZIP alignment and
  native ELF support for 16 KiB pages passed. All 47 runtime files and four helper
  assets match reviewed sources. Packaged six-mode, PC storage and fixed host-curl
  implementations were checked; the old node-exec IP helper and native test
  fixtures are absent. The update manifest exactly matches the signed APK.
- The adaptation patch reproduces all 196 vendored files from 192 verified
  originals. Credentials, signing material, operator inputs and local evidence
  are excluded from publication. Existing approval/credential-rejection controls
  remain enforced across both native lanes.

The tests establish implementation behavior in isolated fixtures. The app performs
operator-PC verification; factory-OS phone and GrapheneOS acceptance of this exact
release remain separate from emulator results.

Verified signed APK size: 197337012 bytes. SHA-256:
`45f5140292d8da1e859b9b74db57168bd8a2991e5e977d9db77979a812aadc34`.

## Android 0.2.10 — saved PC IP recovery, October 28, 2026

Version code 13 repairs remaining dependencies between PC IP collection and saved
node state. Invalid node/sudo metadata or a malformed sibling record can no longer
abort independently approved PC IP scheduling. Explicit native PC-only approval
can survive unavailable node bindings, while node reads remain paused. Native
receipts are reconciled against the actual receiver so a missing IP record can be
redelivered from cache without a new lookup or timestamp. Longer provider waits
learned from a validated receiver are preserved.

These changes address reproducible app failure paths. The reported nn01 phone
state was not available to the developer; its exact cause and recovery remain
unconfirmed until operator feedback. A successful operator curl command proves
that host-side command worked, not that the app's saved connection executed.

Final verification:

- `npm run check` passed: 115 Android runtime/helper/browser tests, 27 Python
  recorder tests, 816 inherited Node tests and 332 browser tests (12.0 minutes).
  The complete adaptation patch reproduces 196 vendored files from 192 verified
  originals. The only browser behavior change is correction of the remaining
  Column info IP summary; the six-mode control, sorting and source values remain.
- All 159 Android tests passed in both debug and release variants after the final
  source changes. Release lint and debug, instrumentation and signed release APK
  builds passed. Tests cover cached receipt restoration, unchanged read counts and
  timestamps, longer receiver provider waits and bounded explicit PC-only approval.
- A fresh API 36 emulator passed native-only checks in 19.846 seconds, the complete
  embedded runtime/WebView smoke in 127.068 seconds, and real production SSH
  monitoring in 12.805 seconds. The saved-state test restores isolated malformed
  encrypted records, verifies independent PC reads, removes/re-adds one PC's public
  association, waits the real one-minute retry allowance and confirms restoration
  of the exact original IP observation with no additional source read. Manual
  pause, genuine shared credential rejection and wallet restrictions remain
  durable through malformed sibling records; restrictions cannot grant rewards
  permission. PC-only repair preserves prior node data and source policy.
- The production SSH test uses the actual Android network capture, socket,
  authentication, controller, native cache and embedded observation API. Its
  generated loopback SSH fixture supplies three distinct node bindings with
  unsupported GPU namespaces. Exactly one PC public-IP command and seven other
  fixed commands were observed, and all three node results were delivered with
  the independent PC network record. The fixture executes no supplied command and
  contacts no public provider or operator PC. Earlier HTTPS fixtures separately
  execute actual host curl against isolated TLS responses.
- Package ID, version 0.2.10/code 13, unchanged pinned signing certificate and
  continuity with the public 0.2.9 APK verify. ZIP/ELF 16 KiB alignment and all 47
  runtime/four helper assets match reviewed sources. Packaged PC validation,
  receipt reconciliation, PC-only approval and atomic pause code are present;
  instrumentation classes and private inputs are absent.

An initial standalone instrumentation registration was overridden by Android build
configuration and failed before executing the app test. The corrected fixture uses
the existing smoke runner. An intermediate build also compiled a new retry test
against the earlier cache class; the frozen final rebuild passed both variants.
No final emulator assertions or timeouts were relaxed. The owned emulator and
synthetic data were removed. No desktop app, operator PC, existing VPN or wallet
was changed; no new privileged host operation was added.

Verified signed APK size: 197337024 bytes. SHA-256:
`ceb4adafcbfae3f794e692d8fc55d4335eb0f0c5941c2aa751f07908df8d320e`.
