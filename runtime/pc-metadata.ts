import { parseGpuTelemetry } from "../vendor/gridlens/src/preview/mobile-telemetry.ts";
import type { GpuTelemetry } from "../vendor/gridlens/src/preview/mobile-telemetry.ts";
import { isIP } from "node:net";

/** Public machine metadata observed through a pinned native SSH session. */
export interface PcMetadata {
  pcId: string;
  pcName: string | null;
  ipAddresses: string[];
  observedAtMs: number;
  gpuTelemetry?: GpuTelemetry;
}

export function parsePcMetadata(value: unknown, latest: number): PcMetadata {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("Invalid PC metadata.");
  const pc = value as Record<string, unknown>;
  if (Object.keys(pc).filter(key => key !== "gpuTelemetry").sort().join(",") !== "ipAddresses,observedAtMs,pcId,pcName"
    || typeof pc.pcId !== "string" || !/^[a-f0-9]{64}$/.test(pc.pcId)
    || !(pc.pcName === null || typeof pc.pcName === "string" && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(pc.pcName))
    || typeof pc.observedAtMs !== "number" || !Number.isSafeInteger(pc.observedAtMs) || pc.observedAtMs < 1 || pc.observedAtMs > latest
    || !Array.isArray(pc.ipAddresses) || pc.ipAddresses.length < 1 || pc.ipAddresses.length > 32) throw new Error("Invalid PC metadata.");
  const addresses = pc.ipAddresses.map((address: unknown): string => {
    if (typeof address !== "string" || address.includes("%") || !isIP(address)) throw new Error("Invalid PC IP address.");
    if (isIP(address) === 4) {
      const first = Number(address.split(".")[0]);
      if (first === 0 || first === 127 || first >= 224) throw new Error("Invalid PC IP address.");
      return address;
    }
    const canonical = new URL(`http://[${address}]/`).hostname.slice(1, -1);
    if (canonical === "::" || canonical === "::1" || canonical.startsWith("ff") || canonical.startsWith("::ffff:")) throw new Error("Invalid PC IP address.");
    return canonical;
  });
  if (new Set(addresses).size !== addresses.length) throw new Error("Duplicate PC IP address.");
  return { pcId: pc.pcId, pcName: pc.pcName, ipAddresses: addresses, observedAtMs: pc.observedAtMs,
    ...(Object.hasOwn(pc, "gpuTelemetry") ? { gpuTelemetry: parseGpuTelemetry(pc.gpuTelemetry) } : {}) };
}

export function parsePcMetadataUpdates(value: unknown, roster: readonly string[], latest: number): Map<string, PcMetadata | null> {
  if (value === undefined) return new Map();
  if (!Array.isArray(value) || value.length > roster.length) throw new Error("Invalid PC metadata list.");
  const allowed = new Set(roster), result = new Map<string, PcMetadata | null>();
  for (const item of value) {
    if (typeof item !== "object" || item === null || Array.isArray(item)
      || typeof item.address !== "string" || !allowed.has(item.address) || result.has(item.address)) throw new Error("Invalid PC metadata host.");
    const { address, ...metadata } = item;
    if (metadata.pcId === null && metadata.pcName === null && Array.isArray(metadata.ipAddresses)
      && metadata.ipAddresses.length === 0 && Object.keys(metadata).sort().join(",") === "ipAddresses,observedAtMs,pcId,pcName"
      && typeof metadata.observedAtMs === "number" && Number.isSafeInteger(metadata.observedAtMs)
      && metadata.observedAtMs > 0 && metadata.observedAtMs <= latest) result.set(address, null);
    else result.set(address, parsePcMetadata(metadata, latest));
  }
  return result;
}
