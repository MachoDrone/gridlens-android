import assert from "node:assert/strict";
import { test } from "node:test";
import { createHash } from "node:crypto";
import { X509Certificate } from "node:crypto";
import { execFileSync } from "node:child_process";
import { createServer } from "node:https";
import { mkdtemp, readFile, rm, writeFile, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { SharedCache, SharedCacheError, requestSharedHttps, validateSharedTrust } from "../runtime/shared-cache.ts";
import type { SharedTransport, SharedTransportRequest } from "../runtime/shared-cache.ts";
import { emptyFleetSnapshots } from "../vendor/gridlens/src/server/snapshot-store.ts";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";

const first = "1".repeat(32), second = "1".repeat(31) + "2";
const now = 1_800_000_000_000;
const trust = { origin: "https://backend.test:8789", serverId: "generated-server", fingerprint: "a".repeat(64) };
const configuration = { ...trust, name: "Fixture", deviceName: "Generated phone", code: "generated-code" };
const context = { scopeId: "generated-fleet", rosterRevision: "revision-1", addresses: [first] };
const secret = "generated-fixture-device-token";
function backend() {
  const calls: SharedTransportRequest[] = [];
  let mode: "connected" | "offline" | "denied" | "malformed" | "changed" | "wait" = "connected";
  const snapshots = emptyFleetSnapshots(context.addresses);
  snapshots.metadata.hosts[0] = { address: first, fetchedAtMs: now - 90_000, retained: false, error: null, marketError: null,
    values: { status: "PREMIUM", marketAddress: null, marketLabel: "Fixture GPU", ram: 32, disk: 100, cpu: "Generated CPU", cuda: null, nvidia: null, currentNode: null, system: null } };
  const transport: SharedTransport = async input => {
    calls.push(input);
    if (mode === "offline") throw new SharedCacheError("unavailable");
    if (input.path === "/api/shared/pair") return { status: 200, value: { version: 1, serverId: trust.serverId, deviceId: "generated-device", token: secret, role: "viewer", context, capabilities: ["fleet-snapshots-v1"] } };
    assert.equal(input.token, secret); assert.deepEqual(input.context, context);
    if (mode === "denied") return { status: 403, value: { error: "revoked" } };
    if (mode === "wait") return { status: 429, value: { error: "wait" }, retryAtMs: now + 120_000 };
    if (input.path === "/api/shared/status") return { status: 200, value: { version: 1, serverId: trust.serverId, serverTimeMs: now,
      context: mode === "changed" ? { ...context, rosterRevision: "revision-2", addresses: [second] } : context,
      capabilities: ["fleet-snapshots-v1"], providerWaits: [{ provider: "jobs", retryAtMs: now + 60_000 }], mode: "shared-cache" } };
    if (input.path === "/api/shared/observations") return { status: 200, value: { version: 1, serverId: trust.serverId, scopeId: context.scopeId, rosterRevision: context.rosterRevision, associations: [], observations: [], networks: [] } };
    if (mode === "malformed") return { status: 200, value: { serverTimeMs: now, hosts: [{ address: second }] } };
    const resource = input.path.split("/").at(-1)!.split(".")[0] as keyof typeof snapshots;
    return { status: 200, value: { ...snapshots[resource], serverTimeMs: now } };
  };
  return { calls, transport, snapshots, mode(value: typeof mode) { mode = value; } };
}

test("pairing binds identity and fleet namespace, preserves dated cache and waits across reopen and explicit standalone", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-shared-cache-")), remote = backend();
  try {
    const store = await SharedCache.load(directory, remote.transport, () => now);
    const id = await store.pair(configuration);
    assert.equal(store.namespace(id), JSON.stringify([trust.serverId, context.scopeId, context.rosterRevision]));
    const reading = await store.read(id, "metadata");
    assert.equal((reading.hosts as { fetchedAtMs: number }[])[0]!.fetchedAtMs, now - 90_000);
    assert.equal(store.standaloneWait("jobs"), now + 60_000);
    assert(!JSON.stringify(store.list()).includes(secret));
    assert.equal((await stat(join(directory, "shared-cache.json"))).mode & 0o777, 0o600);
    remote.mode("offline");
    const retained = await store.read(id, "metadata");
    assert.equal(retained.sharedRetained, true); assert.equal(retained.serverTimeMs, undefined);
    assert.equal((retained.hosts as { fetchedAtMs: number; retained: boolean }[])[0]!.fetchedAtMs, now - 90_000);
    assert.equal((retained.hosts as { retained: boolean }[])[0]!.retained, true);
    store.close();
    const reopened = await SharedCache.load(directory, remote.transport, () => now + 1000);
    assert.equal(reopened.namespace(id), JSON.stringify([trust.serverId, context.scopeId, context.rosterRevision]));
    assert.equal(reopened.standaloneWait("jobs"), now + 60_000);
    assert.equal(await reopened.standalone(id), "network-discovery");
    await reopened.forget(id);
    assert.equal(reopened.standaloneWait("jobs"), now + 60_000, "forgetting a token cannot erase a known wait");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("denial, malformed responses, identity/revision change and provider pauses retain data without fallback", async () => {
  for (const mode of ["denied", "malformed", "changed", "wait"] as const) {
    const directory = await mkdtemp(join(tmpdir(), "gridlens-shared-rejection-")), remote = backend();
    try {
      const store = await SharedCache.load(directory, remote.transport, () => now), id = await store.pair(configuration);
      await store.read(id, "metadata"); remote.mode(mode);
      const retained = await store.read(id, "metadata");
      assert.equal(retained.sharedRetained, true);
      assert.deepEqual(store.context(id).addresses, [first]);
      assert.equal((retained.hosts as { address: string }[])[0]!.address, first);
      const statuses = { denied: "access-denied", malformed: "invalid-response", changed: "roster-changed", wait: "provider-wait" };
      assert.equal(store.list()[0]!.shared.status, statuses[mode]);
      if (mode === "wait") { const count = remote.calls.length; await store.read(id, "metadata"); assert.equal(remote.calls.length, count, "received backend pause prevents retry"); }
    } finally { await rm(directory, { recursive: true, force: true }); }
  }
});

test("malformed persisted connection is preserved and blocks implicit recovery and public dispatch", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-shared-invalid-"));
  try {
    await writeFile(join(directory, "shared-cache.json"), '{"version":1,"links":"invalid"}');
    const store = await SharedCache.load(directory, backend().transport, () => now);
    assert.match(store.warning!, /preserved/); assert(store.standaloneWait("metadata") > now);
    await assert.rejects(store.pair(configuration));
    assert.equal(await readFile(join(directory, "shared-cache.json"), "utf8"), '{"version":1,"links":"invalid"}');
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("PC-only shared network readings require exact verified associations and never become local SSH authority", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-shared-associations-")), remote = backend();
  const binding = { pcId: "a".repeat(64), runtime: "docker", wrapperId: null, nodeContainerId: "b".repeat(64) };
  let invalid = false;
  const network = { pcId: binding.pcId, receivedAtMs: now, network: { schema: 1, observedAtMs: now - 1000, status: "ok", source: "pc-egress-ipify",
    publicIp: "8.8.8.8", publicObservedAtMs: now - 1000, privateIp: "10.20.30.40", privateObservedAtMs: now - 1000 } };
  const transport: SharedTransport = async request => request.path === "/api/shared/observations" ? { status: 200, value: {
    version: 1, serverId: trust.serverId, scopeId: context.scopeId, rosterRevision: context.rosterRevision,
    associations: [{ address: first, binding: invalid ? { ...binding, pcId: "c".repeat(64) } : binding, name: "Generated PC", sshFingerprint: "SHA256:" + "A".repeat(43), verifiedAtMs: now - 5000, privateIp: "10.1.2.3", gpuUuids: [] }],
    observations: [], networks: [network],
  } } : remote.transport(request);
  try {
    const store = await SharedCache.load(directory, transport, () => now), id = await store.pair(configuration);
    const result = await store.observations(id);
    assert.equal(result.pcNetworks[0]!.network.privateIp, "10.20.30.40", "verified ingress is not substituted for measured private IP");
    assert.equal(store.list()[0]!.shared.pcs[0]!.name, "Generated PC");
    assert(!JSON.stringify(store.list()).includes("credential"));
    invalid = true;
    const retained = await store.observations(id);
    assert.equal(retained.error, "invalid-response");
    assert.equal(retained.pcNetworks[0]!.network.observedAtMs, now - 1000);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("pairing rejects a server identity different from the terminal's expected identity", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-shared-pair-id-")), remote = backend();
  try {
    const store = await SharedCache.load(directory, remote.transport, () => now);
    await assert.rejects(store.pair({ ...configuration, serverId: "other-generated-server" }));
    assert.deepEqual(store.list(), []);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("a fast phone clock cannot shorten a received backend provider wait during standalone switch or restart", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-shared-clock-")), remote = backend(), phoneNow = now + 3_600_000;
  try {
    const store = await SharedCache.load(directory, remote.transport, () => phoneNow), id = await store.pair(configuration);
    await store.read(id, "metadata");
    assert.equal(store.standaloneWait("jobs"), phoneNow + 60_000);
    await store.standalone(id); store.close();
    const reopened = await SharedCache.load(directory, remote.transport, () => phoneNow + 1000);
    assert.equal(reopened.standaloneWait("jobs"), phoneNow + 60_000);
    assert.equal((await reopened.read(id, "metadata")).hosts && reopened.context(id).addresses[0], first);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("GPU index stays bounded and explicit history reads bind source identity while retaining failed details", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-shared-gpu-")), remote = backend();
  const binding = { pcId: "a".repeat(64), runtime: "docker", wrapperId: null, nodeContainerId: "b".repeat(64) };
  const gpuUuid = "GPU-00000000-0000-0000-0000-000000000001";
  const gpu = { schema: 2, address: first, observedAtMs: now - 1000, status: "ok", gpuUuids: [gpuUuid], intervalSeconds: 15,
    fromAtMs: now - 60_000, toAtMs: now - 1000, truncated: false, clockTrusted: true, samples: [[now - 1000, 0, 1, 1, null, null, null, null]] };
  let details = 0, failed = false;
  const transport: SharedTransport = async request => {
    if (failed) throw new SharedCacheError("unavailable");
    if (request.path === "/api/shared/gpu-history") {
      details++; assert.deepEqual(request.body, { address: first, fromAtMs: gpu.fromAtMs, toAtMs: gpu.toAtMs });
      return { status: 200, value: { version: 1, serverId: trust.serverId, scopeId: context.scopeId, rosterRevision: context.rosterRevision, address: first, gpu } };
    }
    if (request.path === "/api/shared/observations") return { status: 200, value: { version: 1, serverId: trust.serverId, scopeId: context.scopeId, rosterRevision: context.rosterRevision,
      associations: [{ address: first, binding, name: "Generated PC", sshFingerprint: "SHA256:" + "A".repeat(43), verifiedAtMs: now - 5000, privateIp: null, gpuUuids: [gpuUuid] }],
      observations: [{ address: first, binding, receivedAtMs: now, gpu: { ...gpu, samples: [] } }], networks: [] } };
    return remote.transport(request);
  };
  try {
    const store = await SharedCache.load(directory, transport, () => now), id = await store.pair(configuration);
    const index = await store.observations(id); assert.equal(details, 0, "routine index does not request history");
    assert.equal((index.hosts![0] as { gpu: { sampleCount: number | null } }).gpu.sampleCount, null, "absent server summary count stays unknown");
    const detail = await store.observations(id, first); assert.deepEqual(detail.host?.gpu?.samples, gpu.samples); assert.equal(details, 1);
    failed = true; store.close();
    const reopened = await SharedCache.load(directory, transport, () => now + 1000), retained = await reopened.observations(id, first);
    assert.deepEqual(retained.host?.gpu?.samples, gpu.samples); assert.equal(retained.host?.gpu?.observedAtMs, now - 1000); assert.equal(details, 1);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("production HTTPS requires trusted CA, matching host and paired certificate, and sends the bound context", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-generated-tls-"));
  try {
    execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", join(directory, "key.pem"), "-out", join(directory, "cert.pem"),
      "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"], { stdio: "ignore" });
    const ca = await readFile(join(directory, "cert.pem"), "utf8"), key = await readFile(join(directory, "key.pem"));
    const fingerprint = createHash("sha256").update(new X509Certificate(ca).raw).digest("hex");
    let requests = 0;
    const server = createServer({ key, cert: ca }, (request, response) => {
      requests++; assert.equal(request.headers["x-gridlens-scope"], context.scopeId); assert.equal(request.headers["x-gridlens-roster-revision"], context.rosterRevision);
      response.writeHead(200, { "Content-Type": "application/json" }); response.end('{"version":1}');
    });
    await new Promise<void>(accept => server.listen(0, "127.0.0.1", accept));
    const address = server.address(); assert(address && typeof address !== "string");
    const trusted = { origin: `https://127.0.0.1:${address.port}`, serverId: "generated", fingerprint, caPem: ca };
    try {
      assert.equal((await requestSharedHttps({ trust: trusted, context, path: "/api/shared/status" })).status, 200);
      await assert.rejects(requestSharedHttps({ trust: { ...trusted, fingerprint: "0".repeat(64) }, context, path: "/api/shared/status" }));
      const { caPem: _ca, ...untrusted } = trusted;
      await assert.rejects(requestSharedHttps({ trust: untrusted, context, path: "/api/shared/status" }));
      assert.equal(requests, 1, "neither an untrusted certificate nor changed pin reaches HTTP");
    } finally { server.closeAllConnections(); await new Promise<void>(accept => server.close(() => accept())); }
    for (const bad of [{ ...trusted, origin: "http://127.0.0.1:8789" }, { ...trusted, origin: "https://user:pass@localhost" }, { ...trusted, caPem: "-----BEGIN PRIVATE KEY-----" }]) assert.throws(() => validateSharedTrust(bad));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("phone-local API selects a separate shared fleet, preserves local rosters and starts no duplicate providers", async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-shared-runtime-")), remote = backend();
  await writeFile(join(dataDirectory, "hosts.json"), JSON.stringify([second]));
  await writeFile(join(dataDirectory, "real200.json"), "[]");
  let reads = 0;
  const runtime = await startAndroidRuntime({ dataDirectory, directory: resolve("vendor/gridlens/dist"), preferredPort: 0,
    sharedTransport: remote.transport, providers: { now: () => now, metadataProvider: async addresses => { reads++; return emptyFleetSnapshots(addresses).metadata; } } });
  const origin = `http://127.0.0.1:${runtime.port}`, native = { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" }, browser = { Cookie: `${SESSION_COOKIE}=${runtime.token}` };
  const post = async (path: string, body: object, headers: Record<string, string> = native) => fetch(origin + path, { method: "POST", headers, body: JSON.stringify(body) });
  try {
    const local = await (await post("/mobile/fleets", { op: "create", name: "Local", addresses: [second] })).json();
    const localId = local.fleetId;
    assert.equal((await post("/mobile/shared", { op: "pair", configuration }, { ...browser, "Content-Type": "application/json" })).status, 403);
    const paired = await (await post("/mobile/shared", { op: "pair", configuration: { ...configuration, localFleetId: localId } })).json();
    const scenario = paired.selectedScenario as string;
    assert.notEqual(scenario, `fleet:${localId}`);
    const listing = await (await fetch(origin + "/mobile/fleets", { headers: browser })).json();
    assert.equal(listing.fleets.length, 2); assert(!JSON.stringify(listing).includes(secret));
    assert.deepEqual(listing.fleets.find((fleet: { id: string }) => fleet.id === localId).hosts.map((host: { address: string }) => host.address), [second]);
    const roster = await (await fetch(origin + `/preview/hosts.json?scenario=${scenario}`, { headers: browser })).json();
    assert.deepEqual(roster.addresses, [first]);
    const reading = await (await fetch(origin + `/preview/metadata.json?scenario=${scenario}`, { headers: browser })).json();
    assert.equal(reading.hosts[0].fetchedAtMs, now - 90_000); assert.equal(reads, 0);
    remote.mode("denied");
    const retained = await (await fetch(origin + `/preview/metadata.json?scenario=${scenario}`, { headers: browser })).json();
    assert.equal(retained.sharedRetained, true); assert.equal(reads, 0);
    const standalone = await (await post("/mobile/shared", { op: "standalone", id: scenario.slice(6) })).json();
    assert.equal(standalone.selectedScenario, `fleet:${localId}`);
    const waited = await fetch(origin + `/preview/jobs.json?scenario=fleet:${localId}`, { headers: browser });
    assert.equal(waited.status, 429);
    await (await fetch(origin + `/preview/metadata.json?scenario=fleet:${localId}`, { headers: browser })).json();
    assert.equal(reads, 1, "only explicit standalone selection starts local public reading");
    await post("/mobile/fleets", { op: "delete", id: localId });
    const afterDeletion = await (await post("/mobile/shared", { op: "standalone", id: scenario.slice(6) })).json();
    assert.equal(afterDeletion.selectedScenario, "network-discovery", "an explicitly removed local counterpart is not recreated or replaced by the remote roster");
    assert.deepEqual(JSON.parse(await readFile(join(dataDirectory, "hosts.json"), "utf8")), [second]);
  } finally { runtime.server.closeAllConnections(); await new Promise<void>(accept => runtime.server.close(() => accept())); await rm(dataDirectory, { recursive: true, force: true }); }
});
