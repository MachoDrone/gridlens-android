# Android saved fleets

These endpoints exist only in the Android loopback runtime. The desktop source
checkout and supplied-address import files remain independent. App launch, listing
fleets, reopening a scenario and ordinary public-data refresh never call network
discovery or SSH. Native Android owns those explicit actions.

Every request requires the existing app session and exact loopback Host/origin.
Read-only requests accept the app's session cookie or native `X-GridLens-Token`.
Mutations require the native token header; the WebView cookie alone is insufficient.
No credential or private key is accepted by the fleet schema. Native credential
storage is separate and is not a Node or WebView API.

## List and mutate

`GET /mobile/fleets` returns:

```json
{
  "schema": 1,
  "selectedScenario": "network-discovery",
  "fleets": [],
  "error": null
}
```

Each fleet has an opaque server-generated UUID `id`, a user `name`,
`createdAtMs`, `updatedAtMs`, and `hosts`. Each host contains its validated public `address`, `firstDiscoveredAtMs` and
`lastDiscoveredAtMs`, plus optional `pcMetadata` collected through native SSH.
The metadata has `pcId` (SHA-256 of the pinned SSH public key), `pcName` (or null),
`ipAddresses`, `observedAtMs` and optional `gpuTelemetry`. It contains no SSH
credential, node key or authorization header. Old schema-1 records remain valid. Discovery dates
record when native discoveries were saved; they are not metric observation times.

`POST /mobile/fleets` takes JSON with one of these exact shapes:

| Operation | Fields |
| --- | --- |
| Create | `{"op":"create","name":"Customer fleet","addresses":[...]}` |
| Merge verified discoveries | `{"op":"merge","id":"<known UUID>","addresses":[...]}` |
| Rename | `{"op":"rename","id":"<known UUID>","name":"New name"}` |
| Explicit host removal | `{"op":"remove-hosts","id":"<known UUID>","addresses":[...]}` |
| Delete fleet | `{"op":"delete","id":"<known UUID>"}` |
| Select | `{"op":"select","scenario":"fleet:<known UUID>"}` |

Selection also accepts `network-discovery`, `supplied`, `real200` and
`online-gpu-hosts`. Create and merge automatically select the resulting fleet.
Deleting the selected fleet selects Network Discovery. Rename and host removal
preserve the current selection. An empty merge removes nothing; a new fleet needs
at least one verified address. Unknown operation fields and duplicate JSON fields
are rejected. Success returns `ok: true`, the complete list/selection response and
`fleetId` for fleet operations. Invalid mutations return HTTP 400 with a bounded
application error; denied requests return 403.

Limits are 512 KiB per request, 100 fleets, 10,000 unique public addresses per fleet,
and 20,000 total saved host records. Names contain 1–80 characters without control
characters. Native callers must accept a response up to 4 MiB. Addresses must be
base58 values decoding to exactly 32 bytes; duplicate addresses fail the operation.
No partial mutation is applied after validation failure. Create/merge optionally
accept `pcMetadata: [{address, pcId, pcName, ipAddresses, observedAtMs,
gpuTelemetry?}]`; each address must belong to that operation's roster. A conflict
sentinel uses null pcId/name and empty IPs to clear a previous ambiguous mapping.
Omitted metadata preserves prior observation times. GPU summaries use a strict
nested schema: only four reason flags/counters, bounded UUID histories, original
source times, sampling/window metadata and enumerated status are accepted.
Unknown fields fail closed. Saved files reserve 16 KiB below the 4 MiB read limit
for newline and native response envelopes.

## Monitoring

The browser uses the ordinary provider routes with
`?scenario=fleet:<known UUID>`. The identifier selects a saved server-owned roster;
no request can select arbitrary addresses or filesystem paths. Unknown or deleted
fleet IDs return 404. `?scenario=network-discovery` returns an empty roster and
empty readings, without starting a provider read. Supplied Host Addresses and the
other existing scenarios retain their ordinary behavior.

All saved fleets share the same provider instances, caches, retry deadlines and
budgets with other live scenarios. Saved fleets use the same active public-reader
policy as Supplied Host Addresses. Mutating a fleet cancels its previous streams;
new reads use the updated roster. Different fleets and membership revisions do not
share an incorrectly scoped in-flight response. Observations retain original
source times regardless of discovery or reopening. No SSH access is needed after
saving public identities.

## Persistence and recovery

Only `discovered-fleets.json` and its previous validated revision
`discovered-fleets.json.bak` are written in the app-private data directory. Both
are mode 0600. Operations serialize, flush a temporary file, rename atomically and
flush the containing directory. Supplied imports and encrypted native credentials
are never overwritten by these operations. App-private backup exclusion applies
through the Android manifest/data-extraction policy.

On restart the runtime validates the complete schema, identities, unique IDs and
addresses, selected scenario, capacities and timestamp ordering. A corrupt primary
can recover the previous valid revision with a visible warning; loading alone does
not overwrite the corrupt bytes. If both revisions are unreadable, files remain
untouched and mutations are disabled. A persistence failure disables further writes
until restart reconciles the on-disk revision. Recovery can lose the latest failed
or corrupted revision; it does not invent discovery or measurement freshness.

## Native monitoring observations (0.2.2)

`POST /mobile/observations` requires the native token and accepts at most 2 MiB:
`{schema:1,fleetId,updates:[{address,binding,rewards?,gpu?,monitoring?,network?}]}`. The known
saved fleet owns every distinct updated address. Exact bindings contain pcId,
runtime, wrapperId and nodeContainerId, without credentials. Rewards contain
only public identity, exact six-decimal NOS, source time, status and bounded
retry delay. GPU schema 2 contains verified UUIDs, source clock/window, status,
truncation and compact samples `[atMs,uuidIndex,supportedMask,activeMask,counters4]`.
Monitoring status is an enum plus original check time; no raw errors are allowed.
The complete batch is validated before writes. A persistence failure stops writes;
restart reconciles each durable per-host journal without renewing source time.

Browser-authenticated `GET /mobile/observations?fleet=<known UUID>` returns cached
summaries without sample arrays. Adding `&address=<saved public address>` returns
that host's bounded timeline. These reads never perform SSH or provider calls.
Binding changes clear incompatible retained evidence; failures preserve last
successful reward amounts and GPU samples. Sample merges deduplicate actual
source points. Data stays app-private and is purged when its fleet/host is removed.
Private native approval/credential state is stored separately in Android Keystore
protected storage and is never accepted or exposed by these endpoints.

The optional network observation added in 0.2.3 is schema 1 with observedAtMs,
status, publicIp/publicObservedAtMs, privateIp/privateObservedAtMs, and the fixed
source node-egress-ipify. Numeric addresses are classified before persistence;
unknown fields, invalid pairs and public/private misclassification fail closed.
The store independently retains each last successful part in lastGoodNetwork.
Older journals remain compatible. These fields contain no endpoint authority,
credentials or request URL and cannot trigger connections from the browser.
