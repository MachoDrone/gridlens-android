import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, readFile, writeFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { SavedFleets, parseUniqueJson } from "../runtime/saved-fleets.ts";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";
import type { RuntimeOptions } from "../runtime/server.ts";

const first = "1".repeat(32), second = "1".repeat(31) + "2", third = "1".repeat(31) + "3";

test("saved fleet creation, merge, selection and explicit removal persist independently with discovery dates", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-saved-"));
  let now = 1000;
  try {
    const store = await SavedFleets.load(directory, () => now);
    assert.deepEqual(store.list(), { schema: 1, selectedScenario: "network-discovery", fleets: [], error: null });
    const created = await store.mutate({ op: "create", name: "Customer A", addresses: [first, second] });
    const id = created.fleetId!;
    assert.equal(store.list().selectedScenario, `fleet:${id}`);
    now = 2000;
    await store.mutate({ op: "merge", id, addresses: [second, third] });
    assert.deepEqual(store.get(id)!.hosts, [
      { address: first, firstDiscoveredAtMs: 1000, lastDiscoveredAtMs: 1000 },
      { address: second, firstDiscoveredAtMs: 1000, lastDiscoveredAtMs: 2000 },
      { address: third, firstDiscoveredAtMs: 2000, lastDiscoveredAtMs: 2000 },
    ]);
    // No discoveries, including a failed rescan, never remove prior members.
    now = 3000;
    await store.mutate({ op: "merge", id, addresses: [] });
    assert.equal(store.get(id)!.hosts.length, 3);
    await store.mutate({ op: "rename", id, name: "Renamed" });
    await store.mutate({ op: "remove-hosts", id, addresses: [second] });
    const next = await SavedFleets.load(directory, () => now);
    assert.deepEqual(next.list(), store.list());
    assert.equal(next.get(id)!.name, "Renamed");
    assert.deepEqual(next.pinned(), [first, third]);
    assert.equal((await stat(join(directory, "discovered-fleets.json"))).mode & 0o777, 0o600);
    assert.equal((await stat(join(directory, "discovered-fleets.json.bak"))).mode & 0o777, 0o600);
    await next.mutate({ op: "select", scenario: "supplied" });
    assert.equal((await SavedFleets.load(directory)).list().selectedScenario, "supplied");
    await next.mutate({ op: "delete", id });
    assert.equal(next.list().fleets.length, 0);
    assert.equal(next.list().selectedScenario, "supplied");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("fleet mutations validate complete records and serialize concurrent writes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-saved-"));
  try {
    const store = await SavedFleets.load(directory, () => 1000);
    for (const body of [null, {}, { op: "create", name: "Empty", addresses: [] },
      { op: "create", name: "Bad", addresses: [first, first] }, { op: "create", name: "\n", addresses: [first] },
      { op: "create", name: "Bad", addresses: ["../../wallet.json"] },
      { op: "create", name: "Bad", addresses: [first], password: "never accepted" },
      { op: "select", scenario: "fleet:../../hosts.json" }]) {
      await assert.rejects(store.mutate(body));
      assert.equal(store.list().fleets.length, 0);
    }
    const entries = await Promise.all([store.mutate({ op: "create", name: "A", addresses: [first] }),
      store.mutate({ op: "create", name: "B", addresses: [second] })]);
    assert.equal(store.list().fleets.length, 2);
    assert.equal((await SavedFleets.load(directory)).list().fleets.length, 2);
    const id = entries[0]!.fleetId!;
    await assert.rejects(store.mutate({ op: "remove-hosts", id, addresses: [third] }));
    await assert.rejects(store.mutate({ op: "merge", id: "unknown", addresses: [third] }));
    await store.mutate({ op: "select", scenario: `fleet:${id}` });
    await store.mutate({ op: "delete", id });
    assert.equal(store.list().selectedScenario, "network-discovery");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("recover a validated previous revision without overwriting corrupt data until an explicit mutation", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-saved-"));
  try {
    const store = await SavedFleets.load(directory, () => 1000);
    const { fleetId: id } = await store.mutate({ op: "create", name: "Previous", addresses: [first] });
    await store.mutate({ op: "rename", id, name: "Newer" });
    const path = join(directory, "discovered-fleets.json");
    await writeFile(path, "interrupted external corruption");
    const recovered = await SavedFleets.load(directory, () => 2000);
    assert.match(recovered.list().error!, /recovered/);
    assert.equal(recovered.get(id!)!.name, "Previous");
    assert.equal(await readFile(path, "utf8"), "interrupted external corruption");
    await recovered.mutate({ op: "rename", id, name: "Recovered" });
    assert.equal((await SavedFleets.load(directory)).get(id!)!.name, "Recovered");
    await writeFile(path, "corrupt primary");
    await writeFile(path + ".bak", "corrupt backup");
    const unavailable = await SavedFleets.load(directory);
    assert.match(unavailable.list().error!, /preserved/);
    await assert.rejects(unavailable.mutate({ op: "create", name: "Do not overwrite", addresses: [second] }));
    assert.equal(await readFile(path, "utf8"), "corrupt primary");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

async function fixture(providers: NonNullable<RuntimeOptions["providers"]>) {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-saved-api-"));
  await writeFile(join(dataDirectory, "hosts.json"), JSON.stringify([first]));
  await writeFile(join(dataDirectory, "real200.json"), "[]");
  const runtime = await startAndroidRuntime({ dataDirectory, preferredPort: 0, directory: resolve("vendor/gridlens/dist"), providers });
  const origin = `http://127.0.0.1:${runtime.port}`;
  const headers = { Cookie: `${SESSION_COOKIE}=${runtime.token}` };
  const native = { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" };
  return { ...runtime, dataDirectory, origin, headers, native,
    get: (path: string) => fetch(origin + path, { headers }),
    mutate: (body: object) => fetch(origin + "/mobile/fleets", { method: "POST", headers: native, body: JSON.stringify(body) }),
    async close() { runtime.server.closeAllConnections(); await new Promise<void>(accept => runtime.server.close(() => accept())); await rm(dataDirectory, { recursive: true, force: true }); },
  };
}

test("mobile fleet API keeps native mutations separate from browser reads and permits only known saved rosters", async () => {
  const reads: string[][] = [];
  let onlineReads = 0;
  const value = await fixture({ now: () => 20_000,
    metadataProvider: async addresses => {
      reads.push([...addresses]);
      return { hosts: addresses.map(address => ({ address, values: null, fetchedAtMs: 1000, retained: true, error: null, marketError: null })) };
    },
    onlineProvider: { peek: () => { throw new Error("not requested"); }, read: async () => { onlineReads++; throw new Error("not requested"); } },
  });
  try {
    assert.equal((await fetch(value.origin + "/mobile/fleets")).status, 403);
    assert.equal((await fetch(value.origin + "/mobile/fleets", { method: "POST", headers: { ...value.headers, "Content-Type": "application/json" }, body: JSON.stringify({ op: "create", name: "A", addresses: [second] }) })).status, 403);
    assert.equal((await value.get("/mobile/fleets?path=/tmp/arbitrary")).status, 403);
    const initial = await (await value.get("/mobile/fleets")).json();
    assert.equal(initial.selectedScenario, "network-discovery");
    for (const route of ["hosts", "metadata", "node-info", "jobs", "queue", "sol", "nos", "staking"]) {
      const response = await value.get(`/preview/${route}.json?scenario=network-discovery`);
      assert.equal(response.status, 200, route);
    }
    assert.deepEqual(reads, []);
    assert.equal(onlineReads, 0);
    const created = await (await value.mutate({ op: "create", name: "Customer A", addresses: [second] })).json();
    const id = created.fleetId as string;
    assert.match(id, /^[0-9a-f-]{36}$/);
    assert.equal(created.selectedScenario, `fleet:${id}`);
    assert.deepEqual(reads, [], "creation does not refresh or scan");
    const query = `?scenario=${encodeURIComponent(`fleet:${id}`)}`;
    assert.deepEqual((await (await value.get("/preview/hosts.json" + query)).json()).addresses, [second]);
    const measured = await (await value.get("/preview/metadata.json" + query)).json();
    assert.equal(measured.hosts[0].fetchedAtMs, 1000, "discovery time must never become metric freshness");
    assert.equal(created.fleets[0].hosts[0].lastDiscoveredAtMs, 20_000);
    assert.deepEqual(reads, [[second]]);
    assert.equal((await value.get("/preview/metadata.json?scenario=fleet:00000000-0000-4000-8000-000000000000")).status, 404);
    assert.equal((await value.get("/preview/metadata.json?scenario=fleet:../../hosts.json")).status, 404);
    assert.deepEqual(reads, [[second]]);
    assert.deepEqual(JSON.parse(await readFile(join(value.dataDirectory, "hosts.json"), "utf8")), [first]);
    await value.mutate({ op: "merge", id, addresses: [third] });
    assert.deepEqual((await (await value.get("/preview/hosts.json" + query)).json()).addresses, [second, third]);
    await (await value.get("/preview/metadata.json" + query)).json();
    assert.deepEqual(reads, [[second], [second, third]]);
    await value.mutate({ op: "delete", id });
    assert.equal((await value.get("/preview/metadata.json" + query)).status, 404);
    assert.equal((await (await value.get("/mobile/fleets")).json()).selectedScenario, "network-discovery");
    assert.equal(onlineReads, 0);
  } finally { await value.close(); }
});

test("saved fleet revisions cancel old streams and independent fleets cannot share a wrong roster", async () => {
  let aborted = 0;
  const selections: string[][] = [];
  const value = await fixture({ metadataProvider: (addresses, signal, update) => new Promise(resolve => {
    selections.push([...addresses]);
    update?.({ hosts: addresses.map(address => ({ address, values: null, fetchedAtMs: 1000, retained: true, error: null, marketError: null })) });
    signal?.addEventListener("abort", () => { aborted++; resolve({ hosts: [] }); }, { once: true });
  }) });
  try {
    const a = await (await value.mutate({ op: "create", name: "A", addresses: [first] })).json();
    const b = await (await value.mutate({ op: "create", name: "B", addresses: [second] })).json();
    const open = (id: string) => fetch(`${value.origin}/preview/metadata.json?scenario=fleet:${id}`, { headers: { ...value.headers, Accept: "application/x-ndjson" } });
    const responseA = await open(a.fleetId);
    const responseB = await open(b.fleetId);
    const readerA = responseA.body!.getReader();
    const readerB = responseB.body!.getReader();
    assert.match(new TextDecoder().decode((await readerA.read()).value), new RegExp(first));
    assert.match(new TextDecoder().decode((await readerB.read()).value), new RegExp(second));
    await value.mutate({ op: "merge", id: a.fleetId, addresses: [third] });
    await assert.rejects(readerA.read());
    for (let attempt = 0; aborted === 0 && attempt < 30; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(aborted, 1);
    assert.deepEqual(selections, [[first], [second]]);
    await readerB.cancel();
  } finally { await value.close(); }
});

test("duplicate JSON keys and escaped duplicate keys are rejected before mutations", () => {
  for (const text of ['{"op":"create","op":"delete"}', '{"op":"create","\\u006fp":"delete"}',
    '{"fleets":[{"name":"first","name":"second"}]}']) assert.throws(() => parseUniqueJson(text));
  assert.deepEqual(parseUniqueJson('{"a":[{"a":1},{"a":2}],"b":"{\\\"a\\\":1}"}'), { a: [{ a: 1 }, { a: 2 }], b: '{"a":1}' });
});

test("total saved capacity rejects a complete excess roster and preserves prior revisions", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-saved-capacity-"));
  const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  const many = Array.from({ length: 10_000 }, (_, index) => {
    let value = 65536 + index, suffix = "";
    while (value > 0) { suffix = alphabet[value % 58] + suffix; value = Math.floor(value / 58); }
    return "1".repeat(29) + suffix;
  });
  try {
    const store = await SavedFleets.load(directory, () => 1000);
    await store.mutate({ op: "create", name: "First capacity fixture", addresses: many });
    await store.mutate({ op: "create", name: "Second capacity fixture", addresses: many });
    await assert.rejects(store.mutate({ op: "create", name: "Excess", addresses: [first] }), /capacity/);
    assert.equal(store.list().fleets.length, 2);
    assert.equal((await SavedFleets.load(directory)).list().fleets.length, 2);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("malformed persisted selection is rejected and recovery never selects an unknown roster", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-saved-selection-"));
  try {
    const store = await SavedFleets.load(directory, () => 1000);
    const created = await store.mutate({ op: "create", name: "Known", addresses: [first] });
    await store.mutate({ op: "rename", id: created.fleetId, name: "Known current" });
    const path = join(directory, "discovered-fleets.json");
    const corrupt = JSON.parse(await readFile(path, "utf8"));
    corrupt.selectedScenario = "fleet:../../hosts.json";
    await writeFile(path, JSON.stringify(corrupt));
    const recovered = await SavedFleets.load(directory);
    assert.equal(recovered.list().selectedScenario, `fleet:${created.fleetId}`);
    assert.match(recovered.list().error!, /recovered/);
    await rm(path + ".bak");
    const unavailable = await SavedFleets.load(directory);
    assert.equal(unavailable.list().selectedScenario, "network-discovery");
    assert.equal(unavailable.list().fleets.length, 0);
    assert.match(unavailable.list().error!, /preserved/);
    assert.equal(await readFile(path, "utf8"), JSON.stringify(corrupt));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("a new runtime restores selected fleets and imports without discovery or public-reader dispatch", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-saved-restart-"));
  let reads = 0;
  const options = { dataDirectory: directory, preferredPort: 0, directory: resolve("vendor/gridlens/dist"),
    providers: { metadataProvider: async () => { reads++; return { hosts: [] }; } } };
  let runtime: Awaited<ReturnType<typeof startAndroidRuntime>> | undefined;
  const close = async () => {
    runtime!.server.closeAllConnections();
    await new Promise<void>(resolve => runtime!.server.close(() => resolve()));
    runtime = undefined;
  };
  try {
    await writeFile(join(directory, "hosts.json"), JSON.stringify([first]));
    await writeFile(join(directory, "real200.json"), "[]");
    runtime = await startAndroidRuntime(options);
    const response = await fetch(`http://127.0.0.1:${runtime.port}/mobile/fleets`, { method: "POST",
      headers: { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" },
      body: JSON.stringify({ op: "create", name: "Survives upgrade", addresses: [second] }) });
    const created = await response.json();
    const formerToken = runtime.token;
    await close();
    runtime = await startAndroidRuntime(options);
    assert.notEqual(runtime.token, formerToken);
    const origin = `http://127.0.0.1:${runtime.port}`;
    const headers = { Cookie: `${SESSION_COOKIE}=${runtime.token}` };
    const listing = await (await fetch(origin + "/mobile/fleets", { headers })).json();
    assert.equal(listing.selectedScenario, `fleet:${created.fleetId}`);
    const known = await (await fetch(origin + "/preview/hosts.json?scenario=" + listing.selectedScenario, { headers })).json();
    assert.deepEqual(known.addresses, [second]);
    assert.deepEqual((await (await fetch(origin + "/preview/hosts.json?scenario=supplied", { headers })).json()).addresses, [first]);
    assert.equal(reads, 0);
  } finally { if (runtime) await close(); await rm(directory, { recursive: true, force: true }); }
});

test("saved timestamps outside the JavaScript Date range fail closed before they can reach the UI", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-saved-date-"));
  try {
    const store = await SavedFleets.load(directory, () => Number.MAX_SAFE_INTEGER);
    await assert.rejects(store.mutate({ op: "create", name: "Invalid clock", addresses: [first] }));
    assert.equal(store.list().fleets.length, 0);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("dated PC metadata survives upgrade, preserves old reads and clears ambiguous mappings", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-"));
  let now = 1000;
  try {
    const store = await SavedFleets.load(directory, () => now);
    const { fleetId: id } = await store.mutate({ op: "create", name: "Existing fleet", addresses: [first, second] });
    const metadata = { pcId: "a".repeat(64), pcName: "pc-fixture", ipAddresses: ["192.0.2.20", "fd00:0:0:0:0:0:0:1"], observedAtMs: 1500 };
    now = 2000;
    await store.mutate({ op: "merge", id, addresses: [first], pcMetadata: [{ address: first, ...metadata }] });
    const saved = store.get(id!)!;
    assert.equal(saved.hosts[0]!.pcMetadata!.pcName, "pc-fixture");
    assert.equal(saved.hosts[0]!.pcMetadata!.observedAtMs, 1500);
    assert.deepEqual(saved.hosts[0]!.pcMetadata!.ipAddresses, ["192.0.2.20", "fd00::1"]);
    assert.equal(saved.hosts[1]!.pcMetadata, undefined);
    now = 3000;
    await store.mutate({ op: "merge", id, addresses: [first] });
    assert.equal(store.get(id!)!.hosts[0]!.pcMetadata!.observedAtMs, 1500, "missing metadata must not redate old evidence");
    assert.deepEqual((await SavedFleets.load(directory)).list(), store.list());
    await store.mutate({ op: "merge", id, addresses: [first], pcMetadata: [{ address: first, pcId: null, pcName: null, ipAddresses: [], observedAtMs: 2500 }] });
    assert.equal(store.get(id!)!.hosts[0]!.pcMetadata, undefined, "ambiguous ownership clears the prior mapping");
    assert.equal(store.get(id!)!.hosts.length, 2);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("PC metadata rejects secrets, unsafe names, invalid addresses, unknown hosts and future times atomically", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-invalid-"));
  try {
    const store = await SavedFleets.load(directory, () => 1000);
    const good = { address: first, pcId: "a".repeat(64), pcName: "pc-fixture", ipAddresses: ["192.0.2.20"], observedAtMs: 900 };
    for (const change of [{ password: "forbidden" }, { pcName: "<script>" }, { pcId: "unverified" },
      { ipAddresses: ["example.com"] }, { ipAddresses: ["127.0.0.1"] }, { ipAddresses: ["::1"] },
      { ipAddresses: ["::ffff:127.0.0.1"] }, { ipAddresses: ["192.0.2.20", "192.0.2.20"] },
      { address: second }, { observedAtMs: 1001 }]) {
      await assert.rejects(store.mutate({ op: "create", name: "Fixture", addresses: [first], pcMetadata: [{ ...good, ...change }] }));
      assert.equal(store.list().fleets.length, 0);
    }
  } finally { await rm(directory, { recursive: true, force: true }); }
});
