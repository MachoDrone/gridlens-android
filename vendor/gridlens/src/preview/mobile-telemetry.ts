/** Android PC recordings. No host-to-GPU or job attribution is inferred. */
export const GPU_REASONS = {
  swThermalSlowdown: "Thermal slowdown", hwThermalSlowdown: "Hardware thermal protection",
  swPowerCap: "Power limiting", hwPowerBrake: "Power braking",
} as const;
type Reason = keyof typeof GPU_REASONS;
interface ReasonHistory { supportedSamples: number; activeSamples: number; counterObservedUs: number | null; counterIntervals: number; counterResets: number }
export interface GpuReading {
  uuid: string; index: null; deviceMinor: number | null; observedAtMs: number;
  reasons: Record<Reason, boolean | null>; durationUs: Record<Reason, number | null>;
  history: { firstObservedAtMs: number; lastObservedAtMs: number; sampleCount: number; maxGapMs: number; reasons: Record<Reason, ReasonHistory> };
}
export interface GpuTelemetry {
  schema: 1;
  collector: { status: string; lastAttemptAtMs?: number | null; lastSuccessAtMs?: number | null; intervalSeconds?: number; retentionDays?: number; maxRecords?: number };
  window?: { startAtMs: number; endAtMs: number };
  gpus: GpuReading[];
}
const statuses = new Set(["ok", "unsupported", "query-failed", "invalid-response", "clock-regressed", "clock-untrusted", "stale", "starting", "unavailable", "not-enabled", "setup-failed"]);
const number = (value: unknown, maximum = Number.MAX_SAFE_INTEGER): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= maximum;
const time = (value: unknown): value is number => number(value, 8_640_000_000_000_000);
function record(value: unknown, keys: string[], optional: string[] = []): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)
    || keys.some(key => !Object.hasOwn(value, key)) || Object.keys(value).some(key => !keys.includes(key) && !optional.includes(key))) throw new Error("Invalid GPU recording.");
  return value as Record<string, unknown>;
}
/** Fail closed on unknown fields; never carry arbitrary native output to saved data. */
export function parseGpuTelemetry(value: unknown): GpuTelemetry {
  const input = record(value, ["schema", "collector", "gpus"], ["window"]);
  const collector = record(input.collector, ["status"], ["lastAttemptAtMs", "lastSuccessAtMs", "intervalSeconds", "retentionDays", "maxRecords"]);
  if (input.schema !== 1 || typeof collector.status !== "string" || !statuses.has(collector.status)
    || !Array.isArray(input.gpus) || input.gpus.length > 64) throw new Error("Invalid GPU recording.");
  for (const key of ["lastAttemptAtMs", "lastSuccessAtMs"] as const) if (Object.hasOwn(collector, key) && collector[key] !== null && !time(collector[key])) throw new Error("Invalid GPU observation time.");
  for (const [key, expected] of [["intervalSeconds", 15], ["retentionDays", 7], ["maxRecords", 20000]] as const)
    if (Object.hasOwn(collector, key) && collector[key] !== expected) throw new Error("Unsupported GPU recording policy.");
  if (input.window !== undefined) {
    const window = record(input.window, ["startAtMs", "endAtMs"]);
    if (!time(window.startAtMs) || !time(window.endAtMs) || window.endAtMs < window.startAtMs || window.endAtMs - window.startAtMs > 7 * 86_400_000) throw new Error("Invalid GPU recording window.");
  }
  const uuids = new Set<string>();
  for (const raw of input.gpus) {
    const gpu = record(raw, ["uuid", "index", "deviceMinor", "observedAtMs", "reasons", "durationUs", "history"]);
    if (typeof gpu.uuid !== "string" || !/^GPU-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(gpu.uuid) || uuids.has(gpu.uuid)
      || gpu.index !== null || !(gpu.deviceMinor === null || number(gpu.deviceMinor, 9999)) || !time(gpu.observedAtMs)) throw new Error("Invalid GPU identity.");
    uuids.add(gpu.uuid);
    const keys = Object.keys(GPU_REASONS);
    const reasons = record(gpu.reasons, keys), counters = record(gpu.durationUs, keys);
    const history = record(gpu.history, ["firstObservedAtMs", "lastObservedAtMs", "sampleCount", "maxGapMs", "reasons"]);
    if (!time(history.firstObservedAtMs) || !time(history.lastObservedAtMs) || history.firstObservedAtMs > history.lastObservedAtMs
      || history.lastObservedAtMs !== gpu.observedAtMs || !number(history.sampleCount, 20000) || history.sampleCount < 1 || !number(history.maxGapMs)) throw new Error("Invalid GPU history.");
    if (history.maxGapMs > history.lastObservedAtMs - history.firstObservedAtMs
      || history.sampleCount === 1 && (history.firstObservedAtMs !== history.lastObservedAtMs || history.maxGapMs !== 0)) throw new Error("Invalid GPU history interval.");
    if (input.window === undefined) throw new Error("Missing GPU window.");
    const window = input.window as { startAtMs: number; endAtMs: number };
    if (history.firstObservedAtMs < window.startAtMs || history.lastObservedAtMs > window.endAtMs) throw new Error("GPU history exceeds its window.");
    const histories = record(history.reasons, keys);
    for (const key of keys) {
      if (!(reasons[key] === null || typeof reasons[key] === "boolean") || !(counters[key] === null || number(counters[key]))) throw new Error("Invalid GPU signal.");
      const counts = record(histories[key], ["supportedSamples", "activeSamples", "counterObservedUs", "counterIntervals", "counterResets"]);
      for (const field of ["supportedSamples", "activeSamples", "counterIntervals", "counterResets"])
        if (!number(counts[field], history.sampleCount)) throw new Error("Invalid GPU signal count.");
      if ((counts.activeSamples as number) > (counts.supportedSamples as number)
        || !(counts.counterObservedUs === null || number(counts.counterObservedUs))) throw new Error("Invalid GPU counter.");
      if ((counts.counterIntervals as number) + (counts.counterResets as number) > history.sampleCount - 1
        || (counts.counterIntervals === 0) !== (counts.counterObservedUs === null)
        || reasons[key] !== null && counts.supportedSamples === 0
        || reasons[key] === true && counts.activeSamples === 0
        || reasons[key] === false && counts.activeSamples === counts.supportedSamples
        || reasons[key] === null && counts.supportedSamples === history.sampleCount) throw new Error("Inconsistent GPU signal history.");
    }
  }
  // Fields and scalar types have all been checked. Clone to avoid caller mutation.
  return structuredClone(value) as GpuTelemetry;
}

export function gpuRecordingFacts(telemetry: GpuTelemetry | undefined, phoneNow: number): string[] {
  if (!telemetry) return ["GPU recording is not connected. Enable it from Rescan on the selected PCs."];
  const facts = [`Recorder: ${telemetry.collector.status.replaceAll("-", " ")}.`];
  if (telemetry.window) facts.push(`Recorded window: ${new Date(telemetry.window.startAtMs).toISOString()} to ${new Date(telemetry.window.endAtMs).toISOString()}.`);
  for (const gpu of telemetry.gpus) {
    const age = phoneNow - gpu.observedAtMs;
    facts.push(`${gpu.uuid}: last recorded ${new Date(gpu.observedAtMs).toISOString()}${age < -5000 ? "; PC and phone clocks disagree" : age > 45000 ? "; retained reading" : ""}.`);
    for (const key of Object.keys(GPU_REASONS) as Reason[]) {
      const reason = gpu.history.reasons[key];
      const current = gpu.reasons[key] === null ? "unsupported / unknown" : gpu.reasons[key] ? "active in last sample" : "inactive in last sample";
      facts.push(`${GPU_REASONS[key]}: ${current}; observed active in ${reason.activeSamples}/${reason.supportedSamples} supported samples.`);
      if (reason.counterObservedUs !== null) facts.push(`${GPU_REASONS[key]} counter increase: ${(reason.counterObservedUs / 1_000_000).toFixed(3)} seconds across ${reason.counterIntervals} valid intervals; ${reason.counterResets} resets.`);
    }
    facts.push(`${gpu.history.sampleCount} samples; largest recorded gap ${(gpu.history.maxGapMs / 1000).toFixed(1)} seconds. Unobserved intervals remain unknown.`);
  }
  if (!telemetry.gpus.length) facts.push("No verified GPU readings were returned.");
  facts.push("These are PC GPU observations. A host-to-GPU mapping has not been verified; no per-job throttling or causation is inferred.");
  return facts;
}
