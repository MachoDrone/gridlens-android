/** Android-only public roster selection. Credentials and mutations stay native. */
export interface MobileFleet { id: string; name: string; count: number; lastDiscoveredAtMs: number | null }
export interface MobileScenarios {
  enabled: boolean;
  selectedScenario: string;
  fleets: MobileFleet[];
  error: string | null;
}
export function isSavedFleetScenario(value: string | null): value is `fleet:${string}` {
  return value !== null && /^fleet:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
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
    if (value.schema !== 1 || !Array.isArray(value.fleets) || value.fleets.length > 100) throw new Error();
    const ids = new Set<string>();
    for (const item of value.fleets) {
      if (typeof item !== "object" || item === null || typeof item.id !== "string" || !isSavedFleetScenario(`fleet:${item.id}`)
        || ids.has(item.id) || typeof item.name !== "string" || !item.name.trim() || item.name.length > 80
        || /[\u0000-\u001f\u007f]/.test(item.name) || !Array.isArray(item.hosts)
        || !Number.isSafeInteger(item.updatedAtMs) || item.updatedAtMs < 0) throw new Error();
      ids.add(item.id);
      const discoveries = item.hosts.map((host: unknown) => {
        if (typeof host !== "object" || host === null || !("lastDiscoveredAtMs" in host)
          || typeof host.lastDiscoveredAtMs !== "number" || !Number.isSafeInteger(host.lastDiscoveredAtMs)
          || host.lastDiscoveredAtMs < 1 || host.lastDiscoveredAtMs > item.updatedAtMs) throw new Error();
        return host.lastDiscoveredAtMs;
      });
      result.fleets.push({ id: item.id, name: item.name, count: item.hosts.length,
        lastDiscoveredAtMs: discoveries.length ? Math.max(...discoveries) : null });
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
