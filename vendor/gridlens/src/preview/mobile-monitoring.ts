import { parseSavedObservation } from "../fleet/pc-observations.ts";
import type { PcObservation, PcObservationIndex } from "../fleet/pc-observations.ts";

async function read(url: string, signal: AbortSignal, maximum: number): Promise<Record<string, unknown>> {
  const response = await fetch(url, { credentials: "same-origin", cache: "no-store", signal });
  if (!response.ok || !response.body) throw new Error("PC observations unavailable.");
  const reader = response.body.getReader(), parts: Uint8Array[] = [];
  let length = 0;
  try { for (;;) { const next = await reader.read(); if (next.done) break; length += next.value.byteLength; if (length > maximum) throw new Error("PC response too large."); parts.push(next.value); } }
  finally { await reader.cancel(); }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  const value: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid PC response.");
  return value as Record<string, unknown>;
}
/** Reads only the phone's cache. Native Android owns connections and authorization. */
export function createMobileMonitoring(options: { fleetId: string; visible: () => boolean; hosts: () => readonly string[]; changed: () => void }) {
  const index = new Map<string, PcObservationIndex>(), details = new Map<string, PcObservation>();
  const attempted = new Map<string, number>(), queued = new Set<string>(), active = new Map<string, AbortController>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let indexController: AbortController | undefined;
  let stopped = false;
  let error: string | null = null;
  function headers(value: Record<string, unknown>): void { if (value.schema !== 1 || value.fleetId !== options.fleetId) throw new Error("Wrong PC observation scope."); }
  async function refresh(): Promise<void> {
    if (stopped || !options.visible() || indexController) return;
    if (timer !== undefined) clearTimeout(timer); timer = undefined;
    const controller = new AbortController(); indexController = controller;
    const timeout = setTimeout(() => controller.abort(), 10_000);
    try {
      const value = await read(`/mobile/observations?fleet=${options.fleetId}`, controller.signal, 8 * 1024 * 1024); headers(value);
      if (!Array.isArray(value.hosts) || value.hosts.length > 2048) throw new Error();
      const allowed = new Set(options.hosts()), next = new Map<string, PcObservationIndex>();
      for (const raw of value.hosts) {
        if (!raw || typeof raw !== "object") throw new Error();
        const { gpu, ...rest } = raw;
        const parsed = parseSavedObservation(rest);
        if (!allowed.has(parsed.address) || next.has(parsed.address)) throw new Error();
        const { gpu: _unused, ...base } = parsed;
        const item: PcObservationIndex = base;
        if (gpu !== undefined) {
          if (!gpu || typeof gpu !== "object" || !Number.isInteger(gpu.sampleCount) || gpu.sampleCount < 0 || gpu.sampleCount > 6000) throw new Error();
          const { sampleCount, ...summary } = gpu;
          const checked = parseSavedObservation({ ...rest, gpu: { ...summary, samples: [] } });
          const { samples: _samples, ...valid } = checked.gpu!;
          item.gpu = { ...valid, sampleCount };
        }
        next.set(item.address, item);
      }
      if (indexController !== controller || !options.visible()) return;
      index.clear(); for (const [address, item] of next) index.set(address, item);
      for (const address of details.keys()) if (!next.has(address)) details.delete(address);
      error = value.error ? "Saved PC observations need attention." : null;
      options.changed();
    } catch { if (!controller.signal.aborted) { error = "PC cache unavailable; previously read values retain their source times."; options.changed(); } }
    finally {
      clearTimeout(timeout);
      const current = indexController === controller;
      if (current) indexController = undefined;
      if (current && !stopped && options.visible()) timer = setTimeout(() => { void refresh(); }, 30_000);
    }
  }
  function pump(): void {
    if (stopped || !options.visible()) return;
    for (const address of queued) {
      if (active.size >= 4) break;
      queued.delete(address);
      if (!options.hosts().includes(address)) continue;
      const controller = new AbortController(); active.set(address, controller); attempted.set(address, Date.now());
      const timeout = setTimeout(() => controller.abort(), 10_000);
      void read(`/mobile/observations?fleet=${options.fleetId}&address=${encodeURIComponent(address)}`, controller.signal, 2 * 1024 * 1024).then(value => {
        headers(value);
        if (value.host === null) return;
        const record = parseSavedObservation(value.host);
        if (record.address !== address) throw new Error();
        if (active.get(address) !== controller || !options.visible()) return;
        details.set(address, record); options.changed();
      }).catch(() => {}).finally(() => {
        clearTimeout(timeout);
        if (active.get(address) === controller) active.delete(address);
        pump();
      });
    }
  }
  function detail(address: string): PcObservation | undefined {
    if (index.has(address) && !active.has(address) && Date.now() - (attempted.get(address) ?? 0) >= 30_000) {
      queued.add(address); pump();
    }
    return details.get(address);
  }
  function pause(): void {
    if (timer !== undefined) clearTimeout(timer); timer = undefined;
    indexController?.abort(); indexController = undefined;
    for (const controller of active.values()) controller.abort(); active.clear(); queued.clear();
  }
  return { index, detail, get error() { return error; }, refresh,
    resume() { if (options.visible()) { stopped = false; if (timer !== undefined) clearTimeout(timer); void refresh(); pump(); } else pause(); },
    stop() { stopped = true; pause(); },
  };
}
