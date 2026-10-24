# Verification status

This file records Android acceptance separately from the existing application's
behavior. Physical-phone verification has not been done in this workspace;
emulator results do not establish every phone's touch behavior, battery use,
installed WebView compatibility or long-running network reliability.

Verified locally on October 24, 2026:

- Strict TypeScript checking and self-contained runtime build.
- 16 mobile/runtime/release-policy tests.
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

Remaining acceptance includes the real GitHub download/update installation flow,
and installing from GitHub on the operator's
phone, checking all Fleet actions in portrait/landscape, background/resume and
live internet data with the laptop off, then installing a later signed update
through the Update button while preserving its actual lists and preferences.
