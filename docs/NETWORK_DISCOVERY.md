# Android Network Discovery and Saved Fleets

Network Discovery is Android's initial scenario. Search network (or Fleets →
Search network) opens native Android screens. Network searches remain explicitly initiated. Approved PC IP collection and
separately approved rewards/GPU monitoring reuse saved SSH endpoints while the
saved fleet is open; they do not repeat discovery.
Supplied Host Addresses and Real200 imports remain separate. Desktop code,
services, external input files and VPN configuration are unchanged.

## Workflow

1. Connect the phone to Wi-Fi, or an existing VPN over Wi-Fi/cellular. Choose
   **Search network**. With no saved profiles, the network editor opens directly;
   otherwise choose a named profile or add another. Learn destination ranges from
   Android's current Wi-Fi addresses or specific VPN routes; save that profile
   for later use. A VPN's default route cannot reveal a hidden remote network.
   Learn the range once on-site or enter the destination range. Advertisements
   alone can also supply candidates when no range is known. Suggested Wi-Fi
   settings are usually suitable; VPN suggestions still need checking against
   the destination computers. **Advanced settings** contains range-format help
   and additional SSH ports.
2. Search port 22 and any additional ports saved in the profile. IPv4 /16 through
   /32 is supported without reducing a /16 to /24. Broader routes require a
   narrower profile. IPv6 uses explicit /128 host routes or advertised services;
   it never sweeps an IPv6 subnet. The profile is independent of VPN brand, phone
   VPN address and VPN server public endpoint. Use separate profiles for different
   destination mappings or overlapping networks.
3. Choose the computers to inspect once. Tap **Search name or address** to type,
   then filter by **Saved SSH identities** or **No saved SSH identity** if useful.
   Results have their own scrolling area. The count includes all matches for the
   current search and filter, not just rows visible on screen; **Scroll for more
   results** appears when more rows are below. **Select all results** selects every
   selectable match, including offscreen rows. Selections remain checked when
   filters change; **Clear selection** removes the whole selection. Continue
   requires 1–512 selected computers. No result is selected automatically at this
   step. SSH reachability alone cannot determine which computers run Nosana, so
   choosing computers still precedes sign-in and inventory.
4. Review the chosen computers' SSH identities together. The initial computer
   choices carry forward, with no second checkbox list. Matching saved identities
   can continue; new identities require explicit approval after comparison with a
   trusted source. Fingerprints remain visible. Changed or unavailable identities
   are excluded and receive no credentials. Cancel if a new identity cannot be
   verified. An open port, IP or advertised name is not identity proof.
5. Enter or choose native SSH credential profiles. If none are saved, the sign-in
   form opens directly. **SSH key** is offered first for an existing key already
   authorized on the computers; **Password** shows a separate password field.
   Both methods require the computer's username. A key passphrase is needed only
   for a protected key. GridLens does not create or install SSH keys, and Nosana or
   Solana wallet files must never be imported. Share a profile only with the
   selected trusted PCs, or use a per-PC override. Passwords and supported RSA,
   ECDSA and Ed25519 SSH keys (including supported encrypted files) stay native.
   Review the selected credentials before inspection. One authentication failure
   stops new batch work; up to four already-started sessions may finish.
6. Read-only inventory reports verified public addresses, inaccessible PCs and
   unresolved Nosana instances separately. Container access may need explicit
   per-PC sudo approval and a password. No passwordless-sudo setup is required.
   The normal review offers **Inspect + automatic IP**, explicitly approving
   foreground PC-only IP refresh with the already remembered credentials. It
   grants no wallet/GPU/sudo operations. **Inspect only** preserves one-time
   inspection. Credentials left unremembered remain ephemeral and are excluded
   from automatic IP collection. Existing protected pauses and denials remain;
   the save result reports exclusions. The prominent **→ Enable full monitoring…**
   action separately explains and approves rewards, GPU recorders and IP checks.
7. After inspection, GridLens automatically creates or merges the fleet with all
   verified Nosana host addresses found on the chosen computers. Multiple verified
   hosts on one PC are all included; there is no additional host-selection page.
   Computers without verified hosts and unresolved observations remain in the
   inspection details. No empty fleet is created when no host identities were
   verified. Rescans keep existing saved hosts, including those on computers not
   chosen or reached during this inspection. New monitoring is limited to the
   verified hosts saved from this inspection and their verified PC associations.
   If incomplete evidence cannot safely update an existing PC monitoring record,
   its settings remain unchanged and setup is deferred to
   **Enable / repair monitoring**.
8. **Fleet saved** reports verified host/PC counts and the actual approval/setup
   outcome. Incomplete checks and automatic-IP exclusions remain visible as
   counts, with per-PC reasons under **View inspection details**. Monitoring setup
   in progress is not reported as successful collection. **View fleet** closes
   the summary. Rename, reopen, rescan, remove hosts or delete the fleet through
   **Fleets**.

Rescans merge verified discoveries and update their discovery dates. Unreachable
PCs do not remove saved hosts. A successful discovery is not a fresh metric
reading. Public Nosana/Solana readings use the existing providers, shared caches, request
budgets, retry deadlines and source timestamps over ordinary internet. Automatic
PC IP checks separately need the saved SSH endpoint reachable over Wi-Fi or VPN;
failed or unavailable connections retain dated values without renewing them.

The computer picker names the selected network profile. **SSH identity not saved
in this profile** means this profile has no approved SSH key for that IP and port.
The same PC can already belong to a saved fleet or have an approved identity in
another profile. Creating another profile, even with the same name or range,
creates a separate approval context; editing an existing profile keeps its
identity. This label does not classify Nosana hosts or establish lost data.
Approval is never copied automatically between profiles, since different networks
can reuse the same IP addresses. The subsequent identity review still verifies
saved keys and requires explicit approval for new identities.

## Network and SSH boundaries

A discovery session captures one Android Network and uses its socket factory for
all candidate probes and SSH sessions. Disconnection or changed network/link
properties cancels work and closes its sockets. There is no fallback to another
transport and no VPN or host-network configuration. Service advertisements on
Android 33 with SDK extension 3 or newer supply names/addresses only; SSH trust
is independent. Older supported Android versions use ranges and explicit routes.

There are 32 concurrent candidate probes and four SSH sessions. Profiles have at
most 32 ranges, 262,144 distinct IPv4 candidates and eight SSH ports (including
22). Searches stop with an explicit narrower-profile error beyond 2,048 SSH
candidates or 512 advertised services; they do not silently truncate results.
Inventory accepts at most 512 running containers per response and 32 nested
wrappers. Responses and operation deadlines are bounded; cancellation closes
owned sockets. No arbitrary shell, command or path comes from browser requests.

Inventory reads only fixed container ID/image/name fields for running Docker and
Podman containers. Known `nosana/podman` wrappers support nested Podman inventory.
Validated `nosana/frpc` names `frpc-api-<public-address>` accompanying running
`nosana/nosana-node` containers supply public identities, with base58/32-byte
validation. When those identities are incomplete, a fixed process inside a
validated running node container uses Node's built-in HTTP client to read its
already-running API at `127.0.0.1:5001/`. It starts with an empty environment,
imports no Nosana initialization code, emits only the validated public address
and has a three-second timeout and 256-character response limit. Custom API ports, unavailable
executables, unknown layouts or missing identity evidence remain unresolved.
This records PC-level discovery; it does not claim a physical GPU mapping.

The fixed layout and API interpretation were checked against Nosana's
[proxy naming](https://github.com/nosana-ci/nosana-node/blob/3f07617a7c64820202cf55adbc69625bc4390d72/src/NodeManager/provider/Provider.ts),
[API root response](https://github.com/nosana-ci/nosana-node/blob/3f07617a7c64820202cf55adbc69625bc4390d72/src/NodeManager/node/api/ApiHandler.ts),
and [default API port](https://github.com/nosana-ci/nosana-node/blob/3f07617a7c64820202cf55adbc69625bc4390d72/src/cli/commands/sharedOptions/--port.ts)
at the recorded upstream revision. Different layouts may need a separately
verified adapter; an inaccessible or unresolved instance is not Offline proof.

Commands never read wallets, dump environments/logs, invoke `nosana-node address`,
install services, restart workloads or modify host configuration. Sudo approval
is limited to the fixed inventory operations. Security-key authentication, SSH
agent forwarding and arbitrary interactive MFA are outside this version.

## Storage and recovery

Public fleets use a separate app-private atomic store with an opaque UUID,
user name, public addresses and first/last discovery timestamps. A validated
previous revision supports recovery. Invalid unrecoverable files fail closed
and remain in place. Imported lists are neither overwritten nor merged into
this store. See [the mobile API](SAVED_FLEETS_API.md) for bounds and authorization.

Network profiles and SSH host-key pins remain native. Remembered credentials
are encrypted with AES-256-GCM using an Android Keystore key and fresh random
nonces, in the app's no-backup directory. Neither plaintext credentials nor SSH
keys reach Node, the WebView, logs or backups. Forget credentials destroys the
key and encrypted records while retaining saved fleets, profiles and trust.
An unavailable/invalidated key requires explicit forgetting and reentry; it is
not silently replaced. App updates preserve private data; uninstall removes it.

## Acceptance

Automated checks use synthetic public addresses, in-memory SSH keys/passwords and
isolated container responses, never operator credentials. They are separate from
physical acceptance. The factory-OS Pixel 10 Pro XL is first, followed by the
available GrapheneOS Pixels; protections must not be weakened speculatively.

Physical acceptance must cover Wi-Fi, VPN over Wi-Fi and cellular, switching VPNs,
network loss/cancellation, full-tunnel ambiguity, overlapping networks, large
ranges, changed SSH identities and real supported container layouts. Check saved
fleet reopening on ordinary internet after SSH/VPN is disconnected, and update
preservation. Release status and actual automated results are recorded in
[VERIFICATION.md](VERIFICATION.md); source implementation alone does not establish
physical compatibility or a published update.
