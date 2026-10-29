import { randomBytes, timingSafeEqual } from "node:crypto";
import { mkdir } from "node:fs/promises";
import type { IncomingMessage, Server, ServerResponse } from "node:http";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startPreviewServer } from "../vendor/gridlens/scripts/preview-server.ts";
import { SavedFleets, atomicPrivateJson, parseUniqueJson } from "./saved-fleets.ts";
import { PcObservations } from "./pc-observations.ts";
import { isMetadataAddress } from "../vendor/gridlens/src/fleet/host-metadata.ts";
import { SharedCache } from "./shared-cache.ts";
import type { SharedTransport } from "./shared-cache.ts";
import { FLEET_RESOURCES } from "../vendor/gridlens/src/contracts/fleet.ts";
import type { FleetResource } from "../vendor/gridlens/src/contracts/fleet.ts";
import { selectJobHistoryHosts } from "../vendor/gridlens/src/server/job-history-selection.ts";

export const MAX_ROSTER_BYTES = 512 * 1024;
export const MAX_ROSTER_HOSTS = 10_000;
export const SESSION_COOKIE = "gridlens_session";
type Scenario = "supplied" | "real200";
type PreviewOptions = NonNullable<Parameters<typeof startPreviewServer>[0]>;

export interface RuntimeOptions {
  dataDirectory: string;
  directory?: string;
  preferredPort?: number;
  /** Isolated verification only; production uses the original public readers. */
  providers?: Pick<PreviewOptions, "metadataProvider" | "nodeInfoProvider" | "jobProvider" | "queueProvider" |
    "solProvider" | "nosProvider" | "stakingProvider" | "onlineProvider" | "now">;
  /** Isolated generated-server verification only. Production always uses verified HTTPS. */
  sharedTransport?: SharedTransport;
}

export function parseRoster(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > MAX_ROSTER_HOSTS) {
    throw new Error(`Choose a JSON array containing at most ${MAX_ROSTER_HOSTS} public host addresses.`);
  }
  const addresses: string[] = [];
  const unique = new Set<string>();
  for (const address of value) {
    if (!isMetadataAddress(address)) throw new Error("Every entry must be a public base58 address decoding to 32 bytes.");
    if (unique.has(address)) throw new Error("The host list contains a duplicate address.");
    unique.add(address);
    addresses.push(address);
  }
  return addresses;
}

function equalToken(value: string | undefined, expected: string): boolean {
  if (value === undefined || value.length !== expected.length) return false;
  const bytes = Buffer.from(value, "utf8");
  const secret = Buffer.from(expected, "utf8");
  return bytes.length === secret.length && timingSafeEqual(bytes, secret);
}

function cookieToken(request: IncomingMessage): string | undefined {
  const matches = request.headers.cookie?.split(";")
    .map(item => item.trim()).filter(item => item.startsWith(`${SESSION_COOKIE}=`));
  return matches?.length === 1 ? matches[0]!.slice(SESSION_COOKIE.length + 1) : undefined;
}

function reply(response: ServerResponse, code: number, value: object): void {
  if (response.destroyed || response.writableEnded) return;
  const body = JSON.stringify(value);
  response.writeHead(code, { "Content-Type": "application/json; charset=utf-8", "Content-Length": Buffer.byteLength(body) });
  response.end(body);
}

async function readBody(request: IncomingMessage, maximum = MAX_ROSTER_BYTES): Promise<unknown> {
  const size = request.headers["content-length"];
  if (size !== undefined && (!/^\d+$/.test(size) || Number(size) > maximum)) {
    throw new Error("The request exceeds its size limit.");
  }
  if (request.headers["content-type"]?.split(";", 1)[0]?.trim() !== "application/json") {
    throw new Error("Choose a JSON array of public host addresses.");
  }
  let length = 0;
  const parts: Buffer[] = [];
  const timeout = setTimeout(() => request.destroy(), 8_000);
  try {
    for await (const part of request) {
      const bytes = Buffer.isBuffer(part) ? part : Buffer.from(part);
      length += bytes.length;
      if (length > maximum) throw new Error("The request exceeds its size limit.");
      parts.push(bytes);
    }
    try { return parseUniqueJson(Buffer.concat(parts).toString("utf8")); }
    catch { throw new Error("Choose a valid JSON array of public host addresses."); }
  } finally { clearTimeout(timeout); }
}

export async function startAndroidRuntime({ dataDirectory, directory = fileURLToPath(new URL("./web/", import.meta.url)),
  preferredPort = 4173, providers = {}, sharedTransport }: RuntimeOptions): Promise<{ server: Server; port: number; token: string }> {
  if (!Number.isInteger(preferredPort) || preferredPort < 0 || preferredPort > 65535) throw new Error("Invalid preferred port.");
  const privateDirectory = resolve(dataDirectory);
  await mkdir(privateDirectory, { recursive: true, mode: 0o700 });
  const token = randomBytes(32).toString("hex");
  const savedFleets = await SavedFleets.load(privateDirectory, providers.now);
  const observations = await PcObservations.load(privateDirectory, providers.now);
  const shared = await SharedCache.load(privateDirectory, sharedTransport, providers.now);
  const active = new Map<ServerResponse, string>();
  let updateSavedPins: (() => void) | undefined;
  const importedThisProcess = new Set<string>();
  let replaceRoster: ((scenario: Scenario, addresses: readonly string[]) => void) | undefined;
  let importChain = Promise.resolve();
  const rosterFile = (scenario: Scenario): string => join(privateDirectory, scenario === "supplied" ? "hosts.json" : "real200.json");
  const listing = () => ({ ...savedFleets.list(), fleets: [...savedFleets.list().fleets, ...shared.list()],
    ...(shared.selected() ? { selectedScenario: `fleet:${shared.selected()}` } : {}), sharedError: shared.warning });

  const authorize = (request: IncomingMessage, response: ServerResponse): boolean => {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'none'");
    const host = `127.0.0.1:${request.socket.localPort}`;
    const origin = `http://${host}`;
    const suppliedHeader = request.headers["x-gridlens-token"];
    const native = typeof suppliedHeader === "string" && equalToken(suppliedHeader, token);
    const site = request.headers["sec-fetch-site"];
    if (request.headers.host !== host || request.socket.remoteAddress !== "127.0.0.1" ||
        (request.headers.origin !== undefined && request.headers.origin !== origin) ||
        (site !== undefined && site !== "same-origin" && site !== "none") ||
        (!native && !equalToken(cookieToken(request), token))) {
      reply(response, 403, { error: "This endpoint is available only to this GridLens app session." });
      return false;
    }
    const url = request.url ?? "";
    if (url === "/mobile/shared/waits" && request.method === "GET") {
      reply(response, 200, { version: 1, retryAtMs: shared.standaloneWait("all") }); return false;
    }
    if ((url === "/mobile/shared" || url.startsWith("/mobile/shared?")) && request.method === "GET") {
      reply(response, 200, { version: 1, links: shared.list(), selectedLinkId: shared.selected(), error: shared.warning });
      return false;
    }
    if (url === "/mobile/shared" && request.method === "POST" && native) {
      void readBody(request, 64 * 1024).then(async value => {
        if (!value || typeof value !== "object" || Array.isArray(value) || !("op" in value)) throw new Error();
        let scenario: string | undefined;
        if (value.op === "pair" && "configuration" in value) {
          const input = value.configuration;
          if (input && typeof input === "object" && "localFleetId" in input &&
            (typeof input.localFleetId !== "string" || !savedFleets.get(input.localFleetId))) throw new Error();
          scenario = `fleet:${await shared.pair(input)}`;
        } else if ("id" in value && typeof value.id === "string" && shared.has(value.id)) {
          if (value.op === "retry") { await shared.refreshStatus(value.id); scenario = `fleet:${value.id}`; await shared.select(value.id); }
          else if (value.op === "standalone") {
            scenario = await shared.standalone(value.id);
            if (scenario.startsWith("fleet:") && !savedFleets.get(scenario.slice(6))) scenario = "network-discovery";
            await savedFleets.mutate({ op: "select", scenario });
          }
          else if (value.op === "forget") { await shared.forget(value.id); scenario = savedFleets.list().selectedScenario; }
          else throw new Error();
        } else throw new Error();
        for (const pending of active.keys()) pending.destroy();
        reply(response, 200, { ok: true, ...listing(), selectedScenario: scenario });
      }).catch(() => { reply(response, 400, { error: "Shared connection could not be changed. Check the code, trusted certificate and server identity. Existing fleets and credentials remain saved." }); request.resume(); });
      return false;
    }
    if (url.startsWith("/preview/") && request.method === "GET") {
      const parsed = new URL(url, origin), scenario = parsed.searchParams.get("scenario");
      const id = scenario?.startsWith("fleet:") ? scenario.slice(6) : "";
      const resource = parsed.pathname.slice("/preview/".length).replace(/\.json$/, "") as FleetResource;
      if (shared.has(id)) {
        const context = shared.context(id);
        if (parsed.pathname === "/preview/hosts.json") {
          reply(response, 200, { addresses: context.addresses, error: null, rosterRevision: context.rosterRevision });
        } else if ((FLEET_RESOURCES as readonly string[]).includes(resource)) {
          let selected: { addresses: string[]; refresh: boolean } | undefined;
          try {
            if (parsed.searchParams.getAll("scenario").length !== 1) throw new Error();
            if (resource === "job-history") selected = selectJobHistoryHosts(parsed.searchParams, context.addresses, ["scenario"]);
            else if ([...parsed.searchParams.keys()].some(key => key !== "scenario" && key !== "refresh")) throw new Error();
          } catch { reply(response, 400, { error: "Choose a bounded selection from this shared fleet." }); return false; }
          void shared.read(id, resource, selected?.addresses, selected?.refresh).then(value => reply(response, 200, value),
            () => reply(response, 503, { error: "Shared cache is unavailable. Retry or explicitly choose standalone." }));
        } else reply(response, 404, { error: "Unknown shared reading." });
        return false;
      }
      if ((FLEET_RESOURCES as readonly string[]).includes(resource)) {
        const deadline = shared.standaloneWait(resource);
        if (deadline > (providers.now?.() ?? Date.now())) {
          response.setHeader("Retry-After", Math.max(1, Math.ceil((deadline - (providers.now?.() ?? Date.now())) / 1000)));
          reply(response, 429, { error: "A known provider wait remains in effect after switching data sources.", retryAtMs: deadline }); return false;
        }
      }
    }
    if (url === "/mobile/observations" && request.method === "POST" && native) {
      void readBody(request, 2 * 1024 * 1024).then(async (value) => {
        if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).sort().join(",") !== "fleetId,schema,updates"
          || !("schema" in value) || value.schema !== 1 || !("fleetId" in value) || typeof value.fleetId !== "string"
          || !("updates" in value) || !Array.isArray(value.updates)) throw new Error("Invalid PC observation batch.");
        const fleet = savedFleets.get(value.fleetId);
        if (!fleet) throw new Error("Choose a known saved fleet.");
        await observations.update(fleet.id, fleet.hosts.map(host => host.address), value.updates,
          fleet.hosts.flatMap(host => host.pcMetadata ? [host.pcMetadata.pcId] : []));
        reply(response, 200, { ok: true });
      }).catch(() => { reply(response, 400, { error: "PC observations could not be accepted. Cached values keep their original source times." }); request.resume(); });
      return false;
    }
    if ((url === "/mobile/observations" || url.startsWith("/mobile/observations?")) && request.method === "GET") {
      const query = new URL(url, origin).searchParams;
      const sharedId = query.get("fleet") ?? "";
      if (shared.has(sharedId)) {
        if ([...query.keys()].some(key => key !== "fleet" && key !== "address") || query.getAll("fleet").length !== 1 || query.getAll("address").length > 1) {
          reply(response, 400, { error: "Choose a shared fleet and host." }); return false;
        }
        void shared.observations(sharedId, query.get("address") ?? undefined).then(value => reply(response, 200, value),
          () => reply(response, 503, { error: "Shared observations are unavailable." }));
        return false;
      }
      const fleet = savedFleets.get(query.get("fleet") ?? "");
      const address = query.get("address");
      if ([...query.keys()].some(key => key !== "fleet" && key !== "address") || query.getAll("fleet").length !== 1 || query.getAll("address").length > 1
        || !fleet || address !== null && !fleet.hosts.some(host => host.address === address)) reply(response, 404, { error: "Choose a known saved fleet and host." });
      else reply(response, 200, { schema: 1, fleetId: fleet.id, error: observations.error,
        pcNetworks: observations.listNetworks(fleet.id, fleet.hosts.filter(host => address === null || host.address === address)
          .flatMap(host => host.pcMetadata ? [host.pcMetadata.pcId] : [])),
        ...(address !== null ? { host: observations.get(fleet.id, address) ?? null } : { hosts: observations.list(fleet.id, fleet.hosts.map(host => host.address)) }) });
      return false;
    }
    if (url === "/mobile/fleets" && request.method === "GET") {
      reply(response, 200, listing());
      return false;
    }
    if (url === "/mobile/fleets" && request.method === "POST" && native) {
      void readBody(request).then(async body => {
        if (body && typeof body === "object" && "op" in body && body.op === "select" && "scenario" in body && typeof body.scenario === "string") {
          const id = body.scenario.startsWith("fleet:") ? body.scenario.slice(6) : "";
          if (shared.has(id)) { await shared.select(id); return { listing: listing(), changedId: undefined, fleetId: undefined }; }
          const result = await savedFleets.mutate(body); await shared.select(null); return result;
        }
        return savedFleets.mutate(body);
      }).then(result => {
        if (result.changedId) {
          for (const [pending, selected] of active) if (selected === `fleet:${result.changedId}`) pending.destroy();
          updateSavedPins?.();
          const saved = savedFleets.get(result.changedId);
          void observations.forget(result.changedId, saved?.hosts.map(host => host.address) ?? [],
            saved?.hosts.flatMap(host => host.pcMetadata ? [host.pcMetadata.pcId] : []) ?? []).catch(() => {});
        }
        reply(response, 200, { ok: true, ...result.listing, ...(result.fleetId ? { fleetId: result.fleetId } : {}) });
      }, error => {
        reply(response, 400, { error: error instanceof Error ? error.message : "The saved fleet request is invalid." });
        request.resume();
      });
      return false;
    }
    if (url.startsWith("/mobile/")) {
      const match = /^\/mobile\/rosters\/(supplied|real200)$/.exec(url);
      if (!match || request.method !== "POST" || !native) {
        reply(response, 403, { error: "Use the app's Import hosts action." });
        return false;
      }
      const scenario = match[1] as Scenario;
      void readBody(request).then(parseRoster).then(addresses => {
        // Serialize imports; a second request cannot overwrite or race the first.
        importChain = importChain.then(async () => {
          const union = new Set([...importedThisProcess, ...addresses]);
          if (union.size > 20_000) {
            reply(response, 409, { error: "The app has reached its in-memory roster import capacity for this session." });
            return;
          }
          if (!replaceRoster) throw new Error("The local Fleet runtime is not ready.");
          await atomicPrivateJson(rosterFile(scenario), addresses);
          // Cancel the old selection before updating admission; provider instances,
          // actual observation times and their request allowances remain unchanged.
          for (const [pending, selected] of active) if (selected === scenario) pending.destroy();
          replaceRoster(scenario, addresses);
          for (const address of addresses) importedThisProcess.add(address);
          reply(response, 200, { ok: true, count: addresses.length, scenario });
        }).catch(() => { reply(response, 500, { error: "The host list could not be saved. The previous selection remains available." }); });
        return importChain;
      }, error => {
        reply(response, 400, { error: error instanceof Error ? error.message : "The host list is invalid." });
        request.resume();
      });
      return false;
    }
    if (url.startsWith("/preview/")) {
      const scenario = new URL(url, origin).searchParams.get("scenario");
      if (scenario !== "online-gpu-hosts") {
        active.set(response, scenario?.startsWith("fleet:") ? scenario : scenario === "real200" ? "real200" : "supplied");
        response.once("close", () => active.delete(response));
      }
    }
    return true;
  };
  const options: PreviewOptions = {
    ...providers,
    directory, port: preferredPort, hostsFile: rosterFile("supplied"), real200HostsFile: rosterFile("real200"),
    mobile: {
      authorize, registerRosterUpdater: update => { replaceRoster = update; },
      savedFleet: id => {
        const fleet = savedFleets.get(id);
        return fleet ? { addresses: fleet.hosts.map(host => host.address), revision: JSON.stringify(fleet.hosts.map(host => host.address)) } : undefined;
      },
      savedAddresses: () => savedFleets.pinned(),
      registerSavedUpdater: update => { updateSavedPins = update; },
    },
  };
  let server: Server;
  try { server = await startPreviewServer(options); }
  catch (error) {
    if (preferredPort !== 0 && error instanceof Error && "code" in error && error.code === "EADDRINUSE") {
      server = await startPreviewServer({ ...options, port: 0 });
    } else throw error;
  }
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("The private Fleet listener did not start.");
  const port = address.port;
  server.once("close", () => shared.close());
  try { await atomicPrivateJson(join(privateDirectory, "runtime.json"), { port, token }); }
  catch (error) { server.closeAllConnections(); server.close(); throw error; }
  return { server, port, token };
}
