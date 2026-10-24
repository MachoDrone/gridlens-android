import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { createManifest, isAbsentStatus, parseManifest, parseRelease, parseVersion, RELEASE_MARKER,
  requireNewerVersion, requireOwnedDraft, verifyAssets } from "../tools/release-policy.ts";

const apk = Buffer.from("isolated APK fixture");
const version = { versionCode: 2, versionName: "0.2.0" };
const manifest = createManifest(version, apk);

test("manifest generation emits exactly the phone's seven fields despite extra version metadata", () => {
  const output = createManifest({ ...version, description: "extra build metadata", sourceCommit: "fixture" }, apk);
  assert.equal(Object.keys(output).length, 7);
  assert.deepEqual(output, manifest);
  assert.deepEqual(parseManifest(output), manifest);
  assert.equal(output.sha256, createHash("sha256").update(apk).digest("hex"));
});

test("standalone manifest generator works from a copied source artifact without repository context", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-release-"));
  try {
    await mkdir(join(directory, "tools"));
    for (const file of ["release-manifest.ts", "release-policy.ts"]) {
      await copyFile(new URL(`../tools/${file}`, import.meta.url), join(directory, "tools", file));
    }
    await writeFile(join(directory, "package.json"), '{"type":"module"}');
    await writeFile(join(directory, "version.json"), JSON.stringify({ ...version, unrelated: "must not enter update manifest" }));
    await writeFile(join(directory, "fixture.apk"), apk);
    const result = spawnSync(process.execPath, ["tools/release-manifest.ts", "fixture.apk", "update.json"], {
      cwd: directory, encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(await readFile(join(directory, "update.json"), "utf8")), manifest);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("invalid Android version and incompatible manifests fail before publication", () => {
  for (const invalid of [0, -1, 1.5, 2_147_483_648, "2", null]) {
    assert.throws(() => parseVersion({ ...version, versionCode: invalid }));
  }
  assert.throws(() => createManifest(version, Buffer.alloc(0)));
  assert.throws(() => parseManifest({ ...manifest, extra: true }));
  assert.throws(() => parseManifest({ ...manifest, apkUrl: "https://example.com/app.apk" }));
  assert.throws(() => parseManifest({ ...manifest, size: 256 * 1024 * 1024 + 1 }));
});

test("publishing a new tag requires a greater Android code, while first publication is allowed", () => {
  requireNewerVersion(version, null);
  requireNewerVersion({ versionCode: 3, versionName: "0.3.0" }, manifest);
  assert.throws(() => requireNewerVersion({ versionCode: 2, versionName: "0.3.0" }, manifest));
  assert.throws(() => requireNewerVersion({ versionCode: 1, versionName: "0.3.0" }, manifest));
});

test("only HTTP 404 means missing release; outages and denied requests never become first publication", () => {
  assert.equal(isAbsentStatus(404), true);
  assert.equal(isAbsentStatus(200), false);
  for (const status of [301, 401, 403, 429, 500, 502, 503]) assert.throws(() => isAbsentStatus(status));
});

test("draft retry never overwrites published or unrelated operator drafts", () => {
  const own = parseRelease({ tag_name: "v0.2.0", draft: true, prerelease: false, body: RELEASE_MARKER, assets: [] });
  requireOwnedDraft(own);
  assert.throws(() => requireOwnedDraft({ ...own, draft: false }));
  assert.throws(() => requireOwnedDraft({ ...own, prerelease: true }));
  assert.throws(() => requireOwnedDraft({ ...own, body: "Unrelated draft" }));
  assert.throws(() => requireOwnedDraft({ ...own, body: null }));
});

test("a partial upload cannot be published or mistaken for a completed idempotent run", () => {
  const required = new Map([
    ["gridlens-android.apk", { size: apk.length, sha256: manifest.sha256 }],
    ["update.json", { size: 300 }], ["SHA256SUMS.txt", { size: 86 }],
  ]);
  const complete = parseRelease({ tag_name: "v0.2.0", draft: true, prerelease: false, body: RELEASE_MARKER,
    assets: [{ name: "gridlens-android.apk", size: apk.length, state: "uploaded", digest: `sha256:${manifest.sha256}` },
      { name: "update.json", size: 300, state: "uploaded" }, { name: "SHA256SUMS.txt", size: 86, state: "uploaded" }] });
  verifyAssets(complete, required);
  assert.throws(() => verifyAssets({ ...complete, assets: complete.assets.slice(0, 1) }, required));
  assert.throws(() => verifyAssets({ ...complete, assets: complete.assets.map(item => ({ ...item, size: item.size + 1 })) }, required));
  assert.throws(() => verifyAssets({ ...complete, assets: complete.assets.map(item => ({ ...item, state: "starter" })) }, required));
  assert.throws(() => verifyAssets({ ...complete, assets: complete.assets.map(item => ({ ...item, digest: "sha256:wrong" })) }, required));
  assert.throws(() => verifyAssets({ ...complete, assets: [...complete.assets, complete.assets[0]!] }, required));
});
