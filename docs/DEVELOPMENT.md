# Android development and releases

The desktop checkout is independent. Do not modify its external host files,
preview process or publication branches when building Android. The Android
snapshot includes the current Fleet UI and public providers. Any changes to
data meanings, limits or freshness need the same isolated application checks.

## Toolchain

| Component | Version |
| --- | --- |
| Build Node / npm | 24.14.1 / 11.11.0 |
| Embedded Node | fogtape/nodejs-mobile 24.22.1-0, full build |
| JDK | 17 |
| Gradle / Android Gradle plugin | 8.11.1 / 8.9.2 |
| Compile / target SDK | 35 / 35 |
| Minimum SDK | 26 (Android 8.0) |
| Android build tools | 35.0.0 |
| NDK / CMake | 27.3.13750724 / 3.22.1 |
| APK ABIs | arm64-v8a, x86_64 |
| Native SSH / cryptography | JSch 2.28.8 / Bouncy Castle 1.86 |

Install development tools in user-owned directories. No host package installation,
root access, Docker, host services, networking or VPN changes are required.
With the Android SDK command-line tools available:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.3.13750724' 'cmake;3.22.1' 'platform-tools'
export ANDROID_HOME=/absolute/path/to/your/android-sdk
npm ci --ignore-scripts
npm run deps:vendor
(cd vendor/gridlens && npx --no-install playwright install chromium)
npm run check
node tools/prepare-native.ts
(cd android && ./gradlew testDebugUnitTest lintRelease assembleDebug assembleDebugAndroidTest)
```

The checked-in Gradle wrapper jar and distribution have pinned SHA-256 values.
`tools/prepare-native.ts` verifies the full Node archive's pinned size and SHA-256
before extraction. The NDK packages the matching C++ shared library. Generated
runtime assets, SDK files, native libraries, APKs and build output stay out of Git.

`npm run check` builds the Android runtime and runs its tests plus the complete
vendored desktop checks. Public providers are injected in tests; tests never use
operator rosters or live services. The Android instrumentation smoke test uses a
debug-only synthetic scenario and requires a clean, disposable emulator app.

```sh
adb install android/app/build/outputs/apk/debug/app-debug.apk
adb install android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.gridlens.android.test/com.gridlens.android.SmokeInstrumentation
```

The instrumented import intentionally uses synthetic addresses. Never run it
against an operator's application data. Do not clear someone else's app storage.
Discovery instrumentation uses a separate no-backup namespace and generated
keys to verify actual Android Keystore persistence, forgetting, invalidation and
RSA/ECDSA/Ed25519 import compatibility. JVM SSH tests use an in-process Apache
MINA fixture, with fixed synthetic container responses and disposable credentials.
See [Network Discovery](NETWORK_DISCOVERY.md) and [saved-fleet API](SAVED_FLEETS_API.md).

## Release signing

Keep application ID `com.gridlens.android` and the release signing identity stable.
Android updates must be signed by the installed app's certificate. Do not generate
a new key per release. Losing the key prevents compatible updates.

`version.json` is authoritative. Increase both `versionCode` and `versionName`
for each released update; names use `major.minor.patch`. Release builds accept:

- `GRIDLENS_KEYSTORE`: absolute path to the protected keystore.
- `GRIDLENS_KEYSTORE_PASSWORD`, `GRIDLENS_KEY_ALIAS`, `GRIDLENS_KEY_PASSWORD`.

```sh
(cd android && ./gradlew testReleaseUnitTest lintRelease assembleRelease)
node tools/release-manifest.ts android/app/build/outputs/apk/release/app-release.apk update.json
```

Release builds must have a real signing configuration. Verify with the pinned
SDK's `apksigner verify --verbose` and `zipalign -c -P 16 -v 4` before publishing.
The release manifest contains schema/version, minimum SDK, exact APK URL, size and
SHA-256. The app also independently verifies APK package ID, version and the
installed signing certificate. Android's installer makes the final signature and
installation decision and requires user confirmation.

## GitHub publication

Source: `MachoDrone/gridlens-android`. Downloads/workflow:
`MachoDrone/gridlens-android-releases`. Neither repository contains operator data
or signing keys in Git. The separate download repository stores only these
release secrets in GitHub Actions:

- `GRIDLENS_KEYSTORE_BASE64`
- `GRIDLENS_KEYSTORE_PASSWORD`
- `GRIDLENS_KEY_ALIAS`
- `GRIDLENS_KEY_PASSWORD`

The workflow uses its repository-scoped `GITHUB_TOKEN`; no personal GitHub token
or GitHub credential is embedded in the phone app. It checks out public Android
`main`, verifies complete existing version tags, verifies the application, builds/signs the
APK and publishes `gridlens-android.apk`, `update.json` and `SHA256SUMS.txt` as
a GitHub Release. Keys are decoded into the runner's temporary directory and
removed after signing. Source commits and test results are identified in release
notes. A successful source CI run for the exact checked-out revision can supply
the application-check evidence; absent or inconclusive evidence runs the complete
checks in the release job. Android unit tests, lint, signing and artifact checks
still run for every newly built release. The SDK is installed explicitly under
the runner's temporary directory, with the command-line archive's size/checksum
verified before use. Unpublished drafts are found through authenticated release
listings when GitHub's tag endpoint does not yet expose them; verified draft
assets are published only after every required upload succeeds.

Scheduled runs may be delayed; an immediate publish can be started with:

```sh
gh workflow run release.yml --repo MachoDrone/gridlens-android-releases
```

Before pushing, review staged source paths/content and the snapshot provenance;
exclude lists, credentials, local evidence and generated artifacts. The source
snapshot was taken from the operator's current working tree, including in-progress
Jobs/Country changes, rather than pretending those changes were already in the
upstream commit. Mobile adaptations are recorded separately. Keep both the source
and download repositories on `main`; do not force-push or modify desktop branches.

The original Network Discovery candidate remains an immutable `v0.2.0-rc.1`
prerelease. Version 0.2.1 advances the normal Update channel in response to the
operator's explicit request to install this update through that button. The
operator has confirmed eight hosts and saved-fleet reopening on the factory-OS
Pixel; the broader GrapheneOS/network acceptance matrix remains pending. Do not
claim that publication completes every physical acceptance scenario.

PC telemetry tests also run via `npm run test:collector` (Python 3 standard
library only). The recorder's Python image is pinned by digest in `GpuCollector`;
source and image are verified before reusing existing labeled resources. See
[PC telemetry](PC_TELEMETRY.md) for host-side authority, bounds and missing sources.
The updater checks a small manifest only while foregrounded and never silently
downloads or installs an APK. Version 0.2.4 uses the five-minute policy below.

## Runtime boundaries

Node starts once per Android process. Rotation and background/foreground changes
keep its providers and caches. APK replacement or Android process death restarts
the runtime; its in-memory readings/limits are not durable across process death,
matching the desktop preview's current design. Do not use restarts to bypass limits.
PC and public-data monitoring remain foreground-only. A separately approved APK
download can continue in the bounded foreground service described below.

Host imports validate a bounded complete public-address array and atomically save
only app-private files. They replace that roster in the running provider composition
without renewing observations or resetting budgets. Existing streams for the
replaced selection are cancelled. The desktop's external input files are untouched.

WebView preferences belong to its local origin. The app saves and reuses its local
port across launches/updates; if that port is occupied, it chooses a free loopback
port. Imported lists survive this fallback, but browser preferences tied to the
previous origin may need to be selected again. Both port and session state stay
private. No listener binds to Wi-Fi/LAN interfaces and no Android VPN is installed.

Connection status in Fleet still means UI-to-local-data-service reachability.
It does not prove internet connectivity or upstream host health. Online GPU Hosts
uses the existing verified Online admission and cache-only job policy. Phone and
laptop use independent public-reader processes; they do not share request budgets
or cached observations. No quota increase or hosted-capacity claim is made.

## Android 0.2.2 monitoring implementation

See [rewards/GPU implementation](REWARDS_AND_GPU_TELEMETRY_GAPS.md). The app owns
PC verification and permissions; developer-side SSH to the lab is not an
acceptance prerequisite. Test only isolated SSH/HTTP/process/Keystore fixtures.
The earlier completed single-node proof established the fixed authentication protocol;
it is not recurring permission for development. Real permission and the first
successful per-node read occur in the native phone setup.

`npm run test:collector` now covers both the legacy PC recorder and node-bound
recorder. `MonitoringVerification` adds isolated Android Keystore monitoring
persistence/revocation checks to the debug instrumentation. Public Fleet budgets
remain unchanged. Native monitoring uses four fair foreground slots, persisted
per-node authorization and shared reward cooldowns. Node/WebView cache reads
cannot trigger signing or arbitrary SSH. Receipt of an observation preserves
source times and cannot establish new reachability or causal job attribution.

## Android 0.2.3 column alignment

The reference IP cycle and whole-NOS rewards presentation are marker-gated Android
adaptations. PC/IP text uses the compact table size; exact rewards/source dates
remain independent from rounded display. Native PublicIpReader executes a fixed
HTTPS echo lookup in one verified node per PC, sharing a persisted 30-minute
PublicNetworkCache. Tests inject SSH/HTTPS and never connect to operator PCs.
Network observation timestamps are independent of inventory and financial reads.
Existing enabled monitoring receives the new values without another rescan.

## Installed version and update status in 0.2.4

The native toolbar reads the installed version from PackageInfo, falling back to
BuildConfig; it never derives the installed version from the release feed. A
separate native status line and app-information dialog preserve the latest
validated release, last successful check and last attempt. Failed/interrupted
checks remain distinct from a successful up-to-date observation. Retained newer
compatible release evidence still controls the green button.

One main-thread timer schedules five-minute manifest checks only while the
Activity is foregrounded. Pause/destroy cancel it and automatic requests; manual
checks can bypass the local interval. GitHub Retry-After deadlines are durable
and apply to both manual and automatic checks. They are retry-eligible times,
not forecasts of recovery. Rotation/cancellation cannot discard or shorten a
received deadline. APK download, size/hash/package/version/certificate checks and
Android's final installation confirmation retain their existing boundaries.
This policy is for app updates; PC and Nosana polling intervals are unchanged.

## Screen-independent update downloads in 0.2.5

After native Download confirmation, a private Android `dataSync` foreground
service owns the transfer. The Activity only observes persisted progress; hiding,
pausing or destroying it cannot cancel the service. The native status line,
progress dialog and download notification show downloaded MB / total MB and
percentage. Notification permission is requested optionally after a download
starts and denial does not cancel it. Notification taps open GridLens without
launching Android's installer. Installation still requires a foreground user
action and a fresh package/signature verification.

The service preserves the fixed GitHub allowlist at every redirect, size limits,
streaming SHA-256 check, package/version/signing-certificate checks and durable
provider retry deadlines. Each explicit job has its own partial file and UUID;
file promotion and READY publication share a current-job lock, and a stale worker
cannot overwrite a replacement job. Release metadata and progress are atomic,
app-private and excluded from backup. No APK or fixture identity is added to the
embedded runtime or WebView bridge.

The transfer is bounded to ten minutes, with a twelve-minute service/wake-lock
ceiling covering verification and cleanup. Explicit cancellation stops the
transfer. There is no boot receiver, automatic APK download, automatic install,
silent restart or range-resume promise. Process death, force-stop and network or
storage failures remain possible and surface as an interrupted/failed transfer
that can be retried. Background manifest checks and PC collection remain paused.

`UpdateDownloadEngineTest` exercises bounded transfer and rejection paths with
injected transport. Debug-only instrumentation can supply a slow, valid synthetic
APK to the actual service while retaining its integrity and archive checks. It
uses a disposable emulator and never installs the fixture APK. Production builds
cannot enable that transport. See the dated verification entry for observed
lifecycle results rather than assuming physical-device compatibility.

## Reward contrast and NAT public-IP recovery in 0.2.6

Current rewards use regular 400 weight, matching NOS, and #0f5c2b against NOS
#15803d. The phone-browser fixture supplies both current values and compares their
computed brightness and typography side by side. Retained rewards remained gray
in that version; 0.2.7 replaces that override with a separate clock marker.

The fixed PC-side public lookup prefers IPv4 and retains a bounded IPv6 fallback.
It uses the already verified node and existing monitoring approval. Successful
public readings keep their 30-minute per-PC cache; failures become eligible after
60 seconds, including a failed record written by 0.2.5, unless the provider requested
a longer wait. Viewing, retries and private
address success cannot renew an older public-IP timestamp. Collection still stops
when its native connection is cancelled and never substitutes the phone's IP.

The shared information dialog displays four facts. IP details place public/private
readings with their original times, actual failed-attempt information and reported
monitoring status in those four visible slots. Full saved discovery inventory stays
in Source details. Absence of a result does not imply a failed check or missing
authorization, and retry eligibility is not a promise of network recovery.

## Independent PC readings and paused-connection recovery in 0.2.7

`gpu-access-required` identifies Docker recorder permission separately from SSH
authentication. It does not suspend a credential profile, and successful rewards
and IP readings remain deliverable when GPU collection fails or times out. Native
cancellation takes precedence over an SSH library's ambiguous authentication-cancel
message. Genuine credential denials retain their existing shared-profile pause.

Older stored `credentials-required` pauses do not record which subsystem failed,
so they cannot safely be unpaused merely by installing an update. The explicit
native Check saved connections action uses saved pinned endpoints and credentials,
verifies saved identities and resumes only still-approved matching targets. It
preserves manual pauses/revocations, changed trust, replaced aliases, reward
suspensions and provider deadlines. The WebView supplies only the saved fleet UUID;
the native controller owns eligibility, credentials, verification and cancellation.

Known reward amounts stay dark green even when retained. A visible clock marker,
existing Retained description and original timestamp communicate uncertainty;
recoloring never renews an observation. Tests include retained/fresh presentation,
GPU-only access failures and two PCs with the same public NAT address but distinct
private addresses and independent source times.

## Progressive IP delivery and reward presentation in 0.2.8

Verification and IP collection precede optional rewards/GPU work. The native
connection emits the per-PC network result as each exact host binding is verified;
slow GPU setup or reads cannot hold those successful observations until the end
of the connection. Public egress is requested even when no private interface is
available, with the unchanged IPv4 preference and IPv6 fallback. Multiple hosts
on a PC and independently observed PCs behind shared NAT can use the same public
address. Public/private lookup failures retain their independent source times.

Native delivery sends network, rewards and GPU in separate requests through the
existing `/mobile/observations` API and schema. Rejection of one malformed source
does not reject the other sources. GPU cursors advance only after their own
delivery succeeds. Login denials, identity changes, revoked approvals, wallet
suspensions, provider waits and foreground cancellation remain authoritative.
No credentials, migration, lookup interval or provider budget is added.

Rewards have no clock icon or clock help. Every known amount, current or retained,
uses regular-weight #0f5c2b; unknown is still unknown. Exact amounts, sorting and
read status remain unchanged. Descriptions label timestamps **Last read by
GridLens**, which does not establish when Nosana updated an amount. IP retains
hidden → full public/private → public suffix → private suffix; a missing side is
− and IPv6 stays complete.

`test/fixtures/native-observations.json` is generated by production Java readers
and delivery serialization and checked by the native tests. The browser integration
posts these separate observations through the actual API, rejects malformed GPU
data, checks the displayed column and reopens the persisted runtime. After native
tests, exercise the newly generated bytes directly with:

```sh
GRIDLENS_NATIVE_OBSERVATIONS_FILE=android/app/build/outputs/native-observations.json \
  node --test test/native-observations-browser.test.ts
```

## Independent PC IP collection in 0.2.9

Public IP collection no longer requires a node binding or a running Nosana
container. The native controller uses the saved, approved SSH connection and
pinned PC identity, then executes installed host `curl` with startup configuration
disabled, IPv4 preference, TLS verification, no redirects, a bounded body and a
ten-second request limit. Only IPv4 connectivity failure allows one IPv6 attempt
against `https://api6.ipify.org`; the primary endpoint is `https://api.ipify.org`.
An HTTP refusal or provider-requested wait stops fallback. Missing curl is reported;
no package installation, sudo or container operation is part of IP collection.
Private interface reads remain independent.

One native slot is reserved for independently scheduled PC IP collection and
three remain for rewards/GPU. Per-PC in-flight tracking avoids duplicate reads.
Node-binding failures cannot disable the IP lane. Credential rejection, identity
change, missing/revoked permission, manual pause and foreground cancellation apply
to both lanes. The thirty-minute successful public cache, one-minute failure retry
eligibility and longer provider waits survive the update. Delivery failures retry
the cached observation without remeasuring or advancing its timestamp.

Network-only schema-1 updates use `{pcId,network}` at the existing observation
endpoint. Association comes from saved fleet PC metadata. Separate fleet/PC
journals preserve node bindings, rewards, GPU history and node-monitoring status.
The browser applies PC observations to every currently associated host; duplicate
public NAT addresses are valid. Legacy node-egress observations remain readable
with their original source labels and timestamps. Bounded failure categories
explain curl absence, DNS, connection, timeout, TLS, HTTP refusal, invalid response
and interface-read failure without exposing raw output or credentials.

Android's saved IP preference now supports six modes in this order: `dot`, `full`,
`public-full`, `ext`, `private-full`, `int`, then `dot`. Existing keys and mode
meanings remain compatible; IPv4 suffixes include a leading dot, IPv6 stays full,
and combined mode always preserves both positions with − for missing values.
Missing/invalid preferences default to hidden. Live and synthetic views, header
help and accessible labels share the cycle. Sorting retains full values and taps
initiate no network requests. These adaptations do not edit the desktop checkout.

## Saved PC IP recovery in 0.2.10

PC identity/credential/approval validation is now separate from validation of node
bindings and optional sudo state. A malformed target is isolated to its own work;
it cannot abort scheduling for other PCs or fleets. Node work keeps its strict
binding validation. PC IP work continues only with valid saved PC authority and
current fleet association; no malformed record or denial is silently removed.

The reserved IP worker reconciles acknowledged native observations against the
actual cache-only observation API on resume and at a bounded one-minute interval.
A missing or older received record makes the same native observation eligible for
delivery again, without another curl read or timestamp change. Existing local
delivery retry and provider deadlines remain intact. Invalid/unavailable receiver
responses do not discard delivery receipts.

Explicit native monitoring approval can register PC-only access after an
authenticated discovery and matching saved fleet PC association even when exact
node bindings are unavailable. It keeps node reads paused and grants no additional
reward or GPU authority. Existing node records, source restrictions and cached
measurements are preserved.

`SmokeInstrumentation -e productionSsh true` exercises real Android network capture,
SSH authentication and the production controller against a generated local SSH
fixture, including three nodes with unsupported GPU namespaces. The fixture never
executes supplied shell commands or contacts public providers. Saved-state fixtures
cover invalid node records and cache receipt recovery independently of real PCs.

## Direct PC-only IP checks and pinned SSH key preference in 0.2.11

The Android IP popup includes **Check / repair this PC’s IP**. Its fixed native
navigation contains only a saved fleet UUID and PC identity digest. MainActivity
requires a same-origin top-level user gesture and exact validated parameters; the
native controller resolves current fleet association, trusted saved endpoints and
encrypted credentials. No command, credential or arbitrary endpoint is supplied
by the WebView. Node status remains in dated Source details rather than being
presented as the IP collector’s current result.

The native flow uses the existing reserved IP worker after pausing automatic
collection. It requires native confirmation and actual pinned SSH verification
when PC-IP approval is missing or restricted. Cached readings cannot substitute
for connection verification: an authentication-only connection is used when a
cache/provider deadline prevents curl. Successful PC-only approval leaves node
work paused, preserves wallet/GPU policy and changes no container resources. An
already healthy target remains unchanged; choosing another trusted endpoint or
credential for it is an explicitly confirmed one-time check. Genuine identity or
credential failures remain rejected, and local permission-storage failure stops
further collection. Backgrounding, cancellation and scope changes cancel the flow.

A successful result is posted through the existing network-only observation API
and read back before native delivery is reported. The saved fleet reloads after
completion, sharing the PC reading with its associated hosts. Native dialogs keep
cached readings, original attempt/source times, provider waits and local delivery
failures distinct. Repeated actions honor existing success, failure and provider
deadlines; they do not reset source timestamps.

SSH negotiation now tries the already pinned key type first while keeping the
existing supported alternatives. The old preference could select ECDSA instead
of a still-offered saved RSA key and falsely report an identity change. RSA pins
use RSA-SHA2 algorithms; no legacy RSA-SHA1 algorithm was added. The exact saved
key is still required before authentication, so actual replacement/removal remains
rejected. The same selection applies to ordinary monitoring and direct repair.

The existing smoke runner’s `productionSsh=true,directIp=true` mode exercises the
actual popup action/native confirmation and PC-only repair over generated local
SSH fixtures. It requires an empty disposable emulator; it is not a physical-PC
or production-data diagnostic command.

## Automatic PC IP enrollment in normal discovery (0.2.12)

The ordinary native review now offers **Inspect + automatic IP**, with explicit
PC-only foreground collection scope and the existing **Inspect only** alternative.
Full rewards/GPU monitoring remains a separate approval. Automatic IP enrollment
requires successful authenticated PC metadata, the current saved fleet association
and a byte-matching remembered credential used by that inspection. It never saves
a deliberately ephemeral credential and never grants node, wallet, GPU or sudo
authority. Successful enrollment uses the existing one-slot PC IP scheduler/cache.

New PC targets are eligible; strictly untouched, never-approved legacy inspection
records can be converted after this explicit review action. Existing healthy
monitoring and protected or ambiguous records remain unchanged. The checks reject
manual/revoked access, source restrictions, prior activity/recovery state, malformed
fields, duplicate PC records and identity/credential changes. Shared credential
rejection elsewhere in the same inspection batch prevents automatic enrollment
with that credential. Foreground cancellation and an atomic snapshot comparison
protect the approval commit. No public-IP value is inferred or embedded.

The `productionSsh=true,autoIp=true` instrumentation mode drives normal discovery,
SSH trust, credentials, review and saving, then checks automatic IP delivery to
three arbitrary-name hosts without invoking manual repair. A second run leaves
Remember unchecked and verifies that no credential or ongoing IP permission is
persisted. It is restricted to a disposable emulator and generated local SSH
fixture; provider routes are intercepted before synthetic addresses reach readers.

## Discovery usability (0.2.13)

Physical-device acceptance remains separate from the recorded automated checks.
`DiscoveryUi` supplies native green titles, outlined dialog actions,
blue underlined action rows, separate wrapping descriptions and short labeled
inputs. A virtualized selection list supports search, filters, bulk selection,
stable checked IDs and an explicit maximum. In the current layout, search is a
direct child of the custom dialog view; explanation, filters and bulk controls
have a bounded scrolling region above the independently scrolling results.
Selected/result counts and confirmation remain outside the result list. Selection
footers reserve the native button bar's full measured height, including stacked
buttons at larger text sizes, before allocating result space. The
keyboard and layout correction is described under 0.2.14 below. Action rows expose
one combined accessibility description; automated text lookup must consider that
description as well as a node's text.

Normal discovery skips empty profile/credential chooser screens. It still
requires computer selection before inspection, and reads SSH identities with
bounded concurrency before a consolidated trust review. Matching saved keys are
selected; new keys require selection and explicit trust; changed/unavailable keys
cannot proceed. The SSH form starts with an existing-key method and provides a
separate password method. Changing methods clears the previous secret fields;
remembering, sharing and per-PC overrides retain their existing authority.

Inspection now leads to a second, initially empty selection of verified Nosana
hosts before fleet persistence. Selected public addresses and metadata form the
save request, while complete inspection results retain authentication failures,
unresolved observations and shared-credential restrictions. Monitoring
registration receives the explicit host selection. New grants must fit those
verified host/PC associations; partial selections that cannot safely update an
existing PC-wide record are deferred without rewriting its prior permissions.
The existing explicit saved-PC repair path remains separate.

The completion dialog separates host/PC counts, approval/setup status and issue
counts from expandable inspection details. **View fleet** replaces its generic
OK action. Rejection, unavailable readings, preserved approvals and unfinished
monitoring setup must retain their actual meanings in both views.

Candidate acceptance must exercise the normal native journey, existing SSH-key
and password modes, remembered/ephemeral credentials, shared and per-PC sign-in,
large result sets, search/filter selection, blocked identities, selected-host
monitoring scope, partial rescans, cancellation and narrow/large-font layouts.
Use the existing isolated instrumentation and generated SSH fixtures; record
actual results separately in `VERIFICATION.md`.

Genuine SSH rejection is persisted as inspection returns, before the host picker
can be cancelled. Native-only snapshots bind that result to the exact remembered
credential used by inspection even after cancellation clears the SSH buffers. A
changed credential does not inherit an old rejection. Storage failure blocks
automatic collection; choosing fewer hosts cannot discard a denial.

## Search input and result visibility (0.2.14)

The 0.2.13 selection screen placed the search editor inside a `ListView` header.
The 0.2.14 correction attaches it directly to the custom view before creating the
dialog, so Android can recognize the text editor during dialog setup. The window
also clears `FLAG_ALT_FOCUSABLE_IM` and uses resize behavior when the keyboard
opens. Verify actual tapping and ordinary keyboard input; assigning text directly
to the view does not exercise this failure boundary.

Selection dialogs use the available window height. A separate virtualized result
list receives the remaining space below search and the bounded options region.
The result list keeps its scrollbar visible and shows **Scroll for more results**
or **Scroll up for earlier results** when appropriate. The count is the total
number of rows matching the search/filter, including rows outside the viewport.
**Select all results** selects all selectable matches; it does not change the
selection limit or clear selected rows hidden by a later filter.

Candidate rows now describe saved SSH identity state in the selected network
profile, whose name appears in the explanation. The lookup remains the exact
profile UUID plus IP and SSH port. **No saved SSH identity** is not a Nosana
classification or evidence that fleet data was deleted. Creating a new profile
does not inherit another profile's trust, even when their names or ranges match.
Saved keys are still verified before login and changed keys remain blocked; this
wording change adds no cross-profile trust or credential access.

Record candidate test results, publication and physical-phone confirmation
separately in `VERIFICATION.md`. Acceptance should cover tap-to-type search,
keyboard dismissal, scrolling through all matching rows, selections surviving
filter changes, narrow/large-font layouts and the profile-specific identity labels.
