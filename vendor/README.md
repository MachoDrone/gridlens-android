# Shared Fleet source maintenance

`gridlens/` is a reviewed source snapshot of `MachoDrone/gridlens-typescript`.
`gridlens/PROVENANCE.json` is the exact copied-file allowlist, the upstream base
commit, the original SHA-256 for each file, the current SHA-256, and disclosure
that this first snapshot also contains the existing local uncommitted work.
The only adapted source files are listed in `androidAdaptations.paths`.
`mobile-adaptations.patch` is the complete mobile adaptation against that source.

For the next Android version, start from a clean temporary candidate directory.
Copy the newly reviewed desktop revision's same manifest paths, plus explicitly
reviewed new source/test files. The allowed roots are `src/`, `scripts/`, `test/`
and `docs/`; top-level files are README, LICENSE, package.json, package-lock.json,
tsconfig.json, tsconfig.browser.json, tsconfig.runtime.json, playwright.config.ts,
.nvmrc and .gitignore. Do not recursively copy the checkout, its .git directory,
AGENTS instructions, node_modules, build output, external rosters, runtime data,
credentials or evidence. Record original SHA-256 values before applying changes.

From the candidate's root, apply the reviewed adapter with:

```sh
git apply --check /path/to/gridlens-android/vendor/mobile-adaptations.patch
git apply /path/to/gridlens-android/vendor/mobile-adaptations.patch
```

If either check fails, reconcile the adapter against the changed backend; never
ignore a patch failure or reimplement browser behavior to avoid it. Refresh the
adapter patch if needed, replace `gridlens/` with the candidate, and update
PROVENANCE.json's revision/disclosure, original hashes and final hashes. Keep browser files byte-for-byte identical except the explicit marker-gated
Android adaptations in ANDROID_ADAPTATIONS.md. The root test verifies every final
hash and requires unmodified hashes outside the exact adaptation allowlist.

Then run from the Android repository:

```sh
npm ci --ignore-scripts
npm run deps:vendor
npm run check
npm run native:prepare
```

Build and test the Android APK as described in the root README. Increment
`version.json`, publish a signed tested APK to the separate downloads repository,
and advance its manifest only after the matching asset is available. A desktop
source commit alone never changes an installed phone; the phone's Update button
installs a reviewed Android release through Android's package installer.
