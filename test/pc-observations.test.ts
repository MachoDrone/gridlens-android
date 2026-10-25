import assert from "node:assert/strict";
import { test } from "node:test";
import { appendFile, mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { PcObservations } from "../runtime/pc-observations.ts";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";
import { jobGpuConditions, MAX_GPU_SAMPLES, parseBinding, parseNodeGpu, parsePcUpdate, parseReward,
  rewardAtoms, rewardView, REWARD_FRESH_MS } from "../vendor/gridlens/src/fleet/pc-observations.ts";
import type { GpuSample, NodeBinding, NodeGpuObservation, PcObservation, PcUpdate, RewardObservation } from "../vendor/gridlens/src/fleet/pc-observations.ts";

const first = "1".repeat(32), second = "1".repeat(31) + "2", third = "1".repeat(31) + "3";
const fleet = "00000000-0000-4000-8000-000000000001", otherFleet = "00000000-0000-4000-8000-000000000002";
const AT = Date.UTC(2026, 9, 25, 12);
const binding: NodeBinding = { pcId: "a".repeat(64), runtime: "docker", wrapperId: null, nodeContainerId: "b".repeat(64) };
const uuid = "GPU-00000000-1111-2222-3333-444444444444", uuid2 = "GPU-00000000-1111-2222-3333-555555555555";
const reward = (amount = "0.000000", at = AT, address = first): RewardObservation => ({ schema: 1, address, observedAtMs: at, status: "ok", amount });
const rewardUpdate = (amount = "0.000000", at = AT, address = first): PcUpdate => ({ address, binding, rewards: reward(amount, at, address) });
const sample = (at: number, device = 0, active = 0, counters: (number | null)[] = [0, 0, 0, 0]): GpuSample =>
  [at, device, 15, active, counters[0]!, counters[1]!, counters[2]!, counters[3]!];
function gpu(samples: GpuSample[], toAtMs = AT, gpuUuids = [uuid]): NodeGpuObservation {
  return { schema: 2, address: first, observedAtMs: samples.at(-1)?.[0] ?? null, status: "ok", gpuUuids,
    intervalSeconds: 15, fromAtMs: samples[0]?.[0] ?? toAtMs - 15_000, toAtMs,
    truncated: false, clockTrusted: true, samples };
}
const recorded = (observation: NodeGpuObservation): PcObservation => ({ address: first, binding, gpu: observation, receivedAtMs: AT });
const job = { node: first, timeStart: (AT - 60_000) / 1000, timeEnd: AT / 1000, state: "COMPLETED" };
const journal = (directory: string, address = first) => join(directory, "pc-observations", `${fleet}--${address}.jsonl`);

async function storeFixture() {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-observations-"));
  let now = AT;
  const store = await PcObservations.load(directory, () => now);
  return { directory, store, advance(value: number) { now = value; },
    async reload() { return PcObservations.load(directory, () => now); },
    async close() { await rm(directory, { recursive: true, force: true }); } };
}

test("observation schema accepts exact nested bindings and preserves all valid provider cooldowns", () => {
  assert.deepEqual(parseBinding({ ...binding, runtime: "podman", wrapperId: "c".repeat(64) }),
    { ...binding, runtime: "podman", wrapperId: "c".repeat(64) });
  assert.throws(() => parseBinding({ ...binding, runtime: "docker", wrapperId: "c".repeat(64) }));
  for (const status of ["ok", "rate-limited", "authentication-failed", "unavailable"] as const) {
    const value = { schema: 1, address: first, observedAtMs: AT, status,
      ...(status === "ok" ? { amount: "0.000000" } : {}), retryAfterSeconds: 864000 };
    assert.equal(parseReward(value, first).retryAfterSeconds, 864000);
  }
  for (const status of ["stale", "binding-mismatch", "not-enabled", "setup-failed"])
    assert.equal(parseNodeGpu({ ...gpu([]), status }, first).status, status);
});

test("observation validation rejects cross-host data, credentials, unknown fields and contradictory GPU flags", () => {
  for (const bad of [
    { ...rewardUpdate(), privateKey: "never accepted" },
    { ...rewardUpdate(), binding: { ...binding, command: "never executed" } },
    { ...rewardUpdate(), rewards: reward("1.000000", AT, second) },
    { ...rewardUpdate(), rewards: { ...reward(), authorization: "never accepted" } },
    { ...rewardUpdate(), rewards: { ...reward(), amount: "1.1234567" } },
    { ...rewardUpdate(), rewards: { ...reward(), status: "unavailable" } },
    { address: first, binding, gpu: { ...gpu([sample(AT)]), address: second } },
    { address: first, binding, gpu: gpu([[AT, 0, 0, 1, null, null, null, null]]) },
    { address: first, binding, gpu: gpu([sample(AT), sample(AT)]) },
    { address: first, binding, gpu: gpu([sample(AT, 1)]) },
    { address: first, binding, gpu: gpu([sample(AT + 1)]) },
    { address: first, binding, monitoring: { status: "active", checkedAtMs: AT, sshPassword: "never accepted" } },
  ]) assert.throws(() => parsePcUpdate(bad));
});

test("reward cache preserves verified zero, original source time, failure state and freshness across restart", async () => {
  const value = await storeFixture();
  try {
    await value.store.update(fleet, [first], [rewardUpdate()]);
    assert.deepEqual(rewardView(value.store.get(fleet, first), AT), { amount: "0.000000", observedAtMs: AT, current: true, status: "ok" });
    assert.equal(rewardAtoms("0.000000"), "0");
    assert.equal(rewardAtoms("835.400645"), "835400645");
    assert.equal(rewardView(value.store.get(fleet, first), AT + REWARD_FRESH_MS).current, false);
    value.advance(AT + 30_000);
    await value.store.update(fleet, [first], [{ address: first, binding,
      rewards: { schema: 1, address: first, observedAtMs: AT + 30_000, status: "authentication-failed" } }]);
    const failed = value.store.get(fleet, first)!;
    assert.equal(failed.rewards?.status, "authentication-failed");
    assert.equal(failed.rewards?.amount, undefined);
    assert.deepEqual(rewardView(failed, AT + 30_000), { amount: "0.000000", observedAtMs: AT, current: false, status: "authentication-failed" });
    const reloaded = await value.reload();
    assert.deepEqual(reloaded.get(fleet, first), failed);
    assert.equal((await stat(journal(value.directory))).mode & 0o777, 0o600);
    assert.equal((await stat(join(value.directory, "pc-observations"))).mode & 0o777, 0o700);
    failed.lastGoodRewards!.amount = "999.000000";
    assert.equal(value.store.get(fleet, first)!.lastGoodRewards!.amount, "0.000000");
  } finally { await value.close(); }
});

test("out-of-order rewards cannot replace later evidence, and changed binding cannot inherit rewards or GPU history", async () => {
  const value = await storeFixture();
  try {
    await value.store.update(fleet, [first], [{ ...rewardUpdate("2.000000"), gpu: gpu([sample(AT)]) }]);
    await value.store.update(fleet, [first], [rewardUpdate("9.000000", AT - 1000)]);
    assert.equal(value.store.get(fleet, first)?.rewards?.amount, "2.000000");
    await value.store.update(fleet, [first], [{ address: first, binding: { ...binding, nodeContainerId: "c".repeat(64) },
      monitoring: { status: "authorization-required", checkedAtMs: AT } }]);
    const changed = value.store.get(fleet, first)!;
    assert.equal(changed.lastGoodRewards, undefined); assert.equal(changed.gpu, undefined);
    assert.equal(rewardView(changed, AT).amount, null);
    assert.deepEqual((await value.reload()).get(fleet, first), changed);
  } finally { await value.close(); }
});

test("future PC clocks cannot poison reward or GPU recovery or manufacture current readings", async () => {
  const value = await storeFixture();
  try {
    await value.store.update(fleet, [first], [{ ...rewardUpdate("2.000000"), gpu: gpu([sample(AT)]) }]);
    const future = AT + 8 * 86400000;
    await value.store.update(fleet, [first], [{ ...rewardUpdate("999.000000", future), gpu: gpu([sample(future)], future) }]);
    const quarantined = value.store.get(fleet, first)!;
    assert.equal(rewardView(quarantined, AT).amount, "2.000000");
    assert.equal(rewardView(quarantined, AT).current, false);
    assert.equal(quarantined.gpu?.clockTrusted, false);
    assert.ok(quarantined.gpu?.samples.every(entry => entry[0] <= AT));
    assert.deepEqual(quarantined.gpu?.samples, [sample(AT)], "An untrusted clock cannot age out valid earlier samples");
    value.advance(AT + 15000);
    await value.store.update(fleet, [first], [{ ...rewardUpdate("3.000000", AT + 15000), gpu: gpu([sample(AT + 15000)], AT + 15000) }]);
    const recovered = value.store.get(fleet, first)!;
    assert.equal(rewardView(recovered, AT + 15000).amount, "3.000000");
    assert.equal(rewardView(recovered, AT + 15000).current, true);
    assert.equal(recovered.gpu?.clockTrusted, true);
    assert.equal(recovered.gpu?.toAtMs, AT + 15000);
    assert.deepEqual((await value.reload()).get(fleet, first), recovered);
  } finally { await value.close(); }
});

test("incremental GPU batches deduplicate samples, keep original times and survive failed polling and restart", async () => {
  const value = await storeFixture();
  try {
    const a = sample(AT - 30000), b = sample(AT - 15000, 0, 1), c = sample(AT);
    await value.store.update(fleet, [first], [{ address: first, binding, gpu: gpu([a, b], AT - 15000) }]);
    await value.store.update(fleet, [first], [{ address: first, binding, gpu: gpu([b, c]) }]);
    assert.deepEqual(value.store.get(fleet, first)?.gpu?.samples, [a, b, c]);
    const index = value.store.list(fleet, [first]);
    assert.equal(index[0]?.gpu?.sampleCount, 3); assert.equal("samples" in index[0]!.gpu!, false);
    assert.deepEqual((await value.reload()).get(fleet, first), value.store.get(fleet, first));
    value.advance(AT + 15000);
    await value.store.update(fleet, [first], [{ address: first, binding,
      gpu: { ...gpu([], AT + 15000, []), status: "unavailable", clockTrusted: false } }]);
    const failed = value.store.get(fleet, first)!;
    assert.deepEqual(failed.gpu?.samples, [a, b, c]);
    assert.equal(failed.gpu?.status, "unavailable");
    assert.deepEqual((await value.reload()).get(fleet, first), failed);
    // Data belongs only to this exact fleet even if another fleet has the address.
    assert.equal(value.store.get(otherFleet, first), undefined);
  } finally { await value.close(); }
});

test("GPU sample count and age retention expose truncation while replacement UUIDs reset associations", async () => {
  const value = await storeFixture();
  try {
    const initial = Array.from({ length: MAX_GPU_SAMPLES }, (_, index) => sample(AT - (MAX_GPU_SAMPLES - index) * 15000));
    await value.store.update(fleet, [first], [{ address: first, binding, gpu: gpu(initial) }]);
    await value.store.update(fleet, [first], [{ address: first, binding, gpu: gpu([sample(AT)]) }]);
    let saved = value.store.get(fleet, first)!.gpu!;
    assert.equal(saved.samples.length, MAX_GPU_SAMPLES); assert.equal(saved.truncated, true);
    assert.deepEqual(saved.samples[0], initial[1]);
    value.advance(AT + 8 * 86400000);
    await value.store.update(fleet, [first], [{ address: first, binding, gpu: gpu([sample(AT + 8 * 86400000)], AT + 8 * 86400000) }]);
    assert.equal(value.store.get(fleet, first)?.gpu?.samples.length, 1);
    await value.store.update(fleet, [first], [{ address: first, binding, gpu: gpu([sample(AT + 8 * 86400000)], AT + 8 * 86400000, [uuid2]) }]);
    saved = value.store.get(fleet, first)!.gpu!;
    assert.deepEqual(saved.gpuUuids, [uuid2]); assert.equal(saved.samples.length, 1);
    assert.deepEqual((await value.reload()).get(fleet, first), value.store.get(fleet, first));
  } finally { await value.close(); }
});

test("complete invalid batches change neither memory nor durable journal", async () => {
  const value = await storeFixture();
  try {
    await value.store.update(fleet, [first], [rewardUpdate("1.000000")]);
    const before = await readFile(journal(value.directory));
    for (const updates of [[rewardUpdate("2.000000"), rewardUpdate("3.000000")],
      [rewardUpdate("2.000000"), rewardUpdate("4.000000", AT, second)],
      [rewardUpdate("2.000000"), { ...rewardUpdate("4.000000", AT, second), password: "private" }]])
      await assert.rejects(value.store.update(fleet, [first], updates));
    assert.deepEqual(await readFile(journal(value.directory)), before);
    assert.equal(value.store.get(fleet, first)?.rewards?.amount, "1.000000");
    await value.store.update(fleet, [first], [rewardUpdate("2.000000", AT + 1)]);
    assert.equal(value.store.get(fleet, first)?.rewards?.amount, "2.000000");
  } finally { await value.close(); }
});

test("corrupt or truncated observation journal is preserved and disables further writes", async () => {
  const value = await storeFixture();
  try {
    await value.store.update(fleet, [first, second], [rewardUpdate(), rewardUpdate("1.000000", AT, second)]);
    await appendFile(journal(value.directory), '{"update":');
    const bytes = await readFile(journal(value.directory));
    const reloaded = await value.reload();
    assert.match(reloaded.error!, /preserved|recovery/);
    assert.equal(reloaded.get(fleet, first), undefined);
    assert.equal(reloaded.get(fleet, second)?.rewards?.amount, "1.000000");
    await assert.rejects(reloaded.update(fleet, [first], [rewardUpdate("2.000000")]));
    assert.deepEqual(await readFile(journal(value.directory)), bytes);
  } finally { await value.close(); }
});

test("partial persistence failure stops writes and restart reconciles each durable host without changing source time", async () => {
  const value = await storeFixture();
  try {
    await value.store.update(fleet, [first, second], [rewardUpdate(), rewardUpdate("1.000000", AT, second)]);
    await rm(journal(value.directory, second)); await mkdir(journal(value.directory, second));
    await assert.rejects(value.store.update(fleet, [first, second], [rewardUpdate("2.000000", AT + 1), rewardUpdate("3.000000", AT + 1, second)]));
    assert.match(value.store.error!, /persistence|reconcile|failed/i);
    assert.equal(value.store.get(fleet, first)?.rewards?.observedAtMs, AT);
    await assert.rejects(value.store.update(fleet, [first], [rewardUpdate("4.000000", AT + 2)]));
    const reloaded = await value.reload();
    assert.ok(reloaded.error); assert.equal(reloaded.get(fleet, second), undefined);
    assert.equal(reloaded.get(fleet, first)?.rewards?.observedAtMs, AT + 1);
    assert.equal(reloaded.get(fleet, first)?.rewards?.amount, "2.000000");
  } finally { await value.close(); }
});

test("job GPU conditions use verified host and time intervals without claiming causation or unrecorded history", () => {
  const observation = gpu([sample(AT - 60_000), sample(AT - 45_000, 0, 1), sample(AT - 30_000), sample(AT - 15_000), sample(AT)]);
  const view = jobGpuConditions(recorded(observation), job, AT);
  assert.match(view.label, /Thermal slowdown/); assert.match(view.detail, /Observed during this job/);
  assert.match(view.detail, /does not establish/);
  assert.equal(jobGpuConditions(recorded(observation), { ...job, node: second }, AT).label, "—");
  assert.equal(jobGpuConditions(recorded(observation), { ...job, timeStart: null }, AT).label, "—");
  assert.equal(jobGpuConditions(recorded(observation), { ...job, timeStart: (AT - 120000) / 1000, timeEnd: (AT - 90000) / 1000 }, AT).label, "—");
  assert.equal(jobGpuConditions(recorded({ ...observation, clockTrusted: false }), job, AT).label, "—");
  assert.match(jobGpuConditions(recorded({ ...observation, truncated: true }), job, AT).detail, /Partial coverage/);
});

test("job association detects supported counter increases between samples and excludes resets or gaps", () => {
  const between = recorded(gpu([sample(AT - 15000, 0, 0, [100, 0, 0, 0]), sample(AT, 0, 0, [200, 0, 0, 0])]));
  assert.match(jobGpuConditions(between, job, AT).label, /Thermal slowdown/);
  const reset = recorded(gpu([sample(AT - 15000, 0, 0, [200, 0, 0, 0]), sample(AT, 0, 0, [100, 0, 0, 0])]));
  assert.doesNotMatch(jobGpuConditions(reset, job, AT).label, /Thermal slowdown/);
  const gap = recorded(gpu([sample(AT - 60000, 0, 0, [100, 0, 0, 0]), sample(AT, 0, 0, [200, 0, 0, 0])]));
  assert.doesNotMatch(jobGpuConditions(gap, job, AT).label, /Thermal slowdown/);
  const missingDevice = recorded(gpu([sample(AT - 60000), sample(AT - 45000), sample(AT - 30000), sample(AT - 15000), sample(AT)], AT, [uuid, uuid2]));
  assert.match(jobGpuConditions(missingDevice, job, AT).detail, /Partial coverage/);
});

test("an unsupported GPU path does not invalidate an independently successful reward reading", () => {
  const value: PcObservation = { ...rewardUpdate("1.000001"), receivedAtMs: AT,
    monitoring: { status: "unsupported", checkedAtMs: AT } };
  assert.equal(rewardView(value, AT).current, true);
  assert.equal(rewardView(value, AT + REWARD_FRESH_MS).current, false);
  assert.equal(rewardView({ ...value, monitoring: { status: "paused", checkedAtMs: AT } }, AT).current, false);
});

test("unended historical running jobs cannot acquire new GPU events without a current assignment", () => {
  const observation = recorded(gpu([sample(AT - 15_000, 0, 1), sample(AT)]));
  const running = { ...job, state: "RUNNING", timeEnd: null };
  assert.equal(jobGpuConditions(observation, running, AT).label, "—");
  assert.match(jobGpuConditions(observation, running, AT).detail, /not currently confirmed/);
  assert.match(jobGpuConditions(observation, running, AT, true).label, /Thermal slowdown/);
  assert.equal(jobGpuConditions(observation, { ...running, timeEnd: AT / 1000 }, AT, true).label, "—");
  assert.equal(jobGpuConditions(observation, { ...running, result: "success" }, AT, true).label, "—");
  assert.equal(jobGpuConditions(observation, { ...job, state: "QUEUED" }, AT, true).label, "—");
});

test("observation API permits native writes and scoped browser reads without invoking public providers", async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-observation-api-"));
  await writeFile(join(dataDirectory, "hosts.json"), "[]"); await writeFile(join(dataDirectory, "real200.json"), "[]");
  let reads = 0;
  const runtime = await startAndroidRuntime({ dataDirectory, preferredPort: 0, directory: resolve("vendor/gridlens/dist"),
    providers: { now: () => AT, metadataProvider: async () => { reads++; throw new Error("Unexpected public read"); } } });
  const origin = `http://127.0.0.1:${runtime.port}`;
  const native = { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" };
  const browser = { Cookie: `${SESSION_COOKIE}=${runtime.token}`, "Content-Type": "application/json" };
  const mutate = (body: object) => fetch(origin + "/mobile/fleets", { method: "POST", headers: native, body: JSON.stringify(body) });
  try {
    const created = await (await mutate({ op: "create", name: "Observed", addresses: [first] })).json();
    const other = await (await mutate({ op: "create", name: "Separate", addresses: [second, third] })).json();
    const payload = { schema: 1, fleetId: created.fleetId, updates: [{ ...rewardUpdate("4.000000"), gpu: gpu([sample(AT)]) }] };
    assert.equal((await fetch(origin + "/mobile/observations", { method: "POST", headers: browser, body: JSON.stringify(payload) })).status, 403);
    assert.equal((await fetch(origin + "/mobile/observations", { method: "POST", headers: { ...native, Origin: "https://untrusted.example" }, body: JSON.stringify(payload) })).status, 403);
    assert.equal((await fetch(origin + "/mobile/observations", { method: "POST", headers: native, body: JSON.stringify(payload) })).status, 200);
    assert.equal((await fetch(origin + `/mobile/observations?fleet=${created.fleetId}`)).status, 403);
    const index = await (await fetch(origin + `/mobile/observations?fleet=${created.fleetId}`, { headers: browser })).json();
    assert.equal(index.hosts[0].rewards.amount, "4.000000"); assert.equal(index.hosts[0].gpu.sampleCount, 1);
    assert.equal("samples" in index.hosts[0].gpu, false);
    const detail = await (await fetch(origin + `/mobile/observations?fleet=${created.fleetId}&address=${first}`, { headers: browser })).json();
    assert.equal(detail.host.gpu.samples.length, 1);
    for (const query of [`fleet=${other.fleetId}&address=${first}`, `fleet=${created.fleetId}&address=${second}`,
      `fleet=${created.fleetId}&fleet=${other.fleetId}`, `fleet=${created.fleetId}&path=/tmp/private`, `fleet=../../private`])
      assert.equal((await fetch(origin + "/mobile/observations?" + query, { headers: browser })).status, 404);
    assert.equal((await fetch(origin + "/mobile/observations", { method: "POST", headers: native,
      body: JSON.stringify({ ...payload, updates: [rewardUpdate("2.000000", AT, second)] }) })).status, 400);
    const duplicate = `{"schema":1,"fleetId":"${created.fleetId}","updates":[],"updates":[${JSON.stringify(rewardUpdate())}]}`;
    assert.equal((await fetch(origin + "/mobile/observations", { method: "POST", headers: native, body: duplicate })).status, 400);
    assert.equal(reads, 0);
  } finally {
    runtime.server.closeAllConnections(); await new Promise<void>(accept => runtime.server.close(() => accept()));
    await rm(dataDirectory, { recursive: true, force: true });
  }
});
