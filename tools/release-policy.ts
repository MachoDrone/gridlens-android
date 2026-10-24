import { createHash } from "node:crypto";

export const RELEASE_REPOSITORY = "MachoDrone/gridlens-android-releases";
export const RELEASE_MARKER = "<!-- gridlens-android-release -->";
export const MAX_APK_BYTES = 256 * 1024 * 1024;
export const RELEASE_ASSETS = ["gridlens-android.apk", "update.json", "SHA256SUMS.txt"] as const;
export interface Version { versionCode: number; versionName: string }
export interface Manifest extends Version {
  schemaVersion: 1; apkUrl: string; sha256: string; size: number; minSdk: 26;
}
export interface ReleaseAsset { name: string; size: number; state: string; digest?: string | null }
export interface PublishedRelease {
  tag_name: string; draft: boolean; prerelease: boolean; body: string | null; assets: ReleaseAsset[];
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid release object.");
  return value as Record<string, unknown>;
}

export function parseVersion(value: unknown): Version {
  const candidate = object(value);
  if (!Number.isSafeInteger(candidate.versionCode) || (candidate.versionCode as number) < 1 ||
      (candidate.versionCode as number) > 2_147_483_647 || typeof candidate.versionName !== "string" ||
      candidate.versionName.length > 64 || !/^\d+\.\d+\.\d+$/.test(candidate.versionName)) {
    throw new Error("Release requires a positive Android versionCode and numeric major.minor.patch versionName.");
  }
  return { versionCode: candidate.versionCode as number, versionName: candidate.versionName };
}

export function createManifest(versionInput: unknown, apk: Uint8Array): Manifest {
  const version = parseVersion(versionInput);
  if (apk.byteLength < 1 || apk.byteLength > MAX_APK_BYTES) throw new Error("APK exceeds the Android updater's size limits.");
  return { schemaVersion: 1, versionCode: version.versionCode, versionName: version.versionName,
    apkUrl: `https://github.com/${RELEASE_REPOSITORY}/releases/download/v${version.versionName}/gridlens-android.apk`,
    sha256: createHash("sha256").update(apk).digest("hex"), size: apk.byteLength, minSdk: 26 };
}

export function parseManifest(value: unknown): Manifest {
  const manifest = object(value);
  const version = parseVersion(manifest);
  if (Object.keys(manifest).length !== 7 || manifest.schemaVersion !== 1 || manifest.minSdk !== 26 ||
      typeof manifest.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(manifest.sha256) ||
      !Number.isSafeInteger(manifest.size) || (manifest.size as number) < 1 || (manifest.size as number) > MAX_APK_BYTES ||
      manifest.apkUrl !== `https://github.com/${RELEASE_REPOSITORY}/releases/download/v${version.versionName}/gridlens-android.apk`) {
    throw new Error("Published update manifest does not match the Android updater contract.");
  }
  return { schemaVersion: 1, ...version, apkUrl: manifest.apkUrl, sha256: manifest.sha256,
    size: manifest.size as number, minSdk: 26 };
}

export function parseRelease(value: unknown): PublishedRelease {
  const release = object(value);
  if (typeof release.tag_name !== "string" || !/^v\d+\.\d+\.\d+$/.test(release.tag_name) ||
      typeof release.draft !== "boolean" || typeof release.prerelease !== "boolean" ||
      !(release.body === null || typeof release.body === "string") || !Array.isArray(release.assets)) {
    throw new Error("GitHub returned invalid Android release metadata.");
  }
  const assets = release.assets.map(item => {
    const asset = object(item);
    if (typeof asset.name !== "string" || typeof asset.state !== "string" ||
        !Number.isSafeInteger(asset.size) || (asset.size as number) < 0 ||
        !(asset.digest === undefined || asset.digest === null || typeof asset.digest === "string")) {
      throw new Error("GitHub returned invalid Android release asset metadata.");
    }
    return { name: asset.name, state: asset.state, size: asset.size as number,
      ...(asset.digest === undefined ? {} : { digest: asset.digest }) };
  });
  return { tag_name: release.tag_name, draft: release.draft, prerelease: release.prerelease, body: release.body, assets };
}

/** GitHub's tag endpoint can omit an unpublished draft whose tag does not exist yet. */
export async function lookupReleaseByTag(tag: string,
  api: (path: string, allowAbsent?: boolean) => Promise<unknown | null>): Promise<PublishedRelease | null> {
  if (!/^v\d+\.\d+\.\d+$/.test(tag)) throw new Error("Invalid Android release tag.");
  const checked = (value: unknown): PublishedRelease => {
    const candidate = parseRelease(value);
    if (candidate.tag_name !== tag) throw new Error("GitHub returned a different release tag.");
    if (candidate.draft) requireOwnedDraft(candidate);
    return candidate;
  };
  // The transport returns null only for a real 404; all denied/network/server errors propagate.
  const exact = await api(`/releases/tags/${tag}`, true);
  if (exact !== null) return checked(exact);
  const listed = await api("/releases?per_page=100");
  if (!Array.isArray(listed) || listed.length > 100) throw new Error("GitHub returned an invalid bounded release list.");
  const matches = listed.filter(item => object(item).tag_name === tag);
  if (matches.length > 1) throw new Error("GitHub returned ambiguous duplicate releases for this tag.");
  if (matches.length === 1) return checked(matches[0]);
  if (listed.length === 100) throw new Error("The bounded release list could not establish absence of an older draft.");
  return null;
}

export function requireNewerVersion(candidate: Version, latest: Manifest | null): void {
  if (latest !== null && candidate.versionCode <= latest.versionCode) {
    throw new Error(`Android versionCode must exceed published code ${latest.versionCode}; no release was changed.`);
  }
}

export function requireOwnedDraft(release: PublishedRelease): void {
  if (!release.draft || release.prerelease || !release.body?.includes(RELEASE_MARKER)) {
    throw new Error("Only this workflow's marked, unpublished draft can be resumed or replaced.");
  }
}

export function verifyAssets(release: PublishedRelease, expected: ReadonlyMap<string, { size: number; sha256?: string }>): void {
  for (const name of RELEASE_ASSETS) {
    const found = release.assets.filter(asset => asset.name === name);
    const required = expected.get(name);
    if (!required || found.length !== 1 || found[0]!.state !== "uploaded" || found[0]!.size !== required.size) {
      throw new Error(`Release asset ${name} is missing, incomplete or the wrong size.`);
    }
    if (required.sha256 && found[0]!.digest != null && found[0]!.digest !== `sha256:${required.sha256}`) {
      throw new Error(`GitHub's digest for ${name} does not match the verified local artifact.`);
    }
  }
}

/** Only an actual resource 404 means absence; auth, throttling and server errors stop publication. */
export function isAbsentStatus(status: number): boolean {
  if (status === 404) return true;
  if (status === 200) return false;
  throw new Error(`GitHub release metadata request failed (HTTP ${status}); absence is unconfirmed.`);
}
