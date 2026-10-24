import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { createManifest } from "./release-policy.ts";

const [apkPath, outputPath] = process.argv.slice(2);
if (!apkPath || !outputPath) throw new Error("Usage: node tools/release-manifest.ts APK OUTPUT.json");
const version: unknown = JSON.parse(await readFile(new URL("../version.json", import.meta.url), "utf8"));
const apk = await readFile(resolve(apkPath));
const manifest = createManifest(version, apk);
await writeFile(resolve(outputPath), JSON.stringify(manifest, null, 2) + "\n");
console.log(`Release manifest generated for ${manifest.versionName}.`);
