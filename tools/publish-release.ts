import { appendFile, readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { createManifest, isAbsentStatus, parseManifest, parseRelease, parseVersion, RELEASE_ASSETS,
  RELEASE_MARKER, RELEASE_REPOSITORY, requireNewerVersion, requireOwnedDraft, verifyAssets } from "./release-policy.ts";
import type { Manifest, PublishedRelease } from "./release-policy.ts";

const apiRoot = `https://api.github.com/repos/${RELEASE_REPOSITORY}`;
const token = process.env.GH_TOKEN;
const version = parseVersion(JSON.parse(await readFile(new URL("../version.json", import.meta.url), "utf8")));
const tag = `v${version.versionName}`;

async function bytes(response: Response, maximum: number): Promise<Buffer> {
  if (!response.body) throw new Error("GitHub returned an empty response.");
  const pieces: Buffer[] = [];
  let size = 0;
  for await (const part of response.body) {
    size += part.length;
    if (size > maximum) throw new Error("GitHub response exceeded its expected bounds.");
    pieces.push(Buffer.from(part));
  }
  return Buffer.concat(pieces);
}

async function api(path: string, allowAbsent = false): Promise<unknown | null> {
  const response = await fetch(apiRoot + path, { redirect: "error", signal: AbortSignal.timeout(30_000),
    headers: { Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "GridLens-Android-Release", ...(token ? { Authorization: `Bearer ${token}` } : {}) } });
  if (allowAbsent && isAbsentStatus(response.status)) { await response.body?.cancel(); return null; }
  if (response.status !== 200) { await response.body?.cancel(); throw new Error(`GitHub metadata request failed (HTTP ${response.status}).`); }
  return JSON.parse((await bytes(response, 1024 * 1024)).toString("utf8"));
}

async function release(path: string): Promise<PublishedRelease | null> {
  const result = await api(path, true);
  return result === null ? null : parseRelease(result);
}

async function publicArtifact(releaseTag: string, name: "update.json" | "SHA256SUMS.txt"): Promise<Buffer> {
  const response = await fetch(`https://github.com/${RELEASE_REPOSITORY}/releases/download/${releaseTag}/${name}`, {
    redirect: "follow", signal: AbortSignal.timeout(30_000), headers: { "User-Agent": "GridLens-Android-Release" },
  });
  if (!response.ok) { await response.body?.cancel(); throw new Error(`Published ${name} is unavailable (HTTP ${response.status}).`); }
  return bytes(response, 16 * 1024);
}

async function verifyPublished(item: PublishedRelease): Promise<Manifest> {
  if (item.draft || item.prerelease) throw new Error("The phone updater requires a published release usable by GitHub's latest-download URL.");
  const raw = await publicArtifact(item.tag_name, "update.json");
  const manifest = parseManifest(JSON.parse(raw.toString("utf8")));
  if (item.tag_name !== `v${manifest.versionName}`) throw new Error("Published tag and update manifest disagree.");
  const checksum = await publicArtifact(item.tag_name, "SHA256SUMS.txt");
  if (checksum.toString("utf8") !== `${manifest.sha256}  gridlens-android.apk\n`) throw new Error("Published checksum file does not match the Android update manifest.");
  verifyAssets(item, new Map([
    ["gridlens-android.apk", { size: manifest.size, sha256: manifest.sha256 }],
    ["update.json", { size: raw.length, sha256: createHash("sha256").update(raw).digest("hex") }],
    ["SHA256SUMS.txt", { size: checksum.length, sha256: createHash("sha256").update(checksum).digest("hex") }],
  ]));
  return manifest;
}

function gh(arguments_: string[]): void {
  const result = spawnSync("gh", [...arguments_, "--repo", RELEASE_REPOSITORY], { stdio: "inherit", shell: false });
  if (result.error || result.status !== 0) throw new Error("GitHub release operation failed; any unpublished draft is retained for a safe retry.");
}

// Confirm repository access first: a release 404 must not stand in for an inaccessible repository.
await api("");
const existing = await release(`/releases/tags/${tag}`);
const latest = await release("/releases/latest");
const published = latest === null ? null : await verifyPublished(latest);
const mode = process.argv[2];

if (mode === "select") {
  let needed = true;
  if (existing && !existing.draft) {
    const existingManifest = await verifyPublished(existing);
    if (existingManifest.versionCode !== version.versionCode) throw new Error("The source reuses a published versionName with a different versionCode.");
    needed = false;
  } else {
    if (existing) requireOwnedDraft(existing);
    requireNewerVersion(version, published);
  }
  const result = `tag=${tag}\ncode=${version.versionCode}\nneeded=${needed}\n`;
  if (process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT, result);
  else process.stdout.write(result);
} else if (mode === "publish") {
  if (!token) throw new Error("Publication needs the distribution repository's scoped GH_TOKEN.");
  const directory = resolve(process.argv[3] ?? "release");
  const expected = new Map<string, { size: number; sha256: string }>();
  const assets = new Map<string, Buffer>();
  for (const name of RELEASE_ASSETS) {
    const content = await readFile(resolve(directory, name));
    assets.set(name, content);
    expected.set(name, { size: content.length, sha256: createHash("sha256").update(content).digest("hex") });
  }
  const manifest = parseManifest(JSON.parse(assets.get("update.json")!.toString("utf8")));
  if (JSON.stringify(manifest) !== JSON.stringify(createManifest(version, assets.get("gridlens-android.apk")!))) {
    throw new Error("Local APK, version and update manifest do not match.");
  }
  if (assets.get("SHA256SUMS.txt")!.toString("utf8") !== `${manifest.sha256}  gridlens-android.apk\n`) throw new Error("Local checksum file is invalid.");
  if (existing && !existing.draft) {
    const current = await verifyPublished(existing);
    if (JSON.stringify(current) !== JSON.stringify(manifest)) throw new Error("Refusing to overwrite an already published Android version.");
    console.log(`Already published and complete: ${tag}`);
  } else {
    requireNewerVersion(version, published);
    const notes = resolve(directory, "notes.md");
    if (!(await readFile(notes, "utf8")).includes(RELEASE_MARKER)) throw new Error("Release notes need the workflow ownership marker.");
    if (existing) {
      requireOwnedDraft(existing);
      gh(["release", "edit", tag, "--draft", "--title", `GridLens Android ${tag}`, "--notes-file", notes]);
    } else {
      gh(["release", "create", tag, "--draft", "--title", `GridLens Android ${tag}`, "--notes-file", notes]);
    }
    // Only this unpublished draft can be replaced; a published APK is immutable here.
    gh(["release", "upload", tag, ...RELEASE_ASSETS.map(name => resolve(directory, name)), "--clobber"]);
    const staged = await release(`/releases/tags/${tag}`);
    if (!staged) throw new Error("GitHub did not return the staged release.");
    requireOwnedDraft(staged);
    verifyAssets(staged, expected);
    gh(["release", "edit", tag, "--draft=false", "--latest"]);
    const confirmed = await release("/releases/latest");
    if (!confirmed || confirmed.tag_name !== tag) throw new Error("GitHub's latest release has not been confirmed.");
    if (JSON.stringify(await verifyPublished(confirmed)) !== JSON.stringify(manifest)) throw new Error("Published update manifest did not match the tested APK.");
    console.log(`Published and verified: https://github.com/${RELEASE_REPOSITORY}/releases/tag/${tag}`);
  }
} else {
  throw new Error("Usage: node tools/publish-release.ts select | publish [artifact-directory]");
}
