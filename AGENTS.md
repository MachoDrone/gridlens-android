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
infer host-to-GPU/job mapping or claimable rewards from PC-only inventory.
Android monitoring verifies exact node bindings and GPU UUIDs; job association
uses verified public job intervals, with explicit gaps and no causation claim. Native SSH credentials belong only in the encrypted
no-backup vault, never Node or WebView.
Development must not access existing Nosana/Solana keys or connect to operator
PCs for manual verification of this flow. The app verifies and authorizes PCs.
After explicit native monitoring approval, its fixed bounded reader may use the
exact node's existing identity only inside that node container for the fixed
Nosana rewards GET. Keys/auth headers never reach the phone; no claim/payment or
transaction path is allowed. Denials, provider cooldowns and credential revocation
must persist before further reads or result delivery. The native vault owns
credentials and exact permissions; Node/WebView receive public observations only.
Do not change host networking or install host services. Approved monitoring can
create validated labeled Docker recorders/volumes with pinned image/source and
repair namespace drift only on an already-running owned recorder after exact
identity/configuration proof. Never restart the Nosana workload. Ordinary scans
remain user initiated. Foreground approved saved-fleet monitoring reuses pinned
SSH endpoints without rescans; background/scenario changes cancel connections.
Keep scheduling fair at 200+ PCs and pause a rejected shared SSH credential
profile across its targets. Unknown/stale source data is never refreshed by viewing.

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

Android PC text matches the normal table style: 11px, regular weight. IP cycles
hidden, full public/private, public suffix and private suffix without leading
dots; IPv6 stays complete. Rewards use rounded whole-NOS links in #166534, darker than NOS #15803d.
Exact reward amounts and source times remain unchanged for sorting and details;
unknown is not zero. Public IP comes from the fixed native ipify read inside one
verified node per PC, shared and cached for 30 minutes. Private addresses come
from PC interfaces. Never substitute the phone's IP or SSH ingress for measured
public egress; preserve independent retained timestamps on failure.

Keep the installed APK version visible in the native toolbar and update outcomes.
Update color is not the only status: show last successful check/result separately
from failed, interrupted or unknown checks. App-update checks use one foreground
five-minute timer; pause/destroy stop automatic work. Persist and honor provider
retry deadlines across controllers/restarts, including manual checks. A successful
response must not erase a later provider deadline. Downloads/installation remain
explicit user actions, and the existing signature/integrity checks must remain.
An approved APK transfer belongs to the bounded dataSync foreground service;
Activity pause/destruction only detach its UI. Preserve downloaded/total MB and
percentage, explicit cancellation, per-job file ownership and verification before
readiness. Notifications open the app without installing. Notification permission
denial cannot block the transfer. No background PC collection, boot restart or
automatic installation is implied by this service.
