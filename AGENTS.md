# GridLens Android

This is the independent Android application. The desktop app remains in
`/home/md/gridlens-typescript`; do not edit, restart or publish it as part of mobile work.
Android-only: no iOS target. Preserve Fleet UI, data meanings, budgets, cancellation,
freshness and unknowns. The phone runs both UI and public readers locally.

The operator's immediate physical test target is a Pixel 10 Pro XL with its factory
OS. GrapheneOS support is required, with physical acceptance still unverified.
Available GrapheneOS test devices are Pixel 7, Pixel 9 Pro XL and Pixel 10 Pro XL;
a factory-OS Galaxy S22 Ultra may also be tested. Prioritize initial download,
installation and the stock Pixel test now. Defer GrapheneOS-specific changes until
the operator installs/tests it there. Preserve normal OS protections and diagnose
actual evidence before proposing compatibility-setting changes. Emulator success
does not establish GrapheneOS or physical-device compatibility.

Read README.md and docs/DEVELOPMENT.md. Never include operator rosters, wallet keys,
runtime data, signing keys or local evidence in Git or APKs. Only public addresses
explicitly imported or verified by user-initiated native discovery belong in
public fleet stores. Dated PC names/IPs and strictly validated PC-level GPU
recordings from those verified SSH discoveries may accompany saved hosts. Never
infer host-to-GPU/job mapping or claimable rewards from them. Native SSH credentials belong only in the encrypted
no-backup vault, never Node or WebView.
Do not access existing Nosana/Solana keys, change host networking or install host services.
An explicit native GPU-recording approval may create/reuse only validated labeled
GridLens Docker containers and local named volumes with the pinned image/source.
Ordinary Rescan only reads existing recorders; public Refresh/reopening does no SSH.

Keep vendored sources byte-identical except the explicit hashed adaptations in
`vendor/gridlens/ANDROID_ADAPTATIONS.md`. Document and test native/runtime and
marker-gated mobile scenario adaptations. Use the pinned Node/npm and Android
toolchains. Run `npm run check`, the vendored application checks, Android unit tests
and an APK build before release. Keep emulator and physical-device evidence separate.

The user requested publication to separate Android GitHub repositories and an update
button. Mobile source belongs in MachoDrone/gridlens-android; signed downloads belong
in MachoDrone/gridlens-android-releases. This authorization does not publish desktop
work. Review exact staged content and release artifacts for data and credentials.
Release signing material belongs only in protected local storage or GitHub Actions
secrets. Do not force-push. Keep the application ID and signing identity stable.
