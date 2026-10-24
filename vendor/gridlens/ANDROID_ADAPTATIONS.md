# Android snapshot

This is the current local TypeScript app copied into the Android repository, with
its source, tests and exact dependency lock. `PROVENANCE.json` identifies the
upstream repository and base commit and hashes every copied file. The snapshot
includes uncommitted Jobs/Country work that existed in the selected app; checking
out the base commit alone does not reproduce it. No laptop roster, runtime data,
key, credential or local test evidence is included.

Fleet display rules, measurements and provider budgets are unchanged. Explicit
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
