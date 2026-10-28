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
hidden → full public/private → full public → public last octet → full private →
private last octet → hidden. IPv4 last octets include a leading dot; IPv6 stays
complete. Missing values use − and combined mode always preserves both positions.
Keep the existing preference key and dot/full/ext/int meanings, adding
public-full/private-full. Invalid or absent preferences default to hidden. Sorting
uses full addresses; display changes start no requests.
Rewards use rounded whole-NOS links in #0f5c2b with regular text weight, including
retained amounts, without clock icons. Preserve exact amounts, read status and
source times; timestamps mean last read by GridLens, not when Nosana updated them.
Unknown amounts remain unknown.

Public IP comes directly from installed host curl on each approved PC using its
saved pinned SSH identity, without sudo or container commands. Curl disables its
startup configuration, preserves TLS verification, rejects redirects and bounds
responses. Each request has a ten-second limit. Prefer IPv4 at api.ipify.org;
only connectivity failures may fall back to IPv6 at api6.ipify.org. HTTP refusals
and provider-requested waits never trigger fallback. Install no packages. Private
addresses come independently from PC interfaces. Never substitute the phone's IP
or SSH ingress for measured public egress. Shared NAT is valid across PCs.

Reserve one of the existing four native connection slots for independently
scheduled PC IP reads; rewards/GPU retain three. Node-binding failures cannot
block IP work. Genuine SSH rejection, changed trust, missing/revoked approval and
manual pauses remain authoritative across both lanes. Public successes stay cached
for thirty minutes; failures become eligible after one minute, preserving longer
provider waits. Background/scenario changes cancel collection. Retry failed local
delivery from the cached observation without repeating curl or advancing time.

Schema-1 /mobile/observations accepts a network-only {pcId,network} update, validated
against saved fleet PC metadata. Store these readings separately by fleet and PC;
share them with all currently associated hosts without changing node bindings,
rewards, GPU history or node-monitoring status. Retain old node-egress observations,
source labels, times and deadlines. Public/private failures are bounded categories;
never expose command output, credentials or private keys.

GPU recorder access failures must not be treated as SSH credential rejection or
discard successful IP/reward observations. Cancelled authentication is not proof
of rejection. Old ambiguous credential pauses require an explicit native saved
connection check before resuming approval; preserve real denials, revoked
permissions, trust pins, wallet suspensions and provider deadlines.

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
