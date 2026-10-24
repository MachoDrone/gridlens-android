# GridLens Android

This is the independent Android application. The desktop app remains in
`/home/md/gridlens-typescript`; do not edit, restart or publish it as part of mobile work.
Android-only: no iOS target. Preserve Fleet UI, data meanings, budgets, cancellation,
freshness and unknowns. The phone runs both UI and public readers locally.

Read README.md and docs/DEVELOPMENT.md. Never include operator rosters, wallet keys,
runtime data, signing keys or local evidence in Git or APKs. Only public addresses
explicitly imported by the operator belong in the phone's private data directory.
Do not access existing Nosana/Solana keys, change host networking or install host services.

Keep vendored browser sources byte-identical to the recorded source snapshot.
Document and test native/runtime adaptations. Use the pinned Node/npm and Android
toolchains. Run `npm run check`, the vendored application checks, Android unit tests
and an APK build before release. Keep emulator and physical-device evidence separate.

The user requested publication to separate Android GitHub repositories and an update
button. Mobile source belongs in MachoDrone/gridlens-android; signed downloads belong
in MachoDrone/gridlens-android-releases. This authorization does not publish desktop
work. Review exact staged content and release artifacts for data and credentials.
Release signing material belongs only in protected local storage or GitHub Actions
secrets. Do not force-push. Keep the application ID and signing identity stable.
