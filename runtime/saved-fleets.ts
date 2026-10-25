import { parsePcMetadata, parsePcMetadataUpdates } from "./pc-metadata.ts";
import type { PcMetadata } from "./pc-metadata.ts";
import { randomUUID, randomBytes } from "node:crypto";
import { open, rename, unlink } from "node:fs/promises";
import { dirname, join } from "node:path";
import { isMetadataAddress } from "../vendor/gridlens/src/fleet/host-metadata.ts";

export const MAX_SAVED_FLEETS = 100;
export const MAX_SAVED_HOSTS = 20_000;
export const FLEET_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export interface DiscoveredHost { address: string; firstDiscoveredAtMs: number; lastDiscoveredAtMs: number; pcMetadata?: PcMetadata }
export interface SavedFleet { id: string; name: string; createdAtMs: number; updatedAtMs: number; hosts: DiscoveredHost[] }
export interface FleetState { schema: 1; selectedScenario: string; fleets: SavedFleet[] }
export interface FleetListing extends FleetState { error: string | null }
const MAX_STATE_BYTES = 4 * 1024 * 1024;
const empty = (): FleetState => ({ schema: 1, selectedScenario: "network-discovery", fleets: [] });
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === "object" && !Array.isArray(value);
const exact = (value: Record<string, unknown>, keys: string[]): void => {
  if (Object.keys(value).some(key => !keys.includes(key)) || keys.some(key => !Object.hasOwn(value, key))) throw new Error("Invalid saved fleet fields.");
};
const time = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value > 0 && value <= 8_640_000_000_000_000;
function name(value: unknown): string {
  if (typeof value !== "string" || !value.trim() || value.length > 80 || /[\u0000-\u001f\u007f]/.test(value)) {
    throw new Error("Choose a fleet name between 1 and 80 characters.");
  }
  return value.trim();
}
function addresses(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > 10_000 || value.some(address => !isMetadataAddress(address)) || new Set(value).size !== value.length) {
    throw new Error("Choose at most 10,000 distinct public host addresses.");
  }
  return value as string[];
}
function scenario(value: unknown, state: FleetState): string {
  if (typeof value === "string" && (["network-discovery", "supplied", "real200", "online-gpu-hosts"].includes(value) ||
      value.startsWith("fleet:") && state.fleets.some(fleet => `fleet:${fleet.id}` === value))) return value;
  throw new Error("Choose a known saved fleet or scenario.");
}
function validate(value: unknown): FleetState {
  if (!record(value)) throw new Error("Invalid saved fleets.");
  exact(value, ["schema", "selectedScenario", "fleets"]);
  if (value.schema !== 1 || !Array.isArray(value.fleets) || value.fleets.length > MAX_SAVED_FLEETS) throw new Error("Invalid saved fleets.");
  let count = 0;
  const ids = new Set<string>();
  const fleets = value.fleets.map((fleet: unknown): SavedFleet => {
    if (!record(fleet)) throw new Error("Invalid saved fleet.");
    exact(fleet, ["id", "name", "createdAtMs", "updatedAtMs", "hosts"]);
    if (typeof fleet.id !== "string" || !FLEET_ID.test(fleet.id) || ids.has(fleet.id) || !time(fleet.createdAtMs) ||
        !time(fleet.updatedAtMs) || fleet.updatedAtMs < fleet.createdAtMs || !Array.isArray(fleet.hosts) || fleet.hosts.length > 10_000) throw new Error("Invalid saved fleet.");
    ids.add(fleet.id);
    count += fleet.hosts.length;
    const createdAtMs = fleet.createdAtMs;
    const updatedAtMs = fleet.updatedAtMs;
    const hosts = fleet.hosts.map((host: unknown): DiscoveredHost => {
      if (!record(host)) throw new Error("Invalid discovered host.");
      exact(host, ["address", "firstDiscoveredAtMs", "lastDiscoveredAtMs", ...(Object.hasOwn(host, "pcMetadata") ? ["pcMetadata"] : [])]);
      if (!isMetadataAddress(host.address) || !time(host.firstDiscoveredAtMs) || !time(host.lastDiscoveredAtMs) ||
          host.firstDiscoveredAtMs > host.lastDiscoveredAtMs || host.firstDiscoveredAtMs < createdAtMs || host.lastDiscoveredAtMs > updatedAtMs) throw new Error("Invalid discovered host.");
      return { address: host.address, firstDiscoveredAtMs: host.firstDiscoveredAtMs, lastDiscoveredAtMs: host.lastDiscoveredAtMs,
        ...(Object.hasOwn(host, "pcMetadata") ? { pcMetadata: parsePcMetadata(host.pcMetadata, host.lastDiscoveredAtMs) } : {}) };
    });
    if (new Set(hosts.map(host => host.address)).size !== hosts.length) throw new Error("Duplicate discovered host.");
    return { id: fleet.id, name: name(fleet.name), createdAtMs: fleet.createdAtMs, updatedAtMs: fleet.updatedAtMs, hosts };
  });
  if (count > MAX_SAVED_HOSTS) throw new Error("Saved fleets exceed the host capacity.");
  const state: FleetState = { schema: 1, selectedScenario: "network-discovery", fleets };
  state.selectedScenario = scenario(value.selectedScenario, state);
  return state;
}

/** JSON.parse accepts duplicate keys; native/public records deliberately do not. */
export function parseUniqueJson(text: string): unknown {
  const parsed: unknown = JSON.parse(text);
  const frames: { object: boolean; key: boolean; seen: Set<string> }[] = [];
  const tokens = /"(?:[^"\\]|\\.)*"|[{}[\]:,]|[^\s{}[\]:,"]+/g;
  for (const match of text.matchAll(tokens)) {
    const token = match[0];
    if (token === "{" || token === "[") frames.push({ object: token === "{", key: token === "{", seen: new Set() });
    else if (token === "}" || token === "]") frames.pop();
    else {
      const frame = frames.at(-1);
      if (frame?.object && token === ",") frame.key = true;
      else if (frame?.object && frame.key && token.startsWith('"')) {
        const key = JSON.parse(token) as string;
        if (frame.seen.has(key)) throw new Error("Duplicate JSON fields are not accepted.");
        frame.seen.add(key); frame.key = false;
      }
    }
  }
  return parsed;
}

/** Flush the new file before rename, then its directory. No caller-controlled path. */
export async function atomicPrivateJson(filename: string, value: object): Promise<void> {
  const temporary = `${filename}.${randomBytes(8).toString("hex")}.tmp`;
  try {
    const file = await open(temporary, "wx", 0o600);
    try { await file.writeFile(JSON.stringify(value) + "\n"); await file.sync(); } finally { await file.close(); }
    await rename(temporary, filename);
    const directory = await open(dirname(filename), "r");
    try { await directory.sync(); } finally { await directory.close(); }
  } finally { await unlink(temporary).catch(() => {}); }
}

export class SavedFleets {
  private state: FleetState = empty();
  private warning: string | null = null;
  private writable = true;
  private chain: Promise<void> = Promise.resolve();
  private filename: string;
  private now: () => number;
  private constructor(directory: string, now: () => number) { this.filename = join(directory, "discovered-fleets.json"); this.now = now; }

  static async load(directory: string, now = Date.now): Promise<SavedFleets> {
    const store = new SavedFleets(directory, now);
    const load = async (filename: string): Promise<FleetState> => {
      const file = await open(filename, "r");
      try {
        if ((await file.stat()).size > MAX_STATE_BYTES) throw new Error("Saved fleet data is too large.");
        return validate(parseUniqueJson(await file.readFile("utf8")));
      } finally { await file.close(); }
    };
    try { store.state = await load(store.filename); }
    catch (error) {
      if (error instanceof Error && "code" in error && error.code === "ENOENT") {
        // A missing primary with an existing recovery file can follow interrupted
        // manual recovery. Never replace that existing fleet with an empty state.
        try { store.state = await load(store.filename + ".bak"); store.warning = "Saved fleets were recovered from the previous saved revision."; }
        catch (backupError) {
          if (!(backupError instanceof Error && "code" in backupError && backupError.code === "ENOENT")) {
            store.writable = false; store.warning = "Saved fleet data could not be read. Existing files were preserved.";
          }
        }
      } else {
        try { store.state = await load(store.filename + ".bak"); store.warning = "Saved fleets were recovered from the previous saved revision."; }
        catch { store.writable = false; store.warning = "Saved fleet data could not be read. Existing files were preserved."; }
      }
    }
    return store;
  }
  list(): FleetListing { return { ...structuredClone(this.state), error: this.warning }; }
  get(id: string): SavedFleet | undefined { return FLEET_ID.test(id) ? structuredClone(this.state.fleets.find(fleet => fleet.id === id)) : undefined; }
  pinned(): string[] { return [...new Set(this.state.fleets.flatMap(fleet => fleet.hosts.map(host => host.address)))]; }

  /** Only native-authenticated handlers call this. Never accepts credentials. */
  async mutate(value: unknown): Promise<{ listing: FleetListing; fleetId?: string; changedId?: string }> {
    let result: { listing: FleetListing; fleetId?: string; changedId?: string } | undefined;
    const operation = this.chain.then(async () => {
      if (!this.writable) throw new Error("Saved fleets are unavailable. Existing files were preserved for recovery.");
      if (!record(value) || typeof value.op !== "string") throw new Error("Choose a supported fleet operation.");
      const next = structuredClone(this.state);
      const stamp = Math.max(Math.trunc(this.now()), ...next.fleets.map(fleet => fleet.updatedAtMs), 1);
      let changedId: string | undefined;
      let fleetId: string | undefined;
      if (value.op === "select") {
        exact(value, ["op", "scenario"]);
        next.selectedScenario = scenario(value.scenario, next);
      } else if (value.op === "create") {
        exact(value, ["op", "name", "addresses", ...(Object.hasOwn(value, "pcMetadata") ? ["pcMetadata"] : [])]);
        const roster = addresses(value.addresses);
        const metadata = parsePcMetadataUpdates(value.pcMetadata, roster, stamp);
        if (roster.length === 0) throw new Error("Discover at least one public host before saving a fleet.");
        const fleet: SavedFleet = { id: randomUUID(), name: name(value.name), createdAtMs: stamp, updatedAtMs: stamp,
          hosts: roster.map(address => ({ address, firstDiscoveredAtMs: stamp, lastDiscoveredAtMs: stamp,
            ...(metadata.get(address) ? { pcMetadata: metadata.get(address)! } : {}) })) };
        next.fleets.push(fleet); fleetId = fleet.id; changedId = fleet.id; next.selectedScenario = `fleet:${fleet.id}`;
      } else {
        const fields = value.op === "rename" ? ["op", "id", "name"] : value.op === "merge" || value.op === "remove-hosts" ? ["op", "id", "addresses"] : value.op === "delete" ? ["op", "id"] : null;
        if (!fields) throw new Error("Choose a supported fleet operation.");
        exact(value, [...fields, ...(value.op === "merge" && Object.hasOwn(value, "pcMetadata") ? ["pcMetadata"] : [])]);
        const fleet = next.fleets.find(item => item.id === value.id);
        if (!fleet) throw new Error("Choose a known saved fleet.");
        fleetId = fleet.id; changedId = fleet.id;
        if (value.op === "delete") {
          next.fleets = next.fleets.filter(item => item.id !== fleet.id);
          if (next.selectedScenario === `fleet:${fleet.id}`) next.selectedScenario = "network-discovery";
        } else if (value.op === "rename") { fleet.name = name(value.name); fleet.updatedAtMs = stamp; }
        else if (value.op === "remove-hosts") {
          const removed = new Set(addresses(value.addresses));
          if ([...removed].some(address => !fleet.hosts.some(host => host.address === address))) throw new Error("Choose hosts from this saved fleet.");
          fleet.hosts = fleet.hosts.filter(host => !removed.has(host.address)); fleet.updatedAtMs = stamp;
        } else {
          const roster = addresses(value.addresses);
          const metadata = parsePcMetadataUpdates(value.pcMetadata, roster, stamp);
          for (const address of roster) {
            const host = fleet.hosts.find(item => item.address === address);
            if (host) {
              host.lastDiscoveredAtMs = stamp;
              if (metadata.has(address)) {
                const update = metadata.get(address);
                if (update) host.pcMetadata = update; else delete host.pcMetadata;
              }
            } else fleet.hosts.push({ address, firstDiscoveredAtMs: stamp, lastDiscoveredAtMs: stamp,
              ...(metadata.get(address) ? { pcMetadata: metadata.get(address)! } : {}) });
          }
          fleet.updatedAtMs = stamp; next.selectedScenario = `fleet:${fleet.id}`;
        }
      }
      validate(next);
      if (Buffer.byteLength(JSON.stringify(next)) > MAX_STATE_BYTES - 16 * 1024) throw new Error("Saved fleets exceed the storage capacity.");
      try {
        // Backup only the validated in-memory state, never a corrupt primary.
        await atomicPrivateJson(this.filename + ".bak", this.state);
        await atomicPrivateJson(this.filename, next);
      } catch {
        // A failed directory flush can make the commit ambiguous. Fail closed
        // until reopening reconciles persisted state; do not perform more writes.
        this.writable = false;
        this.warning = "Saved fleet persistence failed. Reopen the app to recover the saved revision.";
        throw new Error(this.warning);
      }
      this.state = next; this.warning = null;
      result = { listing: this.list(), ...(fleetId ? { fleetId } : {}), ...(changedId ? { changedId } : {}) };
    });
    this.chain = operation.catch(() => {});
    await operation;
    return result!;
  }
}
