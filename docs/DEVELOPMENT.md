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
The updater checks a small manifest only while foregrounded, at most every six
hours, and never silently downloads or installs an APK.

## Runtime boundaries

Node starts once per Android process. Rotation and background/foreground changes
keep its providers and caches. APK replacement or Android process death restarts
the runtime; its in-memory readings/limits are not durable across process death,
matching the desktop preview's current design. Do not use restarts to bypass limits.
The app is foreground monitoring, not a background collection or notification service.

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
