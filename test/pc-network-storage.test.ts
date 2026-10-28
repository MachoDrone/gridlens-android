import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { PcObservations } from "../runtime/pc-observations.ts";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";
import { networkView, parsePcNetwork, parsePcNetworkRecord, parsePcNetworkUpdate } from "../vendor/gridlens/src/fleet/pc-observations.ts";
import type { PcNetworkObservation, PcUpdate } from "../vendor/gridlens/src/fleet/pc-observations.ts";

const AT = Date.UTC(2026, 9, 28, 12), address = "1".repeat(32), second = "1".repeat(31) + "2";
const pcId = "a".repeat(64), otherPc = "d".repeat(64);
const fleet = "00000000-0000-4000-8000-000000000001", otherFleet = "00000000-0000-4000-8000-000000000002";
const binding = { pcId, runtime: "docker" as const, wrapperId: null, nodeContainerId: "b".repeat(64) };
const network = (at = AT): PcNetworkObservation => ({ schema: 1, source: "pc-egress-ipify", observedAtMs: at, status: "ok",
  publicIp: "8.8.8.8", publicObservedAtMs: at, privateIp: "192.168.1.101", privateObservedAtMs: at });
const failed = (at = AT + 1000): PcNetworkObservation => ({ schema: 1, source: "pc-egress-ipify", observedAtMs: at, status: "unavailable",
  publicIp: null, publicObservedAtMs: null, privateIp: null, privateObservedAtMs: null,
  publicFailure: "http-refusal", privateFailure: "interface-read-failure", httpStatus: 429, retryAtMs: at + 3_600_000 });
const host = (): PcUpdate => ({ address, binding, rewards: { schema: 1, address, observedAtMs: AT, status: "ok", amount: "12.345678" },
  monitoring: { status: "binding-changed", checkedAtMs: AT },
  gpu: { schema: 2, address, observedAtMs: AT, status: "ok", gpuUuids: ["GPU-00000000-1111-2222-3333-444444444444"],
    intervalSeconds: 15, fromAtMs: AT, toAtMs: AT, truncated: false, clockTrusted: true, samples: [[AT, 0, 15, 1, 0, 0, 0, 0]] } });
const path = (directory: string) => join(directory, "pc-observations", `${fleet}--${pcId}.network.json`);

test("network-only contracts accept bounded failures while rejecting injected authority and contradictory fields", () => {
  assert.deepEqual(parsePcNetworkUpdate({ pcId, network: network() }), { pcId, network: network() });
  assert.deepEqual(parsePcNetwork(failed()), failed());
  for (const publicFailure of ["missing-curl", "dns", "connection", "timeout", "tls", "http-refusal", "invalid-response"])
    assert.equal(parsePcNetwork({ ...failed(), httpStatus: undefined, publicFailure }).publicFailure, publicFailure);
  for (const value of [
    { pcId, network: network(), binding }, { pcId, network: network(), address },
    { pcId, network: network(), rewards: host().rewards }, { pcId: "../../private", network: network() },
  ]) assert.throws(() => parsePcNetworkUpdate(value));
  for (const change of [{ publicFailure: "stderr secret" }, { privateFailure: "stderr secret" }, { httpStatus: 600 }, { httpStatus: 99 },
    { httpStatus: "403" }, { httpStatus: 403.1 }, { retryAtMs: -1 }, { retryAtMs: 0 }, { retryAtMs: 8_640_000_000_000_001 }, { stderr: "private" }])
    assert.throws(() => parsePcNetwork({ ...failed(), ...change }));
  assert.throws(() => parsePcNetwork({ ...network(), publicFailure: "timeout" }));
  assert.throws(() => parsePcNetwork({ ...network(), privateFailure: "interface-read-failure" }));
  assert.throws(() => parsePcNetwork({ ...network(), httpStatus: 200 }));
  assert.throws(() => parsePcNetworkRecord({ pcId, network: network(), receivedAtMs: AT, command: "private" }));
  for (const [field, valid] of [["source", "pc-egress-ipify"], ["source", "node-egress-ipify"], ["status", "unavailable"],
    ["publicFailure", "dns"], ["privateFailure", "interface-read-failure"]] as const) {
    for (const value of [[valid], [[valid]], null, 1, true, { toString: () => valid }]) {
      const invalid = { ...failed(), httpStatus: undefined, [field]: value };
      assert.throws(() => parsePcNetwork(invalid), Error, `${field} must be a literal string`);
      assert.throws(() => parsePcNetworkUpdate({ pcId, network: invalid }));
      assert.throws(() => parsePcNetworkRecord({ pcId, network: invalid, receivedAtMs: AT }));
    }
  }
});

test("per-PC network persistence leaves node bindings, reward/GPU history and status byte-identical", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-network-store-"));
  let now = AT;
  try {
    const store = await PcObservations.load(directory, () => now);
    await store.update(fleet, [address], [host()]);
    const before = store.get(fleet, address), hostPath = join(directory, "pc-observations", `${fleet}--${address}.jsonl`);
    const bytes = await readFile(hostPath);
    await store.update(fleet, [address], [{ pcId, network: network() }], [pcId]);
    assert.deepEqual(store.get(fleet, address), before);
    assert.deepEqual(await readFile(hostPath), bytes);
    assert.equal(store.getNetwork(otherFleet, pcId), undefined);
    assert.equal((await stat(path(directory))).mode & 0o777, 0o600);
    now += 1000;
    await store.update(fleet, [address], [{ pcId, network: failed() }], [pcId]);
    let saved = store.getNetwork(fleet, pcId)!;
    assert.equal(saved.network.publicFailure, "http-refusal");
    assert.equal(saved.network.privateFailure, "interface-read-failure");
    assert.equal(saved.network.retryAtMs, failed().retryAtMs);
    assert.equal(networkView(saved, now).publicObservedAtMs, AT);
    assert.equal(networkView(saved, now).publicRetained, true);
    const failedBytes = await readFile(path(directory));
    const reopened = await PcObservations.load(directory, () => now + 500_000);
    assert.deepEqual(reopened.getNetwork(fleet, pcId), saved);
    assert.deepEqual(await readFile(path(directory)), failedBytes);
    assert.deepEqual(reopened.get(fleet, address), before);
    now += 1000;
    await store.update(fleet, [address], [{ pcId, network: network(now) }], [pcId]);
    saved = store.getNetwork(fleet, pcId)!;
    assert.equal(saved.network.retryAtMs, failed().retryAtMs, "a later success cannot erase a longer provider deadline");
    await store.update(fleet, [address], [{ pcId, network: network() }], [pcId]);
    assert.equal(store.getNetwork(fleet, pcId)!.network.observedAtMs, now, "old delivery cannot roll back source evidence");
    assert.deepEqual(await readFile(hostPath), bytes);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("new PC observations retain legacy node-egress evidence and source labels without rewriting old journals", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-network-legacy-"));
  try {
    const store = await PcObservations.load(directory, () => AT + 1000);
    await store.update(fleet, [address], [{ address, binding, network: { ...network(), source: "node-egress-ipify" } }]);
    const hostPath = join(directory, "pc-observations", `${fleet}--${address}.jsonl`), bytes = await readFile(hostPath);
    await store.update(fleet, [address], [{ pcId, network: failed() }], [pcId]);
    const value = store.getNetwork(fleet, pcId)!;
    assert.equal(value.network.source, "pc-egress-ipify");
    assert.equal(value.lastGoodNetwork!.source, "node-egress-ipify");
    assert.equal(value.lastGoodNetwork!.publicObservedAtMs, AT);
    assert.equal(networkView(value, AT + 1000).publicIp, "8.8.8.8");
    assert.deepEqual(await readFile(hostPath), bytes);
    assert.deepEqual((await PcObservations.load(directory)).getNetwork(fleet, pcId), value);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("PC network source timestamps survive independent failures, replay and clock-invalid observations", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-network-times-"));
  let now = AT;
  try {
    const store = await PcObservations.load(directory, () => now);
    const send = (value: PcNetworkObservation) => store.update(fleet, [address], [{ pcId, network: value }], [pcId]);
    await send(network());
    now += 1000;
    await send({ ...network(now), publicIp: null, publicObservedAtMs: null, status: "unavailable", publicFailure: "timeout" });
    let view = networkView(store.getNetwork(fleet, pcId), now);
    assert.equal(view.publicObservedAtMs, AT); assert.equal(view.publicRetained, true);
    assert.equal(view.privateObservedAtMs, now); assert.equal(view.privateRetained, false);
    now += 1000;
    await send({ ...network(now), privateIp: null, privateObservedAtMs: null, privateFailure: "interface-read-failure" });
    view = networkView(store.getNetwork(fleet, pcId), now);
    assert.equal(view.publicObservedAtMs, now); assert.equal(view.publicRetained, false);
    assert.equal(view.privateObservedAtMs, AT + 1000); assert.equal(view.privateRetained, true);
    now += 1000;
    await send({ ...network(now), publicObservedAtMs: AT - 1000, publicIp: "9.9.9.9" });
    assert.equal(networkView(store.getNetwork(fleet, pcId), now).publicIp, "8.8.8.8");
    assert.equal(networkView(store.getNetwork(fleet, pcId), now).publicObservedAtMs, AT + 2000);
    await send(failed(AT + 1000));
    assert.equal(store.getNetwork(fleet, pcId)!.network.observedAtMs, now);
    assert.equal(store.getNetwork(fleet, pcId)!.network.retryAtMs, failed().retryAtMs, "late delivery cannot discard a longer provider wait");
    await send(network(now + 5001));
    const value = store.getNetwork(fleet, pcId)!;
    assert.equal(value.network.status, "unavailable");
    assert.equal(networkView(value, now).publicObservedAtMs, AT + 2000);
    assert.equal(networkView(value, now).privateObservedAtMs, now);
    assert.deepEqual((await PcObservations.load(directory)).getNetwork(fleet, pcId), value);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("mixed invalid batches and missing PC associations cannot partially mutate either observation lane", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-network-validation-"));
  try {
    const store = await PcObservations.load(directory, () => AT);
    await store.update(fleet, [address], [host()]);
    const before = store.get(fleet, address);
    for (const values of [
      [{ pcId: otherPc, network: network() }], [{ pcId, network: network() }, { pcId, network: network() }],
      [{ pcId, network: network() }, { ...host(), password: "private" }],
      [{ pcId, network: network() }, { address, binding, gpu: { samples: "invalid" } }],
    ]) await assert.rejects(store.update(fleet, [address], values, [pcId]));
    await assert.rejects(store.update(fleet, [address], [{ pcId, network: network() }]));
    assert.equal(store.getNetwork(fleet, pcId), undefined);
    assert.deepEqual(store.get(fleet, address), before);
    await store.update(fleet, [address], [{ pcId, network: network() }], [pcId]);
    const value = store.getNetwork(fleet, pcId)!;
    value.network.publicIp = "9.9.9.9";
    assert.equal(store.listNetworks(fleet, [pcId, pcId])[0]!.network.publicIp, "8.8.8.8");
    assert.deepEqual(store.listNetworks(fleet, [otherPc]), []);
    await store.forget(fleet, [address], []);
    assert.equal(store.getNetwork(fleet, pcId), undefined);
    assert.deepEqual(store.get(fleet, address), before);
    await assert.rejects(stat(path(directory)));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("corrupt PC network files remain untouched and stop later writes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-network-corrupt-"));
  try {
    const store = await PcObservations.load(directory, () => AT);
    await store.update(fleet, [address], [{ pcId, network: network() }], [pcId]);
    await writeFile(path(directory), '{"private":"unreadable"}');
    const reopened = await PcObservations.load(directory, () => AT);
    assert.match(reopened.error!, /preserved/);
    await assert.rejects(reopened.update(fleet, [address], [{ pcId, network: network() }], [pcId]));
    assert.equal(await readFile(path(directory), "utf8"), '{"private":"unreadable"}');
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("actual observation API scopes PC networks to saved metadata and returns them without requiring node bindings", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-pc-network-api-"));
  let reads = 0;
  await writeFile(join(directory, "hosts.json"), "[]"); await writeFile(join(directory, "real200.json"), "[]");
  const runtime = await startAndroidRuntime({ dataDirectory: directory, directory: resolve("vendor/gridlens/dist"), preferredPort: 0,
    providers: { now: () => AT, metadataProvider: async () => { reads++; throw new Error("Unexpected provider read"); } } });
  const origin = `http://127.0.0.1:${runtime.port}`, native = { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" };
  const browser = { Cookie: `${SESSION_COOKIE}=${runtime.token}` };
  const mutate = async (body: object) => (await fetch(origin + "/mobile/fleets", { method: "POST", headers: native, body: JSON.stringify(body) })).json();
  try {
    const created = await mutate({ op: "create", name: "PC network", addresses: [address, second], pcMetadata: [address, second].map(address => ({
      address, pcId, pcName: "fixture", ipAddresses: ["10.0.0.2"], observedAtMs: AT })) });
    const separate = await mutate({ op: "create", name: "No PC association", addresses: [address] });
    const send = (fleetId: string, headers: Record<string, string>, value: unknown = { pcId, network: network() }) => fetch(origin + "/mobile/observations", {
      method: "POST", headers, body: JSON.stringify({ schema: 1, fleetId, updates: [value] }) });
    assert.equal((await send(created.fleetId, { ...browser, "Content-Type": "application/json" })).status, 403);
    assert.equal((await send(separate.fleetId, native)).status, 400);
    assert.equal((await send(created.fleetId, native)).status, 200);
    for (const invalid of [{ source: ["pc-egress-ipify"] }, { status: ["unavailable"] }, { publicFailure: ["dns"] }])
      assert.equal((await send(created.fleetId, native, { pcId, network: { ...failed(), httpStatus: undefined, ...invalid } })).status, 400);
    const read = async (query = "") => (await fetch(origin + `/mobile/observations?fleet=${created.fleetId}${query}`, { headers: browser })).json();
    let index = await read();
    assert.deepEqual(index.hosts, [], "IP delivery cannot manufacture a node binding or monitoring status");
    assert.equal(index.pcNetworks.length, 1);
    assert.equal(index.pcNetworks[0].pcId, pcId);
    for (const hostAddress of [address, second]) {
      const detail = await read(`&address=${hostAddress}`);
      assert.equal(detail.host, null); assert.equal(detail.pcNetworks[0].network.publicIp, "8.8.8.8");
    }
    await mutate({ op: "merge", id: created.fleetId, addresses: [address], pcMetadata: [{ address, pcId: otherPc, pcName: "replacement", ipAddresses: ["10.0.0.3"], observedAtMs: AT }] });
    assert.deepEqual((await read(`&address=${address}`)).pcNetworks, [], "changed saved PC association cannot inherit old PC's network");
    assert.equal((await read(`&address=${second}`)).pcNetworks.length, 1);
    await mutate({ op: "remove-hosts", id: created.fleetId, addresses: [second] });
    index = await read(); assert.deepEqual(index.pcNetworks, []);
    assert.equal((await send(created.fleetId, native)).status, 400, "removed final PC association cannot receive new observations");
    assert.equal(reads, 0);
  } finally {
    runtime.server.closeAllConnections(); await new Promise<void>(accept => runtime.server.close(() => accept()));
    await rm(directory, { recursive: true, force: true });
  }
});
