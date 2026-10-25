# PC inventory and GPU recordings on Android

Android 0.2.1 connects the existing PC and IP Fleet columns to native SSH discovery.
Use **Rescan** once on older saved fleets: their old records contain only public
Nosana addresses. Existing fleet membership, selection and credentials survive
the update. Public refresh and reopening never perform SSH or install a recorder.

**PC** shows the hostname returned by the selected computer. A digest of its
verified SSH public identity associates the discovery with that PC. **IP** keeps
the reference display cycle: hidden dot, full global/internal, global suffix,
internal suffix. IPv6 remains complete; full addresses drive sorting. PC/IP
details show all discovered addresses and the original discovery time. The list
contains the authenticated SSH destination and reported interface addresses;
a destination may be a NAT/VPN mapping. No phone IP or public-IP service is used.
Conflicting PC ownership clears the association instead of choosing one computer.
Unknown names/IPs remain unknown. Discovery time is not live reachability proof.

## Enable the PC recorder

During Rescan, select and trust the intended PCs and choose SSH credentials as
usual. On the review screen choose **GPU recording…**, review the Docker scope,
then **Enable and inspect**. The ordinary **Inspect selected PCs** action only
reads an already enabled recorder. PCs must already have Docker and a working
NVIDIA container runtime. Unsupported/denied PCs are reported separately; the app
does not install Docker, drivers, packages or host services to repair them.

The native app creates only the labeled `gridlens-gpu-recorder` Docker container
and `gridlens-gpu-recorder-data` named volume. It checks ownership/configuration
before reusing either name, preserves existing data and never deletes a colliding
resource. Its Python image is pinned by digest. The container has no network or
published port, no Docker socket or host filesystem bind, a read-only root,
dropped capabilities and bounded resources. NVIDIA's existing utility runtime
provides access to GPU measurements; Docker/NVIDIA operations still affect host
resources. No GPU setting, power limit, workload or existing VPN is changed.

Every 15 seconds a fixed `nvidia-smi -q -x` read records these signals by measured
GPU UUID when supported by the GPU/driver:

- Software thermal slowdown.
- Hardware thermal protection.
- Software power limiting.
- Hardware power braking.

Supported duration counters retain their exact microsecond values. Unsupported
flags/counters remain null. SQLite recording runs independently of the phone,
survives recorder restart, and retains **up to** seven days and 20,000 GPU
records across all GPUs within a bounded database. More GPUs shorten effective retention.
Clock changes, failed queries, gaps and counter resets remain explicit. During a
recorder process, monotonic-versus-wall discontinuities suspend insertion and age
pruning until the clock returns to its coherent timeline. PC wall time after a
process/host restart is not independently authenticated.

Rescan reads the last 24 hours into app-private saved fleet data. Expand **GPU
conditions (PC recordings)** below Scenario to inspect latest flags, supported
sample counts, valid counter increases, source times and recording gaps. Reopening
the phone retains original timestamps. Ordinary public-data Refresh does not read
the PCs. A saved inactive flag is never proof that no throttling occurred between
samples. Retained records are not presented as a current live measurement.

## Remaining rewards and job-attribution boundaries

The required rewards field is still exactly `claimableUptimeNosRewards`.
Verified automatic Nosana authorization is not connected. Anonymous rewards
requests and temporary unfunded reader-identity tests did not establish access.
This update does not access existing Nosana/Solana keys, export authorization
headers, register an account, sign a transaction or claim funds. Running the GPU
recorder cannot unlock rewards; the amount remains unknown rather than becoming
a stake balance, estimate, manual entry or fabricated zero.

PC-level GPU measurements do not establish which physical GPU executed each
Nosana job. This update therefore leaves host GPU ID and per-job throttle
attribution unknown. The physical GPU association and trustworthy job interval
must be established before labeling readings **observed during this job**.
That label would still not establish causation. Unrecorded past jobs cannot be
reconstructed from benchmark temperatures, watts or market names.

Reference: [Nosana hosts API](https://github.com/nosana-ci/nosana-kit/blob/main/docs/api/hosts.md),
[NVIDIA flags and counters](https://docs.nvidia.com/deploy/nvidia-smi/index.html),
[NVIDIA container utility capability](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/docker-specialized.html).

## Verification boundaries

Isolated JVM SSH fixtures verify fixed commands, identity pinning, failure behavior,
ownership and the Docker operation scope. Python fixtures verify NVIDIA XML
parsing, unsupported signals, recording/restart, capacity, clock regression and
counter resets. Runtime/browser checks verify validation, old fleets, display
controls and preserved timestamps. These do not establish actual NVIDIA Docker
startup on an operator PC, factory Pixel acceptance of this update, or GrapheneOS
compatibility. No production PC collector was deployed from the development
workspace; enabling selected PCs happens through the phone flow above.
