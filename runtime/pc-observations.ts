import { appendFile, mkdir, open, readdir, stat, unlink } from "node:fs/promises";
import { join } from "node:path";
import { atomicPrivateJson, parseUniqueJson, FLEET_ID } from "./saved-fleets.ts";
import { MAX_GPU_SAMPLES, parsePcUpdate, parseSavedObservation, sameBinding } from "../vendor/gridlens/src/fleet/pc-observations.ts";
import type { PcUpdate, PcObservation, PcObservationIndex, NodeGpuObservation } from "../vendor/gridlens/src/fleet/pc-observations.ts";

const MAX_FILE = 4 * 1024 * 1024;
const MAX_CACHE = 64 * 1024 * 1024;
const MAX_JOURNALS = 128 * 1024 * 1024;
const MAX_HOSTS = 2048;
const keyOf = (fleet: string, address: string): string => `${fleet}--${address}`;
function mergeGpu(prior: NodeGpuObservation | undefined, update: NodeGpuObservation): NodeGpuObservation {
  if (prior && !update.clockTrusted) return { ...structuredClone(prior), status: update.status, clockTrusted: false };
  if (prior && update.status !== "ok" && !update.gpuUuids.length && !update.samples.length)
    return { ...structuredClone(prior), status: update.status, clockTrusted: update.status.startsWith("clock-") ? false : prior.clockTrusted };
  if (!prior || prior.toAtMs > update.toAtMs + 5000 || JSON.stringify(prior.gpuUuids) !== JSON.stringify(update.gpuUuids)) return structuredClone(update);
  const keep = new Map(prior.samples.filter(sample => sample[0] >= update.toAtMs - 7 * 86_400_000).map(sample => [`${sample[0]}:${sample[1]}`, sample]));
  for (const sample of update.samples) keep.set(`${sample[0]}:${sample[1]}`, sample);
  const combined = [...keep.values()].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const samples = combined.slice(-MAX_GPU_SAMPLES);
  return { ...structuredClone(update), fromAtMs: Math.min(update.fromAtMs, samples[0]?.[0] ?? update.fromAtMs),
    truncated: prior.truncated || update.truncated || combined.length > samples.length, samples };
}
function apply(prior: PcObservation | undefined, update: PcUpdate, receivedAtMs: number): PcObservation {
  update = structuredClone(update);
  if (update.rewards && update.rewards.observedAtMs > receivedAtMs + 300_000) {
    const { amount: _amount, ...reading } = update.rewards;
    update.rewards = { ...reading, status: "unavailable", observedAtMs: receivedAtMs };
  }
  if (update.gpu && update.gpu.toAtMs > receivedAtMs + 5000) update.gpu = { ...update.gpu, status: "clock-untrusted", clockTrusted: false,
    samples: update.gpu.samples.filter(sample => sample[0] <= receivedAtMs + 5000) };
  const base = prior && sameBinding(prior.binding, update.binding) ? prior : undefined;
  const next: PcObservation = { ...(base ?? {}), ...structuredClone(update), receivedAtMs };
  if (base?.rewards && update.rewards && update.rewards.observedAtMs < base.rewards.observedAtMs) next.rewards = base.rewards;
  if (next.rewards?.status === "ok") next.lastGoodRewards = next.rewards;
  if (update.gpu) {
    if (base?.gpu?.clockTrusted && update.gpu.clockTrusted && update.gpu.toAtMs < base.gpu.toAtMs) next.gpu = base.gpu;
    else next.gpu = mergeGpu(base?.gpu, update.gpu);
  }
  if (base?.monitoring && update.monitoring && update.monitoring.checkedAtMs < base.monitoring.checkedAtMs) next.monitoring = base.monitoring;
  return next;
}

/** Durable public observation cache. SSH permission/credentials never enter this store. */
export class PcObservations {
  private records = new Map<string, PcObservation>();
  private sizes = new Map<string, number>();
  private diskSizes = new Map<string, number>();
  private chain: Promise<void> = Promise.resolve();
  private writable = true;
  private directory: string;
  private now: () => number;
  error: string | null = null;
  private constructor(directory: string, now: () => number) { this.directory = directory; this.now = now; }
  static async load(directory: string, now = Date.now): Promise<PcObservations> {
    const store = new PcObservations(join(directory, "pc-observations"), now);
    await mkdir(store.directory, { recursive: true, mode: 0o700 });
    const files = (await readdir(store.directory)).filter(file => /^[a-f0-9-]{36}--[1-9A-HJ-NP-Za-km-z]{32,44}\.jsonl$/.test(file));
    if (files.length > MAX_HOSTS) { store.error = "PC observation cache exceeds its capacity."; store.writable = false; return store; }
    let total = 0;
    for (const filename of files) {
      const key = filename.slice(0, -6), fleet = key.slice(0, 36), address = key.slice(38);
      if (!FLEET_ID.test(fleet)) continue;
      try {
        const file = await open(join(store.directory, filename), "r");
        let contents: string;
        try { const size = (await file.stat()).size; if (size > MAX_FILE) throw new Error(); contents = await file.readFile("utf8"); store.diskSizes.set(key, size); } finally { await file.close(); }
        if (!contents.endsWith("\n")) throw new Error();
        let result: PcObservation | undefined;
        for (const line of contents.trimEnd().split("\n")) {
          const entry = parseUniqueJson(line) as { snapshot?: unknown; update?: unknown; receivedAtMs?: number };
          if (!entry || typeof entry !== "object" || Array.isArray(entry)) throw new Error();
          if (Object.keys(entry).join(",") === "snapshot") result = parseSavedObservation(entry.snapshot);
          else if (Object.keys(entry).sort().join(",") === "receivedAtMs,update" && typeof entry.receivedAtMs === "number" && Number.isSafeInteger(entry.receivedAtMs) && entry.receivedAtMs > 0) result = apply(result, parsePcUpdate(entry.update), entry.receivedAtMs);
          else throw new Error();
          if (result.address !== address) throw new Error();
        }
        if (!result) throw new Error();
        const bytes = Buffer.byteLength(JSON.stringify(result)); total += bytes;
        if (total > MAX_CACHE) throw new Error();
        store.records.set(key, result); store.sizes.set(key, bytes);
      } catch { store.error = "Some saved PC observations could not be read; their files were preserved."; store.writable = false; }
    }
    if ([...store.diskSizes.values()].reduce((a, b) => a + b, 0) > MAX_JOURNALS) {
      store.error = "PC observation journals exceed their storage capacity."; store.writable = false;
    }
    return store;
  }
  get(fleet: string, address: string): PcObservation | undefined { return structuredClone(this.records.get(keyOf(fleet, address))); }
  list(fleet: string, addresses: readonly string[]): PcObservationIndex[] {
    return addresses.flatMap(address => {
      const record = this.records.get(keyOf(fleet, address)); if (!record) return [];
      const { gpu, ...rest } = record;
      if (!gpu) return [structuredClone(rest)];
      const { samples, ...summary } = gpu;
      return [{ ...structuredClone(rest), gpu: { ...structuredClone(summary), sampleCount: samples.length } }];
    });
  }
  async update(fleet: string, roster: readonly string[], values: unknown[]): Promise<void> {
    const work = this.chain.then(async () => {
      if (!this.writable) throw new Error("Saved PC observations need recovery; existing records were preserved.");
      if (!FLEET_ID.test(fleet) || values.length < 1 || values.length > 64) throw new Error("Invalid PC observation batch.");
      const allowed = new Set(roster), seen = new Set<string>();
      const updates = values.map(value => {
        const update = parsePcUpdate(value);
        if (!allowed.has(update.address) || seen.has(update.address)) throw new Error("Choose distinct hosts from this saved fleet.");
        seen.add(update.address); return update;
      });
      const received = this.now();
      const candidate = new Map(this.records);
      const sizes = new Map(this.sizes);
      for (const update of updates) {
        const key = keyOf(fleet, update.address), next = apply(candidate.get(key), update, received);
        candidate.set(key, next); sizes.set(key, Buffer.byteLength(JSON.stringify(next)));
      }
      if (candidate.size > MAX_HOSTS) throw new Error("PC monitoring cache supports at most 2,048 host records.");
      let total = [...sizes.values()].reduce((sum, bytes) => sum + bytes, 0);
      const compacted = new Set<string>();
      for (const [key, record] of [...candidate].sort((a, b) => a[1].receivedAtMs - b[1].receivedAtMs)) {
        if (total <= MAX_CACHE) break;
        if (!record.gpu?.samples.length) continue;
        const next = { ...record, gpu: { ...record.gpu, samples: [], truncated: true } };
        const bytes = Buffer.byteLength(JSON.stringify(next)); total += bytes - sizes.get(key)!;
        candidate.set(key, next); sizes.set(key, bytes); compacted.add(key);
      }
      if (total > MAX_CACHE) throw new Error("PC observation cache is full.");
      try {
        for (const key of compacted) {
          await atomicPrivateJson(join(this.directory, key + ".jsonl"), { snapshot: candidate.get(key)! });
          this.diskSizes.set(key, Buffer.byteLength(JSON.stringify({ snapshot: candidate.get(key)! })) + 1);
        }
        for (const update of updates) {
          const key = keyOf(fleet, update.address), prior = this.records.get(key), next = candidate.get(key)!;
          if (compacted.has(key)) continue;
          const path = join(this.directory, key + ".jsonl");
          const bytes = await stat(path).then(s => s.size, () => 0);
          // Append only newly observed sample points; old values do not acquire a new source time.
          let delta = update;
          if (prior?.gpu && update.gpu && sameBinding(prior.binding, update.binding) && JSON.stringify(prior.gpu.gpuUuids) === JSON.stringify(update.gpu.gpuUuids)) {
            const known = new Map(prior.gpu.samples.map(s => [`${s[0]}:${s[1]}`, JSON.stringify(s)]));
            delta = { ...update, gpu: { ...update.gpu, samples: update.gpu.samples.filter(s => known.get(`${s[0]}:${s[1]}`) !== JSON.stringify(s)) } };
          }
          const line = JSON.stringify({ update: delta, receivedAtMs: received }) + "\n";
          if (!prior || bytes + Buffer.byteLength(line) > MAX_FILE || !sameBinding(prior.binding, update.binding)) {
            await atomicPrivateJson(path, { snapshot: next });
            this.diskSizes.set(key, Buffer.byteLength(JSON.stringify({ snapshot: next })) + 1);
          }
          else {
            await appendFile(path, line, { mode: 0o600 });
            const file = await open(path, "r+"); try { await file.sync(); } finally { await file.close(); }
            this.diskSizes.set(key, bytes + Buffer.byteLength(line));
          }
        }
        let disk = [...this.diskSizes.values()].reduce((a, b) => a + b, 0);
        for (const [key, bytes] of [...this.diskSizes].sort((a, b) => b[1] - a[1])) {
          if (disk <= MAX_JOURNALS) break;
          const record = candidate.get(key); if (!record) continue;
          const size = Buffer.byteLength(JSON.stringify({ snapshot: record })) + 1;
          if (size >= bytes) continue;
          await atomicPrivateJson(join(this.directory, key + ".jsonl"), { snapshot: record });
          this.diskSizes.set(key, size); disk += size - bytes;
        }
      } catch {
        this.writable = false; this.error = "PC observation persistence failed. Reopen the app to reconcile saved records.";
        throw new Error(this.error);
      }
      this.records = candidate; this.sizes = sizes;
    });
    this.chain = work.catch(() => {}); await work;
  }
  async forget(fleet: string, roster: readonly string[]): Promise<void> {
    const keep = new Set(roster);
    const work = this.chain.then(async () => {
      for (const key of [...this.records.keys()]) if (key.startsWith(fleet + "--") && !keep.has(key.slice(38))) {
        await unlink(join(this.directory, key + ".jsonl")); this.records.delete(key); this.sizes.delete(key); this.diskSizes.delete(key);
      }
    });
    this.chain = work.catch(() => {}); await work;
  }
}
