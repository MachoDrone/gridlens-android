import { build } from "esbuild";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { cp, mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("../", import.meta.url)));
const vendor = join(root, "vendor/gridlens");
const destination = join(root, "dist/runtime");
const run = spawnSync(process.execPath, [join(vendor, "scripts/build.ts")], { cwd: vendor, stdio: "inherit" });
if (run.status !== 0) throw new Error("The original Fleet browser build failed.");
await rm(destination, { recursive: true, force: true });
await mkdir(destination, { recursive: true });
await cp(join(vendor, "dist"), join(destination, "web"), { recursive: true });
// Only Android's output opts into native saved-fleet navigation. The inherited
// browser tests and desktop/snapshot builds retain their existing behavior.
const mobileIndex = join(destination, "web/index.html");
await writeFile(mobileIndex, (await readFile(mobileIndex, "utf8"))
  .replace("<head>", '<head>\n  <meta name="gridlens-mobile" content="android">'));

await build({
  absWorkingDir: root,
  entryPoints: [join(root, "runtime/entry.ts")],
  outfile: join(destination, "server.mjs"),
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node24",
  sourcemap: false,
  minify: false,
  legalComments: "eof",
  // A vendored module's CLI guard must not start the desktop preview when its
  // import.meta.url becomes the bundle URL. Mobile is the sole entry point.
  plugins: [{ name: "disable-vendored-cli", setup(builder) {
    builder.onLoad({ filter: /vendor\/gridlens\/scripts\/preview-server\.ts$/ }, async args => {
      const source = await readFile(args.path, "utf8");
      const marker = 'if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {';
      if (source.split(marker).length !== 2) throw new Error("The upstream CLI guard changed; review the Android adapter.");
      return { contents: source.replace(marker, "if (false) {"), loader: "ts", resolveDir: dirname(args.path) };
    });
  } }],
});

// Ship license notices for all installed production dependencies, never their
// development sources or private runtime files.
const notice: string[] = [await readFile(join(vendor, "LICENSE"), "utf8"),
  "\n\n--- Embedded Node.js Mobile runtime ---\n\n" + await readFile(join(root, "licenses/nodejs-mobile-LICENSE.txt"), "utf8"),
  "\n\n--- JSch 2.28.8 (Android native SSH) ---\n\n" + await readFile(join(root, "licenses/jsch-2.28.8-LICENSE.txt"), "utf8"),
  "\n\n--- Bouncy Castle 1.86 (Android native cryptography) ---\n\n" + await readFile(join(root, "licenses/bouncycastle-1.86-LICENSE.txt"), "utf8"),
  "\n\n--- Android NDK runtime library notices ---\n\n" + await readFile(join(root, "licenses/android-ndk-NOTICE.txt"), "utf8")];
async function collectNotices(directory: string): Promise<void> {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
    const packageDirectory = join(directory, entry.name);
    if (entry.name.startsWith("@")) { await collectNotices(packageDirectory); continue; }
    const packageFile = join(packageDirectory, "package.json");
    let metadata: { name?: string; version?: string; license?: string };
    try { metadata = JSON.parse(await readFile(packageFile, "utf8")); } catch { continue; }
    const licenseName = (await readdir(packageDirectory)).find(name => /^licen[sc]e(?:\.|$)/i.test(name));
    if (licenseName) notice.push(`\n\n--- ${metadata.name ?? entry.name} ${metadata.version ?? ""} (${metadata.license ?? "see notice"}) ---\n\n${await readFile(join(packageDirectory, licenseName), "utf8")}`);
  }
}
await collectNotices(join(vendor, "node_modules"));
await writeFile(join(destination, "THIRD_PARTY_NOTICES.txt"), notice.join("\n"));
const hashes: Record<string, string> = {};
async function hashTree(directory: string): Promise<void> {
  for (const entry of (await readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    const file = join(directory, entry.name);
    if (entry.isDirectory()) await hashTree(file);
    else if (entry.isFile()) hashes[relative(destination, file)] = createHash("sha256").update(await readFile(file)).digest("hex");
    else throw new Error("The runtime artifact must contain only ordinary files.");
  }
}
await hashTree(destination);
await writeFile(join(destination, "manifest.json"), JSON.stringify({ schema: 1, files: hashes }, null, 2) + "\n");
const assets = join(root, "android/app/src/main/assets/runtime");
await rm(assets, { recursive: true, force: true });
await mkdir(dirname(assets), { recursive: true });
await cp(destination, assets, { recursive: true });
console.log(`Built standalone Android runtime and ${Object.keys(hashes).length} reviewed assets.`);
