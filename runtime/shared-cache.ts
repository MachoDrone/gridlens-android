import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { request as httpsRequest } from "node:https";
import { checkServerIdentity } from "node:tls";
import { join } from "node:path";
import { atomicPrivateJson, FLEET_ID, parseUniqueJson } from "./saved-fleets.ts";
import { FLEET_RESOURCES, isFleetRoster } from "../vendor/gridlens/src/contracts/fleet.ts";
import type { FleetContext, FleetResource, FleetSnapshots } from "../vendor/gridlens/src/contracts/fleet.ts";
import { emptyFleetSnapshots, normalizeFleetSnapshot, validateFleetContext } from "../vendor/gridlens/src/server/snapshot-store.ts";
import { parseSavedObservation, parsePcNetworkRecord, parseBinding, parseNodeGpu, normalizedIp, GPU_UUID, sameBinding } from "../vendor/gridlens/src/fleet/pc-observations.ts";
import type { NodeBinding, NodeGpuObservation, PcObservation, PcNetworkRecord } from "../vendor/gridlens/src/fleet/pc-observations.ts";

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === "object" && !Array.isArray(value);
const text = (value: unknown, limit = 256): value is string => typeof value === "string" && value.length > 0 && value.length <= limit && !/[\u0000-\u001f\u007f]/.test(value);
const time = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= 8_640_000_000_000_000;
const providerName = (value: unknown): value is string => typeof value === "string" && /^[a-z][a-z0-9-]{0,79}$/.test(value) && value !== "constructor" && value !== "prototype";
const MAX_BYTES = 16 * 1024 * 1024;
export type SharedStatus = "connected" | "unavailable" | "access-denied" | "identity-changed" | "invalid-response" | "provider-wait" | "roster-changed";
export interface SharedTrust { origin: string; serverId: string; fingerprint: string; caPem?: string }
export interface PairSharedInput extends SharedTrust { code: string; deviceName: string; name: string; localFleetId?: string }
interface Cached { snapshot: FleetSnapshots[FleetResource]; serverTimeMs: number; receivedAtMs: number }
interface PcAssociation { address: string; binding: NodeBinding; name: string; sshFingerprint: string; verifiedAtMs: number; privateIp: string | null; gpuUuids: string[] }
interface GpuSummary { address: string; sampleCount: number; fromAtMs: number; toAtMs: number; truncated: boolean }
interface Extras { associations: PcAssociation[]; observations: PcObservation[]; networks: PcNetworkRecord[]; gpuSummaries: GpuSummary[] }
interface SharedLink extends SharedTrust {
  id: string; name: string; localFleetId: string | null; pairedAtMs: number; deviceId: string; token: string;
  role: "viewer" | "operator"; context: FleetContext; capabilities: string[]; status: SharedStatus;
  lastContactAtMs: number | null; retryAtMs: number; cache: Partial<Record<FleetResource, Cached>>; extras: Extras;
  gpuHistory: Record<string, { binding: NodeBinding; gpu: NodeGpuObservation }>;
  remoteClock?: { serverTimeMs: number; receivedAtMs: number };
}
interface State { version: 1; selectedLinkId: string | null; links: SharedLink[]; providerWaits: Record<string, number> }
export class SharedCacheError extends Error { constructor(readonlyKind: SharedStatus) { super(readonlyKind); this.kind = readonlyKind; } readonly kind: SharedStatus }
export interface SharedTransportRequest { trust: SharedTrust; path: string; token?: string; context?: FleetContext; body?: object; signal?: AbortSignal }
export type SharedTransport = (request: SharedTransportRequest) => Promise<{ status: number; value: unknown; retryAtMs?: number }>;

export function validateSharedTrust(value: unknown): SharedTrust {
  if (!object(value) || !text(value.origin, 2048) || !text(value.serverId) || typeof value.fingerprint !== "string"
    || !/^[a-fA-F0-9]{64}$/.test(value.fingerprint)) throw new Error("Enter the HTTPS origin, server identity and SHA-256 certificate fingerprint shown in the backend terminal.");
  const origin = new URL(value.origin);
  if (origin.protocol !== "https:" || origin.username || origin.password || origin.pathname !== "/" || origin.search || origin.hash
    || origin.origin !== value.origin) throw new Error("Use the exact HTTPS origin without a path, query or credentials.");
  if (value.caPem !== undefined && (typeof value.caPem !== "string" || value.caPem.length > 32_768
    || !/^(?:\s*-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----\s*)+$/.test(value.caPem))) {
    throw new Error("Import only the backend's public CA certificate. Private keys are never accepted.");
  }
  return { origin: origin.origin, serverId: value.serverId, fingerprint: value.fingerprint.toLowerCase(),
    ...(value.caPem === undefined ? {} : { caPem: value.caPem as string }) };
}

/** CA and hostname verification are mandatory, followed by the independently paired leaf pin. */
export const requestSharedHttps: SharedTransport = async ({ trust, path, token, context, body, signal }) => {
  validateSharedTrust(trust);
  if (!/^\/api\/(fleet|shared)\/[A-Za-z0-9?&=.%_-]+$/.test(path)) throw new SharedCacheError("invalid-response");
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
    const headers: Record<string, string | number> = { Accept: "application/json" };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (context) { headers["X-GridLens-Scope"] = context.scopeId; headers["X-GridLens-Roster-Revision"] = context.rosterRevision; }
    if (payload) { headers["Content-Type"] = "application/json"; headers["Content-Length"] = payload.length; }
    const request = httpsRequest(new URL(path, trust.origin), { method: payload ? "POST" : "GET", headers,
      agent: false, rejectUnauthorized: true, ...(trust.caPem ? { ca: trust.caPem } : {}),
      checkServerIdentity(host, certificate) {
        const ordinary = checkServerIdentity(host, certificate);
        if (ordinary) return ordinary;
        return certificate.raw && createHash("sha256").update(certificate.raw).digest("hex") === trust.fingerprint
          ? undefined : new SharedCacheError("identity-changed");
      }, ...(signal ? { signal } : {}),
    }, response => {
      const chunks: Buffer[] = []; let size = 0;
      const status = response.statusCode ?? 502;
      const declared = response.headers["content-length"];
      if (declared !== undefined && (!/^\d+$/.test(declared) || Number(declared) > MAX_BYTES)
        || response.headers["content-type"]?.split(";", 1)[0] !== "application/json") {
        response.destroy(); reject(new SharedCacheError("invalid-response")); return;
      }
      response.on("data", (part: Buffer) => { size += part.length; if (size > MAX_BYTES) { response.destroy(); reject(new SharedCacheError("invalid-response")); } else chunks.push(part); });
      response.on("error", () => reject(new SharedCacheError("unavailable")));
      response.on("end", () => {
        try {
          const retry = response.headers["retry-after"];
          const serverDate = Date.parse(response.headers.date ?? "");
          const until = typeof retry === "string" ? /^\d+$/.test(retry) ? Date.now() + Number(retry) * 1000
            : Number.isFinite(serverDate) ? Date.now() + Math.max(0, Date.parse(retry) - serverDate) + 1000 : NaN : NaN;
          resolve({ status, value: parseUniqueJson(Buffer.concat(chunks).toString("utf8")), ...(time(until) ? { retryAtMs: until } : {}) });
        } catch { reject(new SharedCacheError("invalid-response")); }
      });
    });
    const timeout = setTimeout(() => request.destroy(new SharedCacheError("unavailable")), 12_000);
    request.once("close", () => clearTimeout(timeout));
    request.on("error", error => reject(error instanceof SharedCacheError ? error : new SharedCacheError(
      "code" in error && typeof error.code === "string" && /CERT|TLS|SELF_SIGNED/.test(error.code) ? "identity-changed" : "unavailable")));
    request.end(payload);
  });
};

function context(value: unknown): FleetContext {
  validateFleetContext(value);
  if (value.addresses.length > 10_000) throw new Error("Shared fleet exceeds capacity.");
  return { scopeId: value.scopeId, rosterRevision: value.rosterRevision, addresses: [...value.addresses] };
}
function capabilities(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > 64 || value.some(item => !text(item, 80)) || new Set(value).size !== value.length) throw new Error("Invalid shared capabilities.");
  return value as string[];
}
function validExtras(value: unknown, link: SharedLink): Extras {
  if (!object(value) || !Array.isArray(value.observations) || value.observations.length > 2048
    || !Array.isArray(value.networks) || value.networks.length > 2048 || !Array.isArray(value.associations) || value.associations.length > 2048) throw new Error("Invalid shared observations.");
  const associations = value.associations.map((raw): PcAssociation => {
    if (!object(raw) || typeof raw.address !== "string" || !link.context.addresses.includes(raw.address) || !text(raw.name)
      || typeof raw.sshFingerprint !== "string" || !/^SHA256:[A-Za-z0-9+/]{43}$/.test(raw.sshFingerprint)
      || !time(raw.verifiedAtMs) || !(raw.privateIp === null || typeof raw.privateIp === "string" && normalizedIp(raw.privateIp) !== null)
      || !Array.isArray(raw.gpuUuids) || raw.gpuUuids.length > 64 || raw.gpuUuids.some(uuid => typeof uuid !== "string" || !GPU_UUID.test(uuid))
      || new Set(raw.gpuUuids).size !== raw.gpuUuids.length) throw new Error("Invalid shared association.");
    return { address: raw.address, binding: parseBinding(raw.binding), name: raw.name, sshFingerprint: raw.sshFingerprint,
      verifiedAtMs: raw.verifiedAtMs, privateIp: raw.privateIp, gpuUuids: raw.gpuUuids as string[] };
  });
  const observations = value.observations.map(parseSavedObservation), networks = value.networks.map(parsePcNetworkRecord);
  if (observations.some(item => !link.context.addresses.includes(item.address)) || new Set(observations.map(item => item.address)).size !== observations.length
    || new Set(networks.map(item => item.pcId)).size !== networks.length) throw new Error("Invalid shared observation scope.");
  const pcs = new Set(associations.map(item => item.binding.pcId)), bindings = new Map(associations.map(item => [item.address, item.binding]));
  if (new Set(associations.map(item => item.address)).size !== associations.length || networks.some(item => !pcs.has(item.pcId))
    || observations.some(item => !bindings.has(item.address) || !sameBinding(bindings.get(item.address)!, item.binding))) throw new Error("Shared PC association is missing or changed.");
  if (value.gpuSummaries !== undefined && (!Array.isArray(value.gpuSummaries) || value.gpuSummaries.length > associations.length)) throw new Error("Invalid GPU summaries.");
  const gpuSummaries = (value.gpuSummaries as unknown[] | undefined ?? []).map(raw => {
    if (!object(raw) || typeof raw.address !== "string" || !bindings.has(raw.address) || !Number.isSafeInteger(raw.sampleCount)
      || Number(raw.sampleCount) < 0 || Number(raw.sampleCount) > 6000 || !time(raw.fromAtMs) || !time(raw.toAtMs)
      || raw.toAtMs < raw.fromAtMs || raw.toAtMs - raw.fromAtMs > 7 * 86_400_000 || typeof raw.truncated !== "boolean") throw new Error("Invalid GPU summary.");
    const gpu = observations.find(item => item.address === raw.address)?.gpu;
    if (!gpu || gpu.fromAtMs !== raw.fromAtMs || gpu.toAtMs !== raw.toAtMs) throw new Error("GPU summary source window changed.");
    return { address: raw.address, sampleCount: raw.sampleCount as number, fromAtMs: raw.fromAtMs, toAtMs: raw.toAtMs, truncated: raw.truncated };
  });
  if (new Set(gpuSummaries.map(row => row.address)).size !== gpuSummaries.length) throw new Error("Duplicate GPU summary.");
  return { associations, observations, networks, gpuSummaries };
}
function sameContext(left: FleetContext, right: FleetContext): boolean { return left.scopeId === right.scopeId && left.rosterRevision === right.rosterRevision && JSON.stringify(left.addresses) === JSON.stringify(right.addresses); }

/** The remote token and identity stay in app-private storage, never the WebView or local SSH vault. */
export class SharedCache {
  private state: State = { version: 1, selectedLinkId: null, links: [], providerWaits: {} };
  private writable = true;
  private chain: Promise<void> = Promise.resolve();
  private flights = new Map<string, Promise<unknown>>();
  private closed = false;
  private filename: string;
  private transport: SharedTransport;
  private now: () => number;
  private constructor(filename: string, transport: SharedTransport, now: () => number) { this.filename = filename; this.transport = transport; this.now = now; }
  static async load(directory: string, transport = requestSharedHttps, now = Date.now): Promise<SharedCache> {
    const store = new SharedCache(join(directory, "shared-cache.json"), transport, now);
    try {
      const bytes = await readFile(store.filename); if (bytes.length > 64 * 1024 * 1024) throw new Error();
      const value = parseUniqueJson(bytes.toString("utf8"));
      if (!object(value) || value.version !== 1 || !Array.isArray(value.links) || value.links.length > 20 || !object(value.providerWaits)
        || !(value.selectedLinkId === null || typeof value.selectedLinkId === "string")) throw new Error();
      const links = value.links.map(raw => {
        if (!object(raw) || typeof raw.id !== "string" || !FLEET_ID.test(raw.id) || !text(raw.name, 80) || !time(raw.pairedAtMs)
          || !text(raw.deviceId) || !text(raw.token, 512) || !["viewer", "operator"].includes(String(raw.role))
          || !(raw.localFleetId === null || typeof raw.localFleetId === "string" && FLEET_ID.test(raw.localFleetId))
          || !["connected", "unavailable", "access-denied", "identity-changed", "invalid-response", "provider-wait", "roster-changed"].includes(String(raw.status))
          || !(raw.lastContactAtMs === null || time(raw.lastContactAtMs)) || !time(raw.retryAtMs) || !object(raw.cache)) throw new Error();
        const link = { ...raw, ...validateSharedTrust(raw), context: context(raw.context), capabilities: capabilities(raw.capabilities), cache: {} } as unknown as SharedLink;
        for (const [resource, entry] of Object.entries(raw.cache)) {
          if (!(FLEET_RESOURCES as readonly string[]).includes(resource) || !object(entry) || !time(entry.serverTimeMs) || !time(entry.receivedAtMs)) throw new Error();
          link.cache[resource as FleetResource] = { snapshot: normalizeFleetSnapshot(resource as FleetResource, entry.snapshot, link.context.addresses), serverTimeMs: entry.serverTimeMs, receivedAtMs: entry.receivedAtMs };
        }
        link.extras = validExtras(raw.extras, link); link.gpuHistory = {};
        if (raw.remoteClock !== undefined && (!object(raw.remoteClock) || !time(raw.remoteClock.serverTimeMs) || !time(raw.remoteClock.receivedAtMs))) throw new Error();
        if (!object(raw.gpuHistory)) throw new Error();
        for (const [address, entry] of Object.entries(raw.gpuHistory)) {
          if (!link.context.addresses.includes(address) || !object(entry)) throw new Error();
          link.gpuHistory[address] = { binding: parseBinding(entry.binding), gpu: parseNodeGpu(entry.gpu, address) };
        }
        return link;
      });
      if (new Set(links.map(link => link.id)).size !== links.length || value.selectedLinkId !== null && !links.some(link => link.id === value.selectedLinkId)) throw new Error();
      for (const [provider, deadline] of Object.entries(value.providerWaits)) if (!providerName(provider) || !time(deadline)) throw new Error();
      store.state = { version: 1, selectedLinkId: value.selectedLinkId, links, providerWaits: value.providerWaits as Record<string, number> };
    } catch (error) {
      if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) store.writable = false;
    }
    return store;
  }
  private async change(update: (state: State) => void): Promise<void> {
    const operation = this.chain.then(async () => {
      if (!this.writable || this.closed) throw new Error("Shared connection storage needs recovery. Existing records were preserved.");
      const next = structuredClone(this.state); update(next);
      if (Buffer.byteLength(JSON.stringify(next)) > 64 * 1024 * 1024) throw new Error("Shared cache capacity reached.");
      try { await atomicPrivateJson(this.filename, next); } catch (error) { this.writable = false; throw error; }
      this.state = next;
    });
    this.chain = operation.catch(() => {}); await operation;
  }
  has(id: string): boolean { return this.writable && this.state.links.some(link => link.id === id); }
  selected(): string | null { return this.state.selectedLinkId; }
  get warning(): string | null { return this.writable ? null : "Shared connection storage needs recovery. Existing files were preserved; automatic fallback is disabled."; }
  private link(id: string): SharedLink { const value = this.state.links.find(link => link.id === id); if (!value || !this.writable) throw new Error("Unknown shared connection."); return structuredClone(value); }
  context(id: string): FleetContext { return this.link(id).context; }
  namespace(id: string): string { const link = this.link(id); return JSON.stringify([link.serverId, link.context.scopeId, link.context.rosterRevision]); }
  list() {
    return this.state.links.map(link => ({ id: link.id, name: `${link.name} · Shared cache`, updatedAtMs: link.pairedAtMs,
      hosts: link.context.addresses.map(address => ({ address })), shared: { serverId: link.serverId, scopeId: link.context.scopeId,
        rosterRevision: link.context.rosterRevision, status: link.status, lastContactAtMs: link.lastContactAtMs, retryAtMs: link.retryAtMs,
        localFleetId: link.localFleetId, pcs: link.extras.associations.map(item => ({ address: item.address, pcId: item.binding.pcId, name: item.name, verifiedAtMs: item.verifiedAtMs })) } }));
  }
  async pair(value: unknown): Promise<string> {
    const trust = validateSharedTrust(value);
    if (!object(value) || !text(value.code, 128) || !text(value.deviceName, 80) || !text(value.name, 64)
      || value.localFleetId !== undefined && (typeof value.localFleetId !== "string" || !FLEET_ID.test(value.localFleetId))) throw new Error("Enter a connection code, device and fleet name.");
    if (!this.writable || this.state.links.length >= 20) throw new Error("Shared connection storage is unavailable or full.");
    const response = await this.transport({ trust, path: "/api/shared/pair", body: { version: 1, code: value.code, deviceName: value.deviceName } });
    const result = response.value;
    if (response.status !== 200 || !object(result) || result.version !== 1 || result.serverId !== trust.serverId
      || !text(result.deviceId) || !text(result.token, 512) || !["viewer", "operator"].includes(String(result.role))) throw new Error("Pairing failed. Check the code, trusted certificate and server identity in the backend terminal.");
    const id = randomUUID();
    const link: SharedLink = { ...trust, id, name: value.name, localFleetId: value.localFleetId as string | undefined ?? null,
      pairedAtMs: this.now(), deviceId: result.deviceId, token: result.token, role: result.role as "viewer" | "operator",
      context: context(result.context), capabilities: capabilities(result.capabilities), status: "connected", lastContactAtMs: this.now(), retryAtMs: 0, cache: {}, extras: { associations: [], observations: [], networks: [], gpuSummaries: [] }, gpuHistory: {} };
    await this.change(state => { state.links.push(link); state.selectedLinkId = id; }); return id;
  }
  async select(id: string | null): Promise<void> { if (id !== null) this.link(id); await this.change(state => { state.selectedLinkId = id; }); }
  async forget(id: string): Promise<void> { await this.change(state => { state.links = state.links.filter(link => link.id !== id); if (state.selectedLinkId === id) state.selectedLinkId = null; }); }
  async standalone(id: string): Promise<string> { const link = this.link(id); await this.select(null); return link.localFleetId ? `fleet:${link.localFleetId}` : "network-discovery"; }
  /** A known remote wait is never shortened by switching modes or forgetting the connection. */
  standaloneWait(resource: string): number {
    if (!this.writable) return 8_640_000_000_000_000;
    const aliases = resource === "sol" || resource === "nos" || resource === "staking" ? [resource, "solana", "rpc"]
      : resource === "jobs" || resource === "job-history" ? [resource, "jobs", "indexer"] : [resource, "nosana", "indexer"];
    const known = new Set(["metadata", "node-info", "jobs", "job-history", "queue", "sol", "nos", "staking", "solana", "rpc", "nosana", "indexer"]);
    return Math.max(0, ...Object.entries(this.state.providerWaits).filter(([key]) => resource === "all" || aliases.includes(key) || !known.has(key)).map(([, value]) => value));
  }
  private async response(link: SharedLink, path: string, body?: object) {
    if (link.retryAtMs > this.now()) throw new SharedCacheError("provider-wait");
    const response = await this.transport({ trust: link, path, token: link.token, context: link.context, ...(body ? { body } : {}) });
    if (response.retryAtMs && response.retryAtMs > 0) await this.change(state => {
      const current = state.links.find(item => item.id === link.id); if (current) current.retryAtMs = Math.max(current.retryAtMs, response.retryAtMs!);
    });
    if (response.status === 401 || response.status === 403) throw new SharedCacheError("access-denied");
    if (response.status === 409) throw new SharedCacheError("roster-changed");
    if (response.status === 429 || response.status === 503 && response.retryAtMs) throw new SharedCacheError("provider-wait");
    if (response.status !== 200 || !object(response.value)) throw new SharedCacheError("invalid-response");
    return response.value;
  }
  async refreshStatus(id: string): Promise<void> {
    const key = `${id}:status`;
    const existing = this.flights.get(key); if (existing) { await existing; return; }
    const work = (async () => {
      const link = this.link(id);
      try {
        const result = await this.response(link, "/api/shared/status");
        if (result.version !== 1 || result.serverId !== link.serverId) throw new SharedCacheError("identity-changed");
        if (!time(result.serverTimeMs)) throw new SharedCacheError("invalid-response");
        const serverTimeMs = result.serverTimeMs, receivedAtMs = this.now();
        if (!sameContext(context(result.context), link.context)) throw new SharedCacheError("roster-changed");
        if (!Array.isArray(result.providerWaits) || result.providerWaits.length > 64) throw new SharedCacheError("invalid-response");
        const waits = result.providerWaits.map(raw => {
          if (!object(raw) || !providerName(raw.provider) || !time(raw.retryAtMs)) throw new SharedCacheError("invalid-response");
          return { provider: raw.provider, retryAtMs: raw.retryAtMs };
        });
        const offered = capabilities(result.capabilities);
        await this.change(state => {
          const current = state.links.find(item => item.id === id); if (!current) return;
          current.status = "connected"; current.lastContactAtMs = this.now(); current.capabilities = offered;
          current.remoteClock = { serverTimeMs, receivedAtMs };
          for (const wait of waits) if (wait.retryAtMs > serverTimeMs) state.providerWaits[wait.provider] = Math.max(state.providerWaits[wait.provider] ?? 0,
            Math.min(8_640_000_000_000_000, receivedAtMs + wait.retryAtMs - serverTimeMs));
        });
      } catch (error) { await this.failed(id, error); throw error; }
    })().finally(() => this.flights.delete(key));
    this.flights.set(key, work); await work;
  }
  private async failed(id: string, error: unknown): Promise<void> {
    await this.change(state => { const current = state.links.find(item => item.id === id); if (current) current.status = error instanceof SharedCacheError ? error.kind : "invalid-response"; });
  }
  async read(id: string, resource: FleetResource, selected?: readonly string[], refresh = false): Promise<Record<string, unknown>> {
    const key = `${id}:${resource}:${JSON.stringify(selected ?? [])}:${refresh}`;
    const active = this.flights.get(key); if (active) return await active as Record<string, unknown>;
    const work = this.readOne(id, resource, selected, refresh).finally(() => this.flights.delete(key)); this.flights.set(key, work); return await work;
  }
  private async readOne(id: string, resource: FleetResource, selected?: readonly string[], refresh = false): Promise<Record<string, unknown>> {
    const link = this.link(id), addresses = selected ?? link.context.addresses;
    if (!isFleetRoster([...addresses]) || addresses.some(address => !link.context.addresses.includes(address))) throw new Error("Invalid shared host selection.");
    let result: Cached | undefined;
    try {
      await this.refreshStatus(id);
      const query = resource === "job-history" ? "?" + new URLSearchParams([...(refresh ? [["refresh", "1"]] : []), ...addresses.map(address => ["host", address])]).toString() : "";
      const raw = await this.response(this.link(id), `/api/fleet/${resource}.json${query}`);
      if (!time(raw.serverTimeMs)) throw new SharedCacheError("invalid-response");
      const snapshot = normalizeFleetSnapshot(resource, raw, addresses);
      result = { snapshot, serverTimeMs: raw.serverTimeMs, receivedAtMs: this.now() };
      await this.change(state => {
        const current = state.links.find(item => item.id === id); if (!current || !sameContext(current.context, link.context)) return;
        const previous = current.cache[resource]?.snapshot ?? emptyFleetSnapshots(current.context.addresses)[resource];
        const updated = new Map(snapshot.hosts.map(host => [host.address, host]));
        current.cache[resource] = { ...result!, snapshot: normalizeFleetSnapshot(resource, { ...snapshot, hosts: previous.hosts.map(host => updated.get(host.address) ?? host) }, current.context.addresses) };
        // Provider retry deadlines present in the existing contracts remain durable across mode switches.
        const collect = (value: unknown): void => {
          if (Array.isArray(value)) { value.forEach(collect); return; }
          if (!object(value)) return;
          for (const [field, child] of Object.entries(value)) {
            if (field === "retryAtMs" && time(child) && child > result!.serverTimeMs) state.providerWaits[resource] = Math.max(state.providerWaits[resource] ?? 0,
              Math.min(8_640_000_000_000_000, result!.receivedAtMs + child - result!.serverTimeMs));
            else if (typeof child === "object") collect(child);
          }
        }; collect(snapshot);
      });
      return { ...snapshot, serverTimeMs: result.serverTimeMs };
    } catch (error) {
      await this.failed(id, error);
      const current = this.link(id), cached = current.cache[resource]?.snapshot ?? emptyFleetSnapshots(current.context.addresses)[resource];
      const filtered = { ...structuredClone(cached), hosts: structuredClone(cached.hosts.filter(row => addresses.includes(row.address))) };
      for (const row of filtered.hosts) if ("retained" in row) row.retained = true;
      // No cached transport clock is replayed as a fresh server time.
      return { ...filtered, ...(resource === "metadata" ? {} : { nextRefreshAtMs: Math.max(current.retryAtMs, this.now() + 30_000) }), sharedRetained: true };
    }
  }
  async observations(id: string, address?: string): Promise<{ schema: 1; fleetId: string; error: string | null; pcNetworks: PcNetworkRecord[]; host?: PcObservation | null; hosts?: object[] }> {
    const link = this.link(id);
    if (address && !link.context.addresses.includes(address)) throw new Error("Invalid shared host.");
    try {
      await this.refreshStatus(id);
      const raw = await this.response(link, "/api/shared/observations");
      if (raw.version !== 1 || raw.serverId !== link.serverId || raw.scopeId !== link.context.scopeId || raw.rosterRevision !== link.context.rosterRevision) throw new SharedCacheError("roster-changed");
      const extras = validExtras(raw, link);
      await this.change(state => {
        const current = state.links.find(item => item.id === id); if (!current) return;
        current.extras = extras;
        for (const [host, history] of Object.entries(current.gpuHistory)) {
          const association = extras.associations.find(item => item.address === host);
          if (!association || !sameBinding(association.binding, history.binding)) delete current.gpuHistory[host];
        }
        const receipt = this.now(), remoteTime = current.remoteClock?.serverTimeMs;
        const keepWait = (provider: string, deadline: number): void => {
          if (remoteTime === undefined || deadline <= remoteTime) return;
          state.providerWaits[provider] = Math.max(state.providerWaits[provider] ?? 0, Math.min(8_640_000_000_000_000, receipt + deadline - remoteTime));
        };
        for (const network of extras.networks) if (network.network.retryAtMs) keepWait("pc-network", network.network.retryAtMs);
        for (const observation of extras.observations) if (observation.rewards?.retryAfterSeconds) {
          const deadline = Math.min(8_640_000_000_000_000, observation.rewards.observedAtMs + observation.rewards.retryAfterSeconds * 1000);
          keepWait("rewards", deadline);
        }
      });
      if (address) {
        const summary = extras.observations.find(item => item.address === address), gpu = summary?.gpu;
        if (summary && gpu && gpu.toAtMs >= gpu.fromAtMs) {
          const history = await this.response(this.link(id), "/api/shared/gpu-history", { address, fromAtMs: gpu.fromAtMs, toAtMs: gpu.toAtMs });
          if (history.version !== 1 || history.serverId !== link.serverId || history.scopeId !== link.context.scopeId
            || history.rosterRevision !== link.context.rosterRevision || history.address !== address) throw new SharedCacheError("roster-changed");
          if (history.gpu !== null) {
            const parsed = parseNodeGpu(history.gpu, address);
            if (parsed.fromAtMs < gpu.fromAtMs || parsed.toAtMs > gpu.toAtMs || JSON.stringify(parsed.gpuUuids) !== JSON.stringify(gpu.gpuUuids)) throw new SharedCacheError("invalid-response");
            await this.change(state => { const current = state.links.find(item => item.id === id); if (current) current.gpuHistory[address] = { binding: summary.binding, gpu: parsed }; });
          }
        }
      }
    } catch (error) { await this.failed(id, error); }
    const current = this.link(id);
    const host = address ? structuredClone(current.extras.observations.find(item => item.address === address)) : undefined;
    const history = address ? current.gpuHistory[address] : undefined;
    if (host && history && sameBinding(host.binding, history.binding)) host.gpu = history.gpu;
    return { schema: 1, fleetId: id, error: current.status === "connected" ? null : current.status, pcNetworks: current.extras.networks,
      ...(address ? { host: host ?? null }
        : { hosts: current.extras.observations.map(item => { if (!item.gpu) return item; const { samples: _samples, ...gpu } = item.gpu;
          return { ...item, gpu: { ...gpu, sampleCount: current.extras.gpuSummaries.find(row => row.address === item.address)?.sampleCount ?? null } }; }) }) };
  }
  close(): void { this.closed = true; }
}
