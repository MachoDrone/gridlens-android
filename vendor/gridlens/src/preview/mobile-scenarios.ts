import { formatIPDisplay } from "../fleet/ip-display.ts";
import type { IPAddresses, IPDisplayMode } from "../fleet/ip-display.ts";
import { parseGpuTelemetry } from "./mobile-telemetry.ts";
import type { GpuTelemetry } from "./mobile-telemetry.ts";
/** Android-only public roster selection. Credentials and mutations stay native. */
export interface MobilePc {
  pcId: string; pcName: string | null; ipAddresses: string[]; observedAtMs: number; gpuTelemetry?: GpuTelemetry;
}
export interface MobileSharedFleet { serverId: string; scopeId: string; rosterRevision: string; status: string; lastContactAtMs: number | null; retryAtMs: number; localFleetId: string | null }
export interface MobileFleet { id: string; name: string; count: number; lastDiscoveredAtMs: number | null; pcs: Record<string, MobilePc>; shared?: MobileSharedFleet }
export function parseMobileShared(value: unknown): MobileSharedFleet {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid shared fleet.");
  const item = value as Record<string, unknown>;
  if ([item.serverId, item.scopeId, item.rosterRevision].some(part => typeof part !== "string" || !part || part.length > 256 || /[\u0000-\u001f\u007f]/.test(part))
    || !["connected", "unavailable", "access-denied", "identity-changed", "invalid-response", "provider-wait", "roster-changed"].includes(String(item.status))
    || !(item.lastContactAtMs === null || Number.isSafeInteger(item.lastContactAtMs) && Number(item.lastContactAtMs) >= 0)
    || !Number.isSafeInteger(item.retryAtMs) || Number(item.retryAtMs) < 0
    || !(item.localFleetId === null || typeof item.localFleetId === "string" && isSavedFleetScenario(`fleet:${item.localFleetId}`))) throw new Error("Invalid shared fleet.");
  return { serverId: item.serverId as string, scopeId: item.scopeId as string, rosterRevision: item.rosterRevision as string,
    status: item.status as string, lastContactAtMs: item.lastContactAtMs as number | null, retryAtMs: item.retryAtMs as number, localFleetId: item.localFleetId as string | null };
}
export function mobilePcAddresses(pc: MobilePc | undefined): { internal: string | null; global: string | null } {
  const local = (ip: string): boolean => /^(?:10\.|192\.168\.|169\.254\.|172\.(?:1[6-9]|2[0-9]|3[01])\.|100\.(?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.|f[cd]|fe[89ab])/i.test(ip);
  return { internal: pc?.ipAddresses.find(local) ?? null, global: pc?.ipAddresses.find(ip => !local(ip)) ?? null };
}

/** Keep the original preference values, adding full single-address modes on Android. */
export type MobileIPDisplayMode = IPDisplayMode | "public-full" | "private-full";
const MOBILE_IP_MODES = ["dot", "full", "public-full", "ext", "private-full", "int"] as const;
export function normalizeMobileIPMode(value: unknown): MobileIPDisplayMode {
  return MOBILE_IP_MODES.find(mode => mode === value) ?? "dot";
}
export function nextMobileIPMode(mode: MobileIPDisplayMode): MobileIPDisplayMode {
  return MOBILE_IP_MODES[(MOBILE_IP_MODES.indexOf(mode) + 1) % MOBILE_IP_MODES.length]!;
}
export function mobileIPModeLabel(mode: MobileIPDisplayMode): string {
  return { dot: "hidden", full: "both full addresses", "public-full": "external IP / NAT",
    ext: "external last octet", "private-full": "internal IP", int: "internal last octet" }[mode];
}
export function formatMobileIPDisplay(addresses: IPAddresses, mode: MobileIPDisplayMode): { text: string; description: string } {
  if (mode === "dot") return { text: "•", description: "IP addresses hidden." };
  const global = addresses.global?.trim() || null, internal = addresses.internal?.trim() || null;
  const description = `External/public: ${global ?? "not reported"}. Internal/private: ${internal ?? "not reported"}.`;
  if (mode === "full") return { text: `${global ?? "−"} / ${internal ?? "−"}`, description };
  if (mode === "public-full") return { text: global ?? "−", description };
  if (mode === "private-full") return { text: internal ?? "−", description };
  const base = formatIPDisplay({ global, internal }, mode);
  return { text: base.text === "—" ? "−" : base.text, description };
}

export interface MobileScenarios {
  enabled: boolean;
  selectedScenario: string;
  fleets: MobileFleet[];
  error: string | null;
}
export function isSavedFleetScenario(value: string | null): value is `fleet:${string}` {
  return value !== null && /^fleet:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
}
/** Only a saved fleet/PC identity may select the fixed native IP-check screen. */
export function mobilePcIpCheckHref(fleetId: unknown, pcId: unknown): string | null {
  if (typeof fleetId !== "string" || !isSavedFleetScenario(`fleet:${fleetId}`)
    || typeof pcId !== "string" || !/^[a-f0-9]{64}$/.test(pcId)) return null;
  return `/mobile/ip-check?fleet=${fleetId}&pc=${pcId}`;
}
export async function readMobileScenarios(document: Pick<Document, "querySelector">): Promise<MobileScenarios> {
  const result: MobileScenarios = { enabled: false, selectedScenario: "network-discovery", fleets: [], error: null };
  if (document.querySelector<HTMLMetaElement>('meta[name="gridlens-mobile"]')?.content !== "android") return result;
  result.enabled = true;
  try {
    const response = await fetch("/mobile/fleets", { credentials: "same-origin", cache: "no-store", signal: AbortSignal.timeout(8000) });
    if (!response.ok || !response.body) throw new Error();
    const reader = response.body.getReader();
    let bytes = 0;
    const chunks: Uint8Array[] = [];
    try {
      for (;;) {
        const part = await reader.read();
        if (part.done) break;
        bytes += part.value.byteLength;
        if (bytes > 8 * 1024 * 1024) throw new Error();
        chunks.push(part.value);
      }
    } finally { await reader.cancel(); }
    const body = new Uint8Array(bytes);
    let offset = 0;
    for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
    const value = JSON.parse(new TextDecoder().decode(body)) as Record<string, unknown>;
    if (value.schema !== 1 || !Array.isArray(value.fleets) || value.fleets.length > 120) throw new Error();
    const ids = new Set<string>();
    for (const item of value.fleets) {
      if (typeof item !== "object" || item === null || typeof item.id !== "string" || !isSavedFleetScenario(`fleet:${item.id}`)
        || ids.has(item.id) || typeof item.name !== "string" || !item.name.trim() || item.name.length > 80
        || /[\u0000-\u001f\u007f]/.test(item.name) || !Array.isArray(item.hosts)
        || !Number.isSafeInteger(item.updatedAtMs) || item.updatedAtMs < 0) throw new Error();
      ids.add(item.id);
      const pcs: Record<string, MobilePc> = Object.create(null) as Record<string, MobilePc>;
      if (item.shared !== undefined) {
        const shared = parseMobileShared(item.shared);
        if (item.hosts.length > 10_000 || item.hosts.some((host: unknown) => !host || typeof host !== "object" || !("address" in host) || typeof host.address !== "string")) throw new Error();
        result.fleets.push({ id: item.id, name: item.name, count: item.hosts.length, pcs, lastDiscoveredAtMs: null, shared });
        continue;
      }
      const discoveries = item.hosts.map((host: unknown) => {
        if (typeof host !== "object" || host === null || !("lastDiscoveredAtMs" in host)
          || typeof host.lastDiscoveredAtMs !== "number" || !Number.isSafeInteger(host.lastDiscoveredAtMs)
          || host.lastDiscoveredAtMs < 1 || host.lastDiscoveredAtMs > item.updatedAtMs) throw new Error();
        if ("pcMetadata" in host && "address" in host && typeof host.address === "string") {
          const pc = host.pcMetadata as MobilePc;
          if (pc === null || typeof pc !== "object" || typeof pc.pcId !== "string" || !/^[a-f0-9]{64}$/.test(pc.pcId)
            || !(pc.pcName === null || typeof pc.pcName === "string" && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(pc.pcName))
            || !Array.isArray(pc.ipAddresses) || pc.ipAddresses.length < 1 || pc.ipAddresses.length > 32
            || pc.ipAddresses.some(ip => typeof ip !== "string" || ip.length > 45 || !/^[a-fA-F0-9:.]+$/.test(ip))
            || !Number.isSafeInteger(pc.observedAtMs) || pc.observedAtMs < 1 || pc.observedAtMs > host.lastDiscoveredAtMs) throw new Error();
          if (pc.gpuTelemetry !== undefined) pc.gpuTelemetry = parseGpuTelemetry(pc.gpuTelemetry);
          pcs[host.address] = pc;
        }
        return host.lastDiscoveredAtMs;
      });
      result.fleets.push({ id: item.id, name: item.name, count: item.hosts.length,
        pcs, lastDiscoveredAtMs: discoveries.length ? Math.max(...discoveries) : null });
    }
    if (typeof value.selectedScenario === "string"
      && (["network-discovery", "supplied", "real200", "online-gpu-hosts"].includes(value.selectedScenario)
        || isSavedFleetScenario(value.selectedScenario) && ids.has(value.selectedScenario.slice(6)))) {
      result.selectedScenario = value.selectedScenario;
    }
    if (value.error !== null) result.error = "Saved fleets need attention. Open Fleets to review recovery before making changes.";
  } catch {
    result.fleets = [];
    result.error = "Saved fleets could not be loaded. Reopen Fleets to retry; saved records have not been removed.";
  }
  return result;
}
