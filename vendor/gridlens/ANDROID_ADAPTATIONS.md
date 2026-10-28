# Android snapshot

This is the current local TypeScript app copied into the Android repository, with
its source, tests and exact dependency lock. `PROVENANCE.json` identifies the
upstream repository and base commit and hashes every copied file. The snapshot
includes uncommitted Jobs/Country work that existed in the selected app; checking
out the base commit alone does not reproduce it. No laptop roster, runtime data,
key, credential or local test evidence is included.

Public-provider measurements and budgets are unchanged. Android PC/IP columns additionally use dated native discovery records. Explicit
mobile adaptations support Android:

- `scripts/preview-server.ts` has optional mobile hooks. Authorization runs before
  any request handler and before the listener is advertised. A native roster
  import updates the existing runtime's selected roster without constructing new
  providers or resetting request budgets, caches and retry deadlines. Known
  saved-fleet UUIDs select an independent public roster. Network Discovery has an
  empty idle roster. Unknown IDs fail closed. Saved roster changes cancel old
  streams and update provider retention pins.
- `scripts/online-retention.ts` can replace fixed roster pins while preserving
  removed addresses through ordinary retention and outstanding retry deadlines.
- `src/preview/main.ts`, `fleet-config.ts` and new `mobile-scenarios.ts` add the
  mobile scenario selector and fixed native navigation only when Android's build
  injects its trusted HTML marker. Saved-fleet names use text nodes; web requests
  are read-only. Search/Rescan opens native screens through user-initiated
  navigation, never a JavaScript credential/command bridge. Desktop/snapshot
  builds do not receive the marker or make mobile API calls.
- `src/server/browser-assets.ts` permits the new local module. Its existing
  preview-server fixture gains that asset. Other inherited tests are unchanged.

`PROVENANCE.json` preserves original source hashes and records each adapted hash;
the new Android module has a null original hash. Native profiles, credentials
and fleet persistence are outside the vendored application. The desktop checkout
remains independent and untouched.

The Android bundler disables the desktop module's CLI entry guard in its output;
otherwise bundling `import.meta.url` would also run the desktop entry point. The
standalone Android entry is `runtime/entry.ts` in the enclosing repository, with
testable server composition in `runtime/server.ts`. The dedicated entry does not
compare argv with module paths, which differ through Android's private-data aliases.
The source desktop CLI still works and the full original check suite is retained.

Run `npm run check:vendor` from the Android repository for those checks. Run the
root `npm run check` for Android runtime, artifact and inherited Fleet checks.

Android 0.2.1 also adapts `mobile-scenarios.ts` and marker-gated `main.ts` to display
validated PC names/IPs with the inherited sorting/display controls. New
`mobile-telemetry.ts` validates and presents PC-level GPU history without assigning
it to a host/job. Browser assets and their fixture include this module. Original
source hashes remain unchanged; the complete patch reproduces every adapted file.
The recording native bridge is still absent: only explicit native SSH actions can
supply metadata or enable Docker recording. Public readers never gain credentials.

Android 0.2.2 adds `src/fleet/pc-observations.ts` and
`src/preview/mobile-monitoring.ts` for strict native-observation contracts and
bounded cache-only browser reads. The optional exact rewards sort key extends
`src/fleet/sort.ts`; existing callers retain their prior behavior. Android's
`main.ts` renders exact/retained rewards and fixed native setup navigation, while
an optional `job-history.ts` callback adds GPU conditions only in Android saved
fleets. Conditions require matched host, UUID allocation and verified job timing;
counter resets/gaps/unsupported signals stay explicit. New module assets are
allowlisted and hash-recorded. No desktop checkout or public-provider budget is
changed; browser code still has no credential, signing, Docker or SSH interface.

Android 0.2.3 additionally adapts styles.css under the trusted Android root marker
for ordinary PC/IP text and rounded green reward links. main.ts and
mobile-scenarios.ts apply the reference IP display cycle without changing the
inherited non-Android formatter. pc-observations.ts adds optional validated,
independently dated public/private network readings; old snapshots remain valid.
The phone never supplies its own public IP as a host measurement.

Android 0.2.8 removes reward clock elements, their CSS and related help from
marker-gated main.ts/styles.css. Current and retained known amounts remain
regular-weight #0f5c2b links. Exact amounts and status remain in descriptions;
timestamps say “Last read by GridLens” and do not claim Nosana update times.
mobile-scenarios.ts preserves both IP slots, using − for missing addresses;
IPv6 remains complete in compact modes. IP help covers public-only connections
and shared NAT without changing discovery, collection or observation times.

Android 0.2.9 extends only the marker-gated IP display to six steps: hidden,
both full addresses, external IP / NAT, external IPv4 last octet, internal IP,
and internal IPv4 last octet. Suffixes include their leading dot; IPv6 stays
complete. The existing preference key and dot/full/ext/int meanings remain,
with public-full/private-full added. Invalid or missing preferences hide IPs.
The inherited desktop formatter and its four-mode cycle stay byte-identical.
All modes retain full-address sorting and initiate no requests.

mobile-monitoring.ts additionally validates optional PC-keyed network records
against saved fleet PC identities. main.ts shares each record with every host
currently assigned to that PC, independently of node monitoring observations.
Legacy node-bound network observations remain readable. IP details expose only
bounded public/private failure categories, an HTTP status when applicable, and
recorded retry eligibility. Latest and retained source labels/timestamps remain
separate; rendering never advances their evidence. pc-observations.ts validates
the compatible schema-1 PC-network update/read contracts and bounded failure
fields while retaining the existing reward/GPU/node contracts.

Android 0.2.10 corrects the remaining Column info IP summary to describe all six
modes and direct PC curl collection. Data meanings, saved preferences, sorting
and provider requests are unchanged.
