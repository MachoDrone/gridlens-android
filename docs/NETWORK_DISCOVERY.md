# Android Network Discovery and Saved Fleets

Network Discovery is Android's initial scenario. Search network (or Fleets →
Search network) opens native Android screens. No network search or SSH connection
starts on app launch, scenario reopening, normal refresh or foreground resume.
Supplied Host Addresses and Real200 imports remain separate. Desktop code,
services, external input files and VPN configuration are unchanged.

## Workflow

1. Connect the phone to Wi-Fi, or an existing VPN over Wi-Fi/cellular. Choose
   Search network and a named network profile. Learn destination ranges from
   Android's current Wi-Fi addresses or specific VPN routes; save that profile
   for later use. A VPN's default route cannot reveal a hidden remote network.
   Learn the range once on-site or enter the destination range. Advertisements
   alone can also supply candidates when no range is known.
2. Search port 22 and any additional ports saved in the profile. IPv4 /16 through
   /32 is supported without reducing a /16 to /24. Broader routes require a
   narrower profile. IPv6 uses explicit /128 host routes or advertised services;
   it never sweeps an IPv6 subnet. The profile is independent of VPN brand, phone
   VPN address and VPN server public endpoint. Use separate profiles for different
   destination mappings or overlapping networks.
3. Select candidate computers, compare their SSH fingerprints with a trusted
   source and explicitly trust first-use identities. An open port, IP or advertised
   name is not identity proof. Changed trusted keys block credential submission.
4. Enter or choose native SSH credential profiles. Share a profile only with the
   selected trusted PCs, or use a per-PC override. Passwords and supported RSA,
   ECDSA and Ed25519 SSH keys (including supported encrypted files) stay native.
   Review the selected credentials before inspection. One authentication failure
   stops new batch work; up to four already-started sessions may finish.
5. Read-only inventory reports verified public addresses, inaccessible PCs and
   unresolved Nosana instances separately. Container access may need explicit
   per-PC sudo approval and a password. No passwordless-sudo setup is required.
   Successful public addresses automatically create and select a named fleet.
   Rename, reopen, rescan, remove selected hosts or delete it through Fleets.

Rescans merge verified discoveries and update their discovery dates. Unreachable
PCs do not remove saved hosts. A successful discovery is not a fresh metric
reading. Normal monitoring uses the existing public providers, shared caches,
request budgets, retry deadlines and source timestamps over ordinary internet;
it needs neither ongoing SSH access nor the original VPN.

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
