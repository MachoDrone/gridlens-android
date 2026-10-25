import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseGpuTelemetry, gpuRecordingFacts } from "../vendor/gridlens/src/preview/mobile-telemetry.ts";
import type { GpuTelemetry } from "../vendor/gridlens/src/preview/mobile-telemetry.ts";
import { parsePcMetadata, parsePcMetadataUpdates } from "../runtime/pc-metadata.ts";
import { SavedFleets } from "../runtime/saved-fleets.ts";

const NOW = 1_700_000_000_000;
const ADDRESS = "1".repeat(32);

/** Four samples at -90s, -75s, -60s, 0s, matching collector.py's summary contract. */
function recording(): GpuTelemetry {
  return {
    schema: 1,
    collector: { status: "ok", lastAttemptAtMs: NOW, lastSuccessAtMs: NOW,
      intervalSeconds: 15, retentionDays: 7, maxRecords: 20000 },
    window: { startAtMs: NOW - 86_400_000, endAtMs: NOW },
    gpus: [{
      uuid: "GPU-11111111-2222-3333-4444-555555555555", index: null, deviceMinor: 0, observedAtMs: NOW,
      reasons: { swThermalSlowdown: false, hwThermalSlowdown: null, swPowerCap: true, hwPowerBrake: false },
      durationUs: { swThermalSlowdown: 1_000_000, hwThermalSlowdown: null, swPowerCap: 20_000_000, hwPowerBrake: 0 },
      history: { firstObservedAtMs: NOW - 90_000, lastObservedAtMs: NOW, sampleCount: 4, maxGapMs: 60_000,
        reasons: {
          swThermalSlowdown: { supportedSamples: 4, activeSamples: 1, counterObservedUs: 500_000, counterIntervals: 1, counterResets: 1 },
          hwThermalSlowdown: { supportedSamples: 0, activeSamples: 0, counterObservedUs: null, counterIntervals: 0, counterResets: 0 },
          swPowerCap: { supportedSamples: 4, activeSamples: 2, counterObservedUs: 2_000_000, counterIntervals: 2, counterResets: 0 },
          hwPowerBrake: { supportedSamples: 3, activeSamples: 1, counterObservedUs: 0, counterIntervals: 1, counterResets: 0 },
        } },
    }],
  };
}

function metadata(gpuTelemetry?: GpuTelemetry) {
  return { pcId: "a".repeat(64), pcName: "fixture-pc", ipAddresses: ["192.0.2.20"], observedAtMs: NOW + 1000,
    ...(gpuTelemetry === undefined ? {} : { gpuTelemetry }) };
}

test("GPU recording preserves unsupported signals, observed counters, gaps and independent source times", () => {
  const input = recording();
  const parsed = parseGpuTelemetry(input);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed, input);
  assert.notEqual(parsed.gpus[0], input.gpus[0]);
  assert.equal(parsed.gpus[0]!.index, null, "device minor cannot become a physical index");
  assert.equal(parsed.gpus[0]!.reasons.hwThermalSlowdown, null);
  assert.equal(parsed.gpus[0]!.durationUs.hwThermalSlowdown, null);
  assert.equal(parsed.gpus[0]!.history.reasons.hwThermalSlowdown.counterObservedUs, null);
  assert.equal(parsed.gpus[0]!.history.reasons.hwPowerBrake.counterObservedUs, 0, "measured zero stays distinct from unsupported");
  input.gpus[0]!.history.reasons.swPowerCap.counterObservedUs = 9000;
  assert.equal(parsed.gpus[0]!.history.reasons.swPowerCap.counterObservedUs, 2_000_000, "caller cannot mutate saved evidence");
  const before = structuredClone(parsed);
  const facts = gpuRecordingFacts(parsed, NOW + 60_000).join("\n");
  assert.match(facts, /Hardware thermal protection: unsupported \/ unknown/);
  assert.match(facts, /Power limiting: active in last sample/);
  assert.match(facts, /counter increase: 2\.000 seconds across 2 valid intervals; 0 resets/);
  assert.match(facts, /4 samples; largest recorded gap 60\.0 seconds/);
  assert.match(facts, /Unobserved intervals remain unknown/);
  assert.match(facts, /retained reading/);
  assert.match(facts, /no per-job throttling or causation is inferred/);
  assert.deepEqual(parsed, before, "rendering at a new phone time never renews source timestamps");
});

test("collector failures retain dated GPU evidence and clock disagreement is explicit", () => {
  for (const status of ["query-failed", "invalid-response", "clock-regressed", "clock-untrusted", "stale"]) {
    const input = recording();
    input.collector.status = status;
    input.collector.lastAttemptAtMs = status === "clock-regressed" ? NOW - 15_000 : NOW + 15_000;
    const parsed = parseGpuTelemetry(input);
    assert.equal(parsed.collector.status, status);
    assert.equal(parsed.collector.lastSuccessAtMs, NOW);
    assert.equal(parsed.gpus[0]!.observedAtMs, NOW);
    assert.match(gpuRecordingFacts(parsed, NOW + 100_000).join("\n"), /retained reading/);
  }
  assert.match(gpuRecordingFacts(recording(), NOW - 6000).join("\n"), /PC and phone clocks disagree/);
  assert.doesNotMatch(gpuRecordingFacts(recording(), NOW + 1000).join("\n"), /currently active|throttled this job|caused this job/i);
});

test("unavailable and unsupported collectors never imply healthy GPUs or a zero measurement", () => {
  for (const status of ["unsupported", "starting", "unavailable", "not-enabled", "setup-failed"]) {
    const empty = parseGpuTelemetry({ schema: 1, collector: { status }, gpus: [] });
    assert.deepEqual(empty.gpus, []);
    const facts = gpuRecordingFacts(empty, NOW).join("\n");
    assert.match(facts, /No verified GPU readings were returned/);
    assert.doesNotMatch(facts, /inactive in last sample|counter increase: 0/);
  }
  assert.match(gpuRecordingFacts(undefined, NOW).join("\n"), /not connected/);
});

test("GPU recording rejects extra fields throughout the tree and cannot carry credentials or job attribution", () => {
  const inject: Array<(input: GpuTelemetry) => object> = [
    value => value, value => value.collector, value => value.window!, value => value.gpus[0]!,
    value => value.gpus[0]!.reasons, value => value.gpus[0]!.durationUs, value => value.gpus[0]!.history,
    value => value.gpus[0]!.history.reasons, value => value.gpus[0]!.history.reasons.swPowerCap,
  ];
  for (const select of inject) {
    for (const field of ["authorization", "privateKey", "jobId"]) {
      const input = recording();
      Object.assign(select(input), { [field]: "untrusted-fixture-value" });
      assert.throws(() => parseGpuTelemetry(input), /Invalid GPU/, `${field} must be rejected at every nesting level`);
    }
  }
});

test("GPU recording rejects malformed dates and samples outside the declared window", () => {
  for (const bad of [-1, 1.5, NaN, Infinity, 8_640_000_000_000_001, "1700000000000"]) {
    const input = recording();
    Object.assign(input.collector, { lastAttemptAtMs: bad });
    assert.throws(() => parseGpuTelemetry(input));
    Object.assign(input.collector, { lastAttemptAtMs: NOW });
    Object.assign(input.gpus[0]!, { observedAtMs: bad });
    assert.throws(() => parseGpuTelemetry(input));
  }
  const mutations: Array<(input: GpuTelemetry) => void> = [
    value => { value.window!.startAtMs = NOW - 30_000; },
    value => { value.window!.endAtMs = NOW - 1; },
    value => { value.window!.startAtMs = NOW + 1; },
    value => { value.window!.startAtMs = NOW - 7 * 86_400_000 - 1; },
    value => { value.gpus[0]!.history.lastObservedAtMs = NOW - 1; },
    value => { value.gpus[0]!.history.firstObservedAtMs = NOW + 1; },
    value => { delete value.window; },
  ];
  for (const mutate of mutations) {
    const input = recording(); mutate(input);
    assert.throws(() => parseGpuTelemetry(input));
  }
});

test("GPU summary rejects impossible sample histories, transitions and unsupported latest flags", () => {
  const mutations: Array<(input: GpuTelemetry) => void> = [
    value => { value.gpus[0]!.history.sampleCount = 0; },
    value => { value.gpus[0]!.history.sampleCount = 1; },
    value => { value.gpus[0]!.history.maxGapMs = 90_001; },
    value => { value.gpus[0]!.history.reasons.swPowerCap.activeSamples = 5; },
    value => { value.gpus[0]!.history.reasons.swPowerCap.supportedSamples = 0; value.gpus[0]!.history.reasons.swPowerCap.activeSamples = 0; },
    value => { value.gpus[0]!.history.reasons.swPowerCap.counterIntervals = 4; },
    value => { value.gpus[0]!.history.reasons.swPowerCap.counterResets = 2; },
    value => { value.gpus[0]!.history.reasons.swPowerCap.counterIntervals = 0; },
    value => { value.gpus[0]!.history.reasons.swPowerCap.counterObservedUs = null; },
    value => { value.gpus[0]!.durationUs.swPowerCap = -1; },
    value => { value.gpus[0]!.history.reasons.swPowerCap.counterObservedUs = Number.MAX_SAFE_INTEGER + 1; },
  ];
  for (const [index, mutate] of mutations.entries()) {
    const input = recording(); mutate(input);
    assert.throws(() => parseGpuTelemetry(input), Error, `impossible history case ${index}`);
  }
});

test("GPU identity and recording policy are bounded without inferring physical index", () => {
  const mutations: Array<(input: GpuTelemetry) => void> = [
    value => { value.gpus.push(structuredClone(value.gpus[0]!)); },
    value => { value.gpus[0]!.uuid = "GPU-unverified"; },
    value => { Object.assign(value.gpus[0]!, { index: 0 }); },
    value => { value.gpus[0]!.deviceMinor = 10000; },
    value => { value.collector.status = "healthy"; },
    value => { value.collector.intervalSeconds = 1; },
    value => { value.collector.retentionDays = 365; },
    value => { value.collector.maxRecords = 999999; },
    value => { value.gpus = Array.from({ length: 65 }, () => structuredClone(value.gpus[0]!)); },
  ];
  for (const mutate of mutations) {
    const input = recording(); mutate(input);
    assert.throws(() => parseGpuTelemetry(input));
  }
});

test("optional GPU metadata is backwards compatible and persists without freshness renewal", async () => {
  const old = metadata();
  assert.deepEqual(parsePcMetadata(old, NOW + 2000), old);
  const input = metadata(recording());
  const parsed = parsePcMetadata(input, NOW + 2000);
  assert.equal(parsed.observedAtMs, NOW + 1000);
  assert.equal(parsed.gpuTelemetry!.gpus[0]!.observedAtMs, NOW, "PC discovery and GPU observation times are independent");
  assert.throws(() => parsePcMetadata({ ...old, gpuTelemetry: { ...recording(), password: "fixture" } }, NOW + 2000));
  const updates = parsePcMetadataUpdates([{ address: ADDRESS, ...input }], [ADDRESS], NOW + 2000);
  assert.deepEqual(updates.get(ADDRESS), parsed);
  assert.throws(() => parsePcMetadataUpdates([{ address: ADDRESS, ...input }], [], NOW + 2000));

  const directory = await mkdtemp(join(tmpdir(), "gridlens-gpu-metadata-"));
  let now = NOW + 2000;
  try {
    const store = await SavedFleets.load(directory, () => now);
    const { fleetId } = await store.mutate({ op: "create", name: "Fixture fleet", addresses: [ADDRESS],
      pcMetadata: [{ address: ADDRESS, ...input }] });
    now += 60_000;
    await store.mutate({ op: "merge", id: fleetId, addresses: [ADDRESS] });
    const reopened = await SavedFleets.load(directory, () => now);
    const pc = reopened.get(fleetId!)!.hosts[0]!.pcMetadata!;
    assert.deepEqual(pc.gpuTelemetry, recording());
    assert.equal(pc.observedAtMs, NOW + 1000);
    assert.match(gpuRecordingFacts(pc.gpuTelemetry, now).join("\n"), /retained reading/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
