# Network discovery physical acceptance

This checklist is a test procedure, not a record of completed phone acceptance.
Record results separately in [VERIFICATION.md](VERIFICATION.md). Use the signed
candidate version under review; record its version code and APK checksum before
starting. Do not infer physical compatibility from JVM or emulator checks.

## Devices and evidence

Test the factory-OS Pixel 10 Pro XL first. Then repeat the required journeys on
the available GrapheneOS Pixel 7, Pixel 9 Pro XL and Pixel 10 Pro XL. A factory-OS
Galaxy S22 Ultra is an additional device, not a replacement for those targets.
Record OS/build and Android System WebView version. Keep normal OS protections;
report any actual permission/error before considering compatibility changes.

Use operator-authorized PCs for successful read-only inventory only. Wrong
passwords, denied sudo, changing SSH keys, corrupt storage and key invalidation
belong to disposable fixtures. Do not change a production PC's SSH host key or
trigger production account lockouts. No test requires reading wallet files,
installing host services, changing workloads or changing an existing VPN's
configuration. Select existing VPN connections using their ordinary phone UI.

Keep evidence local and redact identifiers before sharing. Record test ID,
pass/fail/not-run, device/build, candidate version, time, network category and
observable result. Never paste, screenshot, export or attach SSH passwords,
private keys, passphrases, session tokens, authorization headers or vault files.
Use labels such as PC A, PC B, Fleet A and Network A in shared results.

## Initial journey and no automatic discovery

1. Install the signed candidate on the factory-OS Pixel using Android's normal
   installer. On a fresh disposable app installation, open it with no imported
   lists. Expect **Network Discovery**, an empty Fleet, and **Search network**.
   Wait 60 seconds without pressing Search. No candidate progress, trust prompt,
   SSH login or discovery connection should occur.
2. For a measurable zero-connection check, use a disposable SSH fixture's
   connection/authentication counters. Reset only the fixture counters before
   each journey. Launch, background/resume, reopen a saved fleet and press
   ordinary Refresh separately; each must add zero discovery/SSH connections.
   The instrumentation smoke also asserts the application's discovery counters
   remain zero. A silent screen alone is not proof of zero connections.
3. Open **Hosts** and verify **Supplied Host Addresses** still imports a complete
   public-address-only test list. Switching between supplied and saved fleets
   must retain their separate membership. Do not import a wallet/key export.
4. Keep the laptop off for saved-fleet monitoring. Ordinary internet access must
   be sufficient; public-source failures may leave values unknown or retained.
   A Connected indicator means the on-phone UI can reach its local runtime,
   not that a Nosana host or upstream provider is online.

## Successful discovery and saved fleets

Use at least two reachable, authorized PCs, including one with multiple Nosana
instances. Know their expected public host addresses from an authorized public
source before the test. Do not invoke commands that initialize/read node wallets.

1. On local Wi-Fi, press **Search network**. Inspect the proposed network ranges
   and ports before saving the named network profile. Confirm they describe the
   intended destination network. The initial SSH port is 22; test one additional
   configured port on a fixture where available.
2. Search, watch completed/total endpoint progress, then select only the intended
   PCs. A reachable SSH candidate must not be called an identified Nosana host.
   Compare each first-use SSH fingerprint with a trusted administrator source
   before pressing Trust. An advertised name or IP is not identity proof.
3. Enter a shared SSH credential profile in the native dialog, then use the
   credential review screen to give one selected PC an individual override.
   Inspect only the selected, trusted PCs. Verify all expected public host
   addresses, including multiple instances on one PC, appear in the saved fleet.
4. Verify successful discovery automatically creates and selects a saved fleet.
   Rename it, create a second named fleet and switch between them. Close/reopen
   the app; the selected saved fleet must return without a Search or login.
5. Rescan Fleet A while a fixture PC is inaccessible. Previously saved addresses
   must remain; report unreachable/unresolved results separately. Restore the
   fixture, rescan, and verify new discoveries merge without duplicate addresses.
   Explicitly remove one test host, then delete only Fleet B. Fleet A, imported
   lists, credentials and network profiles must survive those separate actions.
6. Compare a reading's source timestamp before/after reopening and rescanning.
   Discovery dates must not renew cached metric timestamps or change an unknown
   amount into a measured value. Normal public refresh remains subject to shared
   limits and upstream availability.

## Network matrix

Use only pre-existing authorized destinations and VPN profiles. Mark a row
**not run** when the necessary network is unavailable; do not substitute a claim
from a different row. Stop a search with Cancel before starting another.

| ID | Action | Expected result |
| --- | --- | --- |
| N1 | Direct Wi-Fi, no applicable VPN: inspect automatic range inference and search. | The local IPv4 prefix is retained; no arbitrary private-network guessing. |
| N2 | Existing VPN over Wi-Fi with a specific destination route. | Suggested destination range follows Android's route; discovery stays on that VPN. |
| N3 | Same destination through an existing VPN over cellular. | Saved profile works without per-PC IP entry; no Wi-Fi-only requirement. |
| N4 | Full-tunnel VPN exposing only default routes and the phone's own addresses. | No remote subnet is invented from the phone's tunnel IP. Learn a profile once on-site or enter one authorized range; advertisements-only search is explicit. |
| N5 | Switch between existing VPN providers that reach the same destination network. | Reuse the destination profile; the saved fleet and public addresses do not change. Start a fresh search after the switch. |
| N6 | Existing VPN profiles with different mappings or overlapping private ranges. | Use separate named network profiles. A different SSH key at a trusted endpoint is blocked before authentication. |
| N7 | During search/SSH, disconnect VPN, disable the active network, or switch Wi-Fi/cellular. | Active work cancels, reports interruption and does not continue on another transport. Credentials are not retried automatically. |
| N8 | During a VPN search, move between two available Wi-Fi networks while the VPN remains active. | Conservative network-change cancellation still applies. Repeat separately for an underlying address/route change where available. |
| N9 | Press Cancel during candidate probing and again during SSH inventory. | Progress stops; the disposable fixture stops receiving new work. Reopening the app does not resume the cancelled batch. |
| N10 | A pre-provisioned authorized IPv4 /16 lab with fixtures near both ends. | Progress covers 65,534 usable candidates per port; a completed search can find both ends. No /24 truncation. |
| N11 | Enter a broader-than-/16 IPv4 range or an IPv6 subnet such as /64. | Validation asks for a narrower range; no sweep begins. |
| N12 | Reachable advertised IPv6 SSH service or explicit IPv6 /128 route. | Only advertised/explicit IPv6 hosts are probed; never the whole IPv6 subnet. |

A /16 can take many minutes on an unresponsive network. Seeing its total and
cancelling is a cancellation check, not proof that the full range completed.
Reserve a lab test window for N10 or mark full-range physical coverage not run.
Service advertisements depend on Android's available network-scoped discovery
API and advertisements actually crossing the chosen network; absence of an
advertisement is not proof a PC is offline. Capacity errors must be visible;
never count an aborted or truncated search as a complete network inventory.

## Credentials, trust and partial failures

Run these cases against disposable SSH/container fixtures using temporary test
credentials. The fixture must be reachable from the phone over the chosen network;
its public identities, command list and authentication counters provide evidence.

| ID | Fixture/action | Expected result |
| --- | --- | --- |
| C1 | Password login, then remembered shared credentials and a per-PC override. | Only selected, trusted PCs receive their chosen credential. Saved profiles work after app restart. |
| C2 | RSA, ECDSA and Ed25519 SSH keys, each unencrypted and in a supported passphrase-protected format. | Native import/validation and actual authentication succeed; no password fallback or key-file output. |
| C3 | Wrong SSH password or key passphrase. | Clear failure; batch stops starting additional authentication after failure. Up to four already-started sessions may finish. No password/key cycling. |
| C4 | Replace a fixture's SSH identity after trust; separately change it between inspection and inventory. | Changed identity is blocked and the fixture sees zero credential authentication for the changed connection. |
| C5 | Fixture needs sudo; test explicit approval, denial, skipped approval and a password-protected sudo account. | Only approved fixed inventory commands run. No passwordless-sudo prerequisite, interactive shell or workload change. |
| C6 | Non-root inventory finds rootless instances, while approved sudo finds additional root-owned instances. | Both verified address sets are retained; retry failures do not discard earlier successes. |
| C7 | Node container without verifiable public identity, plus another PC that succeeds. | Unresolved instances and inaccessible PCs are reported separately. Successful public identities can be saved. |
| C8 | **Forget credentials**, then reopen the saved fleet. | Passwords/keys/passphrases/overrides are unavailable; saved public fleets, network profiles and trust records remain. Monitoring needs no new SSH login. |

Inspect the fixture's allowlisted executed commands, not production environment
or logs. They must be bounded Docker/Podman inventory and supported wrapper/API
reads only. They must not read wallet files, dump environment/logs, invoke
`nosana-node address`, install services, restart workloads or change host setup.

## Storage, update and device completion

1. On a disposable test installation, run the supplied isolated instrumentation
   for Android Keystore encrypt/decrypt persistence, forgetting and key
   invalidation. It uses a separate fixture namespace. Do not delete real
   credential keys, clear operator app data or corrupt real saved-fleet files.
   Filesystem inspection must report only encryption/presence assertions, never
   decrypted records. JVM AES tests alone do not establish Keystore behavior.
2. Before an update, record fleet names/counts, selected fleet, saved network
   profile names and a display preference. Use **Update** to install the next
   tested APK signed with the existing application identity. Reopen and verify
   those records survive; verify remembered fixture credentials with an explicit
   Rescan. Monitoring immediately after update must use zero discovery/SSH work.
   If no newer signed candidate exists, mark this update-preservation case not run.
3. Repeat the initial journey, saved-fleet reopening, credentials, required
   network modes, cancellation and Update checks on each GrapheneOS device.
   Record actual prompts and failures. Do not disable exploit protection,
   weaken network restrictions or change VPN behavior in advance.
4. Keep per-device results separate. A pass on the factory Pixel, emulator or
   one GrapheneOS version does not establish the others. Missing fixtures,
   unsupported advertisement APIs and untested network conditions remain explicit
   acceptance gaps rather than inferred passes.
