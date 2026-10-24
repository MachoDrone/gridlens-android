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
