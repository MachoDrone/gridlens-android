import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, readFile, rm, stat, writeFile, cp, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { request as httpRequest } from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { parseRoster, startAndroidRuntime, SESSION_COOKIE, MAX_ROSTER_HOSTS } from "../runtime/server.ts";
import type { RuntimeOptions } from "../runtime/server.ts";
import { createOnlineRetention } from "../vendor/gridlens/scripts/online-retention.ts";

const first = "1".repeat(32);
const second = "1".repeat(31) + "2";
const directory = resolve("vendor/gridlens/dist");

async function fixture(providers: RuntimeOptions["providers"] = {}) {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-mobile-"));
  // Tests create only isolated public synthetic inputs. No live host file is read.
  await writeFile(join(dataDirectory, "hosts.json"), JSON.stringify([first]));
  await writeFile(join(dataDirectory, "real200.json"), "[]");
  const runtime = await startAndroidRuntime({ dataDirectory, directory, preferredPort: 0, providers });
  return {
    ...runtime,
    dataDirectory,
    origin: `http://127.0.0.1:${runtime.port}`,
    headers: { Cookie: `${SESSION_COOKIE}=${runtime.token}` },
    native: { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" },
    async close() {
      runtime.server.closeAllConnections();
      await new Promise<void>(accept => runtime.server.close(() => accept()));
      await rm(dataDirectory, { recursive: true, force: true });
    },
  };
}

test("rosters accept only complete unique public-address arrays", () => {
  assert.deepEqual(parseRoster([]), []);
  assert.deepEqual(parseRoster([first, second]), [first, second]);
  for (const invalid of [null, {}, { addresses: [first] }, [first, first], ["private-key"], [1], ["1".repeat(31)], ["../hosts.json"], Array(MAX_ROSTER_HOSTS + 1).fill(first)]) {
    assert.throws(() => parseRoster(invalid));
  }
});

test("all local routes require the app session before any source read", async () => {
  let reads = 0;
  const value = await fixture({ metadataProvider: async () => { reads++; return { hosts: [] }; } });
  try {
    for (const path of ["/", "/styles.css", "/preview/connection.json", "/preview/hosts.json", "/preview/metadata.json"]) {
      assert.equal((await fetch(value.origin + path)).status, 403);
    }
    assert.equal(reads, 0);
    assert.equal((await fetch(value.origin + "/preview/hosts.json", { headers: value.headers })).status, 200);
    assert.equal((await fetch(value.origin + "/preview/metadata.json", { headers: { Cookie: `${SESSION_COOKIE}=wrong` } })).status, 403);
    assert.equal((await fetch(value.origin + "/preview/metadata.json", { headers: { ...value.headers, Origin: "https://attacker.example" } })).status, 403);
    assert.equal((await fetch(value.origin + "/preview/metadata.json", { headers: { ...value.headers, "Sec-Fetch-Site": "same-site" } })).status, 403);
    const forgedHost = await new Promise<number>(accept => {
      httpRequest(value.origin + "/preview/metadata.json", { headers: { ...value.headers, Host: "attacker.example" } }, incoming => {
        incoming.resume(); accept(incoming.statusCode!);
      }).end();
    });
    assert.equal(forgedHost, 403);
    assert.equal((await fetch(value.origin + "/preview/hosts.json", { headers: { Cookie: `${SESSION_COOKIE}=${value.token}; ${SESSION_COOKIE}=${value.token}` } })).status, 403);
    assert.equal(reads, 0);
    const response = await fetch(value.origin + "/", { headers: value.headers });
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-security-policy") ?? "", /connect-src 'self'/);
    assert.equal(response.headers.get("referrer-policy"), "no-referrer");
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.match(await response.text(), /Nosana Fleet/);
  } finally { await value.close(); }
});

test("authenticated native import persists only its roster, reuses providers and changes no other scenario", async () => {
  const selections: string[][] = [];
  const value = await fixture({ metadataProvider: async addresses => {
    selections.push([...addresses]);
    return { hosts: addresses.map(address => ({ address, values: null, fetchedAtMs: 1000, retained: true, error: null, marketError: null })) };
  } });
  try {
    await (await fetch(value.origin + "/preview/metadata.json", { headers: value.headers })).json();
    const denied = await fetch(value.origin + "/mobile/rosters/supplied", {
      method: "POST", headers: { ...value.headers, "Content-Type": "application/json" }, body: JSON.stringify([second]),
    });
    assert.equal(denied.status, 403);
    const response = await fetch(value.origin + "/mobile/rosters/supplied", {
      method: "POST", headers: value.native, body: JSON.stringify([second]),
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ok: true, count: 1, scenario: "supplied" });
    assert.deepEqual(JSON.parse(await readFile(join(value.dataDirectory, "hosts.json"), "utf8")), [second]);
    assert.equal((await stat(join(value.dataDirectory, "hosts.json"))).mode & 0o777, 0o600);
    assert.deepEqual(JSON.parse(await readFile(join(value.dataDirectory, "real200.json"), "utf8")), []);
    const roster = await (await fetch(value.origin + "/preview/hosts.json", { headers: value.headers })).json();
    assert.deepEqual(roster.addresses, [second]);
    const data = await (await fetch(value.origin + "/preview/metadata.json", { headers: value.headers })).json();
    assert.equal(data.hosts[0].address, second);
    assert.equal(data.hosts[0].fetchedAtMs, 1000);
    assert.deepEqual(selections, [[first], [second]]);
    assert.deepEqual((await (await fetch(value.origin + "/preview/hosts.json?scenario=real200", { headers: value.headers })).json()).addresses, []);
    const bad = await fetch(value.origin + "/mobile/rosters/supplied", { method: "POST", headers: value.native, body: JSON.stringify([second, second]) });
    assert.equal(bad.status, 400);
    assert.deepEqual(JSON.parse(await readFile(join(value.dataDirectory, "hosts.json"), "utf8")), [second]);
  } finally { await value.close(); }
});

test("imports reject oversized and non-JSON bodies without accepting arbitrary paths", async () => {
  const value = await fixture();
  try {
    assert.equal((await fetch(value.origin + "/mobile/rosters/../../hosts.json", { method: "POST", headers: value.native, body: "[]" })).status, 405);
    assert.equal((await fetch(value.origin + "/mobile/rosters/supplied?path=/tmp/other", { method: "POST", headers: value.native, body: "[]" })).status, 403);
    assert.equal((await fetch(value.origin + "/mobile/rosters/real200", { method: "POST", headers: { "X-GridLens-Token": value.token, "Content-Type": "text/plain" }, body: "[]" })).status, 400);
    const response = await new Promise<number>(accept => {
      const request = httpRequest(value.origin + "/mobile/rosters/supplied", {
        method: "POST", headers: { ...value.native, "Content-Length": 1_000_000 },
      }, incoming => { incoming.resume(); accept(incoming.statusCode!); });
      request.end("[]");
    });
    assert.equal(response, 400);
    assert.deepEqual(JSON.parse(await readFile(join(value.dataDirectory, "hosts.json"), "utf8")), [first]);
  } finally { await value.close(); }
});

test("replacing the fixed roster preserves removed hosts and retry deadlines", () => {
  let now = 1000;
  const retained: Set<string>[] = [];
  const retention = createOnlineRetention({ pinned: [first], now: () => now, caches: () => [{
    retryAt: address => address === first ? 10_000_000 : 0,
    prune: scope => retained.push(new Set(scope.hosts)),
  }] });
  retention.replacePinned([second]);
  retention.prune();
  assert(retained.at(-1)?.has(first));
  assert(retained.at(-1)?.has(second));
  now += 4_000_000;
  retention.prune();
  assert(retained.at(-1)?.has(first), "cooldown outlives ordinary retired retention");
  now = 10_000_001;
  retention.prune();
  assert(!retained.at(-1)?.has(first));
  assert(retained.at(-1)?.has(second));
});

test("a busy saved port falls back without interfering with the existing local app", async () => {
  const existing = await fixture();
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-port-fallback-"));
  let replacement: Awaited<ReturnType<typeof startAndroidRuntime>> | undefined;
  try {
    replacement = await startAndroidRuntime({ dataDirectory, directory, preferredPort: existing.port });
    assert.notEqual(replacement.port, existing.port);
    assert.notEqual(replacement.token, existing.token);
    assert.equal((await fetch(existing.origin + "/preview/connection.json", { headers: existing.headers })).status, 200);
    const origin = `http://127.0.0.1:${replacement.port}`;
    assert.equal((await fetch(origin + "/preview/connection.json", { headers: existing.headers })).status, 403);
    assert.equal((await fetch(origin + "/preview/connection.json", {
      headers: { Cookie: `${SESSION_COOKIE}=${replacement.token}` },
    })).status, 200);
    assert.deepEqual(JSON.parse(await readFile(join(dataDirectory, "runtime.json"), "utf8")), {
      port: replacement.port, token: replacement.token,
    });
  } finally {
    if (replacement) {
      replacement.server.closeAllConnections();
      await new Promise<void>(accept => replacement!.server.close(() => accept()));
    }
    await existing.close();
    await rm(dataDirectory, { recursive: true, force: true });
  }
});

test("copied self-contained runtime starts through Android-style path aliases without checkout, laptop or npm", async () => {
  const temporary = await mkdtemp(join(tmpdir(), "gridlens-apk-runtime-"));
  const packaged = join(temporary, "runtime");
  const privateDirectory = join(temporary, "private-data");
  await cp(resolve("dist/runtime"), packaged, { recursive: true });
  const alias = join(temporary, "android-path-alias");
  await symlink(packaged, alias, "dir");
  const child = spawn(process.execPath, [join(alias, "server.mjs"), privateDirectory, "0"], {
    cwd: temporary, env: { PATH: process.env.PATH }, stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "";
  child.stdout.on("data", chunk => { output += chunk; });
  child.stderr.on("data", chunk => { output += chunk; });
  try {
    let ready: { port: number; token: string } | undefined;
    for (let count = 0; count < 100 && !ready; count++) {
      try { ready = JSON.parse(await readFile(join(privateDirectory, "runtime.json"), "utf8")); }
      catch { await new Promise(accept => setTimeout(accept, 50)); }
    }
    assert(ready, `Packaged runtime failed to start: ${output}`);
    const origin = `http://127.0.0.1:${ready.port}`;
    assert.equal((await fetch(origin + "/")).status, 403);
    const headers = { Cookie: `${SESSION_COOKIE}=${ready.token}` };
    const page = await fetch(origin + "/", { headers });
    assert.equal(page.status, 200);
    assert.match(await page.text(), /Nosana Fleet/);
    const hosts = await fetch(origin + "/preview/hosts.json", { headers });
    assert.equal(hosts.status, 503, "missing roster stays an honest missing input and never triggers a sample fallback");
    assert.equal((await stat(join(privateDirectory, "runtime.json"))).mode & 0o777, 0o600);
    assert(!output.includes(ready.token));
    assert.match(await readFile(join(packaged, "web/preview/main.js"), "utf8"), /Country/);
    assert.match(await readFile(join(packaged, "web/preview/job-history.js"), "utf8"), /Source details/);
  } finally {
    if (child.exitCode === null) { child.kill("SIGTERM"); await once(child, "exit"); }
    await rm(temporary, { recursive: true, force: true });
  }
});
