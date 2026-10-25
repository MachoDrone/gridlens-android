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
