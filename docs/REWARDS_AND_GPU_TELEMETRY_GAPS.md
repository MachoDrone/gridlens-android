# Rewards and GPU conditions: Android implementation

The app owns PC verification, authorization and collection. Developer access to
the operator’s PCs is not part of installation or normal operation. The original desktop
gap note identified the required sources; this document describes their Android
implementation in version 0.2.2.

## One setup, then automatic monitoring

Open a saved fleet and select **Enable / repair monitoring**. GridLens reuses the
saved PC endpoints, pinned SSH identities and encrypted credentials over the
phone's existing Wi-Fi/VPN connection. The native setup explains one combined
permission for the selected computers. No API key, browser wallet connection,
developer-side PC verification or routine rescan is required.

The app verifies running node containers and their public identities, including
nodes nested in supported Podman wrappers. Authorization binds the PC SSH identity,
runtime, full wrapper/node container IDs and public host address. The phone stores
these permissions and credential references encrypted in Android's no-backup vault;
neither the Node runtime nor WebView receives credentials or signing authority.

Monitoring runs while this saved fleet is open, with four native connection slots
and fair scheduling. GPU reads are eligible every 30 seconds and rewards reads
every five minutes; large fleets, connectivity and provider cooldowns can delay
individual readings. Backgrounding or selecting a different scenario cancels
the foreground connections. PC recorders continue independently. Ordinary Fleet
refresh reads cached/native observations and public providers without rescanning.
Missing credentials, replaced identities and changed bindings are reported by
the app. Repair is explicit; credentials are not retried in a password loop.

## Exact claimable rewards

The fixed reader executes inside the existing, explicitly approved node container.
It checks the expected public identity and process safeguards before using that
container's default node key. A bounded signer process signs only the fixed
public-address authentication message and exits before the HTTPS request. The
request is one fixed Nosana rewards GET, with TLS verification, no redirects,
bounded time/output and no claim, payment or transaction operation.

The key remains in the node container. Its authorization header is sent only to
Nosana, never to the phone or GridLens storage. The underlying Nosana signature
has broader authority than this read, which the native approval states. The
reader's limited commands do not make that signature a read-scoped token.

Only the exact `claimableUptimeNosRewards`, public address, source time, status and
bounded provider retry delay return. Amounts retain six-decimal NOS precision;
verified zero is displayed as zero. Staking/earned balances, estimates, manual
entries and imports are never substituted. The first successful app read verifies
access for that node. Transient failures can retry under the original permission;
401/403 or identity mismatch suspend the affected authorization until app repair.
Provider cooldowns and denials persist before further reads or result delivery.

Failed/expired reads retain the last actual amount with its original timestamp
and muted styling. Unknown is not zero. App development and isolated testing used
generated memory-only identities; no developer-side existing-key test is required
or performed for this Android workflow.

## GPU measurements associated with jobs

The app compares actual node-visible NVIDIA UUIDs with the node API's reported
hardware and verifies the exact running container/network scope. Unsupported or
ambiguous layouts remain unknown. It creates only labeled GridLens recorder
containers and named volumes with a pinned Python image, bounded resources,
read-only roots and dropped capabilities. There are no Docker sockets, host bind
mounts, host services, host-network containers, VPN changes or workload restarts.
The recorder shares only the verified node's existing isolated network namespace
and receives the verified GPU allocation through the existing NVIDIA runtime.
Docker/NVIDIA operations use host resources and do not have zero host effects.

The recorder rechecks node identity/GPU allocation around each fixed NVIDIA query
and records thermal slowdown, hardware thermal protection, power limiting and
power braking. Supported duration counters remain exact; unsupported signals stay
unknown. Recording runs every 15 seconds and retains up to seven days/20,000 GPU
records across the allocated devices in the owned volume. Normal node restart
can require repair of an already-running owned recorder's namespace connection;
the app verifies identity/configuration before that bounded recorder-only repair.
Intentionally stopped recorders are not silently restarted by ordinary reads.

The node's public local API does not expose a current job ID. The app therefore
uses its existing verified public job records and reported start/end intervals,
without introducing another job-API poller. Expand **Jobs** to see **GPU conditions**:
samples and supported counter increases are associated only with the matching
host, verified GPU allocation and job interval. Results say **observed during
this job**, not that the GPU condition caused a slowdown or failure. Partial
coverage, per-device gaps, clock uncertainty, unsupported counters and resets are
explicit. An open-ended running job additionally requires the existing current,
fresh matching assignment; an abandoned RUNNING history record cannot absorb
newer GPU events. Unrecorded past intervals cannot be reconstructed.

## Data and verification boundaries

The phone accepts sanitized observations only through its native-token loopback
endpoint. Browser reads are cache-only and scoped to saved fleet membership.
Rewards and GPU timelines retain independent source times; opening a page does
not renew them. Incremental GPU cursors advance only after the phone acknowledges
durable observation storage. Phone history is bounded to 6,000 samples per host,
2,048 monitored host records, 64 MiB of current observations and compacted 128 MiB
journals plus a bounded pending write. Cache eviction/truncation is explicit;
the PC's recorder remains the independent retained source.

Isolated native, process, persistence and phone-browser tests verify these paths.
They do not assert that every PC has a supported NVIDIA setup or that Nosana will
accept every node identity. The app performs those checks on the user's selected
PCs and displays each result. Existing fleets/public monitoring remain available
when a PC or provider is unavailable.

## Column presentation and PC addresses in 0.2.3

PC text uses the normal 11px regular table style. IP uses the reference four-mode
cycle: hidden bullet, full public / private addresses, bare public IPv4 last
octet, then bare private last octet. IPv6 remains complete. Modes are saved;
full private addresses still drive sorting. Missing sides stay explicit, and an
SSH destination is not silently substituted for the public egress address.

Nosana's local Node Info redacts its IP, and the checked public metrics response
has no usable public-IP field. The approved native connection therefore performs
one fixed, bounded HTTPS request to api64.ipify.org inside a verified node, plus
a read-only hostname -I on the PC. No extra host package or service is installed.
The public address is that node's observed egress for the PC, which NAT or a
PC-side VPN may affect; it is not a claim that every container uses the same
route. The private address comes from reported PC interfaces. No wallet key,
authorization header or private address is sent to ipify. The phone's connection
is not used for this measurement.

Each PC shares a persisted 30-minute lookup cache, including failures, across its
hosts. Public and private parts retain separate timestamps; a failed or older
part cannot renew or overwrite newer evidence. Saved values remain available as
retained observations. Browser toggles and ordinary cache reads start no lookup.

Rewards are rounded to the nearest whole NOS using integer arithmetic, styled in
reference green and linked to host.nosana.com. Exact six-decimal NOS remains in
accessible details and controls sorting, including amounts that round to the
same number. Retained amounts stay gray. Verified zero has a zero link; an
unknown amount stays a dash with no invented value.
