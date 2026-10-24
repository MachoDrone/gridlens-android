import { createHash } from "node:crypto";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const version = "24.22.1-0";
const sha256 = "d6fd9f610fe5d81fede8f0d400c5a62723247725a19831835f15abf4f0604250";
const archiveSize = 112853733;
const cache = resolve(root, ".tools", `nodejs-mobile-android-${version}.zip`);
await mkdir(resolve(root, ".tools"), { recursive: true });
let archive: Buffer;
try { archive = await readFile(cache); } catch {
  const response = await fetch(`https://github.com/fogtape/nodejs-mobile/releases/download/v${version}/nodejs-mobile-android-${version}.zip`, {
    signal: AbortSignal.timeout(600_000),
  });
  if (!response.ok || !response.body) throw new Error(`Native runtime download failed (${response.status}).`);
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > archiveSize) throw new Error("Native runtime archive exceeds its pinned size.");
    chunks.push(Buffer.from(chunk));
  }
  archive = Buffer.concat(chunks);
}
if (archive.length !== archiveSize || createHash("sha256").update(archive).digest("hex") !== sha256) {
  throw new Error("Native runtime archive does not match the pinned size and SHA-256.");
}
await writeFile(cache, archive);
const target = resolve(root, "android/app/libnode");
await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });
const result = spawnSync("unzip", ["-q", cache, "-d", target], { stdio: "inherit" });
if (result.status !== 0) throw new Error("Could not extract the verified native runtime (unzip required).");
console.log(`Verified Node.js Mobile ${version}; extracted Android libraries and headers.`);
