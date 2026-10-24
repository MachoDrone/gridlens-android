import assert from "node:assert/strict";
import { test } from "node:test";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

test("the original Fleet snapshot and its two reviewed Android adaptations match provenance", async () => {
  const directory = resolve("vendor/gridlens");
  const provenance = JSON.parse(await readFile(resolve(directory, "PROVENANCE.json"), "utf8")) as {
    repository: string;
    baseCommit: string;
    snapshotIncludesUncommittedChanges: boolean;
    files: { path: string; sourceSha256: string; sha256: string }[];
    androidAdaptations: { paths: string[] };
  };
  assert.equal(provenance.repository, "https://github.com/MachoDrone/gridlens-typescript");
  assert.match(provenance.baseCommit, /^[0-9a-f]{40}$/);
  const adapted: string[] = [];
  for (const entry of provenance.files) {
    assert(!entry.path.includes("..") && !entry.path.startsWith("/"));
    assert(!/(^|\/)(AGENTS\.md|hosts\.json|real200\.json|node_modules|\.git|\.tools)(\/|$)/.test(entry.path));
    const digest = createHash("sha256").update(await readFile(resolve(directory, entry.path))).digest("hex");
    assert.equal(digest, entry.sha256, `${entry.path}: final snapshot hash`);
    if (entry.sha256 !== entry.sourceSha256) adapted.push(entry.path);
    if (entry.path.startsWith("src/")) assert.equal(entry.sha256, entry.sourceSha256, `${entry.path}: browser/domain source remains identical`);
  }
  assert.deepEqual(adapted.sort(), ["scripts/online-retention.ts", "scripts/preview-server.ts"]);
  assert.deepEqual([...provenance.androidAdaptations.paths].sort(), adapted);
});
