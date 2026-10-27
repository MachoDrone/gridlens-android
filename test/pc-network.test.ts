import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PcObservations } from "../runtime/pc-observations.ts";
import { networkView, parsePcNetwork, parsePcUpdate, parseSavedObservation } from "../vendor/gridlens/src/fleet/pc-observations.ts";
import type { NodeBinding, PcNetworkObservation, PcUpdate } from "../vendor/gridlens/src/fleet/pc-observations.ts";
import { formatMobileIPDisplay } from "../vendor/gridlens/src/preview/mobile-scenarios.ts";
import { formatIPDisplay, nextIPMode } from "../vendor/gridlens/src/fleet/ip-display.ts";
import type { IPDisplayMode } from "../vendor/gridlens/src/fleet/ip-display.ts";

const address = "1".repeat(32);
const fleet = "00000000-0000-4000-8000-000000000001";
const binding: NodeBinding = { pcId: "a".repeat(64), runtime: "docker", wrapperId: null, nodeContainerId: "b".repeat(64) };
const AT = Date.UTC(2026, 9, 26, 12);
const journal = (directory: string) => join(directory, "pc-observations", `${fleet}--${address}.jsonl`);

function network(publicIp: string | null = "8.8.8.8", privateIp: string | null = "192.168.5.42", observedAtMs = AT): PcNetworkObservation {
  return { schema: 1, source: "node-egress-ipify", status: publicIp === null ? "unavailable" : "ok", observedAtMs,
    publicIp, publicObservedAtMs: publicIp === null ? null : observedAtMs,
    privateIp, privateObservedAtMs: privateIp === null ? null : observedAtMs };
}
const update = (value: PcNetworkObservation): PcUpdate => ({ address, binding, network: value });

test("PC network accepts only correctly classified numeric observations and canonicalizes IPv6", () => {
  assert.deepEqual(parsePcNetwork(network()), network());
  const ipv6 = parsePcNetwork(network("2606:4700:4700:0000:0000:0000:0000:1111", "FD12:3456:0000:0000:0000:0000:0000:0001"));
  assert.equal(ipv6.publicIp, "2606:4700:4700::1111");
  assert.equal(ipv6.privateIp, "fd12:3456::1");
  for (const privateIp of ["10.2.3.4", "172.16.0.1", "172.31.255.254", "192.168.1.1", "100.64.0.1", "100.127.255.254", "fd00::1", "fc00::1"]) {
    assert.throws(() => parsePcNetwork(network(privateIp)), Error, `private ${privateIp} cannot be public egress`);
    assert.equal(parsePcNetwork(network(null, privateIp)).privateIp, privateIp);
  }
  for (const publicIp of ["8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111"])
    assert.throws(() => parsePcNetwork(network(null, publicIp)), Error, `public ${publicIp} cannot be a private interface`);
  for (const invalid of ["127.0.0.1", "0.0.0.0", "169.254.1.2", "224.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "ff02::1",
    "192.0.2.1", "198.51.100.1", "203.0.113.1", "198.18.0.1", "2001:db8::1", "::ffff:8.8.8.8",
    "example.com", "8.8.8.8:443", "https://8.8.8.8/", "8.8.8.08", " 8.8.8.8", "8.8.8.8\n", "256.1.1.1", "$(id)"])
    assert.throws(() => parsePcNetwork(network(invalid)), Error, `invalid public literal ${JSON.stringify(invalid)}`);
});

test("PC network rejects injected fields, conflicting status and malformed source timestamps", () => {
  for (const field of ["authorization", "password", "privateKey", "sshEndpoint", "url"])
    assert.throws(() => parsePcNetwork({ ...network(), [field]: "fixture-must-not-be-carried" }));
  for (const changed of [
    { schema: 2 }, { source: "viewing-phone-ip" }, { source: "ssh-endpoint" }, { status: "healthy" },
    { status: "unavailable" }, { publicIp: null }, { publicObservedAtMs: null }, { privateIp: null }, { privateObservedAtMs: null },
    { observedAtMs: 0 }, { observedAtMs: 1.5 }, { observedAtMs: -1 }, { observedAtMs: Infinity },
    { observedAtMs: 8_640_000_000_000_001 }, { observedAtMs: String(AT) },
    { publicObservedAtMs: 0 }, { publicObservedAtMs: AT + 1 }, { privateObservedAtMs: AT + 1 },
  ]) assert.throws(() => parsePcNetwork({ ...network(), ...changed }));
  assert.throws(() => parsePcNetwork({ ...network(null, null), status: "ok" }));
  const validPartial = network(null, "10.0.0.42");
  assert.equal(parsePcNetwork(validPartial).status, "unavailable");
  assert.deepEqual(parsePcUpdate(update(validPartial)).network, validPartial);
  assert.throws(() => parsePcUpdate({ ...update(validPartial), authorization: "fixture" }));
});

test("network cache retains public and private values independently without advancing their source times", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-network-cache-"));
  let now = AT;
  try {
    const store = await PcObservations.load(directory, () => now);
    await store.update(fleet, [address], [update(network())]);
    now += 10_000;
    await store.update(fleet, [address], [update(network(null, "192.168.5.43", now))]);
    let view = networkView(store.get(fleet, address), now);
    assert.equal(view.publicIp, "8.8.8.8"); assert.equal(view.publicObservedAtMs, AT); assert.equal(view.publicRetained, true);
    assert.equal(view.privateIp, "192.168.5.43"); assert.equal(view.privateObservedAtMs, AT + 10_000); assert.equal(view.privateRetained, false);
    now += 10_000;
    await store.update(fleet, [address], [update(network("1.1.1.1", null, now))]);
    view = networkView(store.get(fleet, address), now);
    assert.equal(view.publicIp, "1.1.1.1"); assert.equal(view.publicObservedAtMs, AT + 20_000); assert.equal(view.publicRetained, false);
    assert.equal(view.privateIp, "192.168.5.43"); assert.equal(view.privateObservedAtMs, AT + 10_000); assert.equal(view.privateRetained, true);
    now += 10_000;
    await store.update(fleet, [address], [update(network(null, null, now))]);
    const saved = store.get(fleet, address)!;
    assert.equal(saved.lastGoodNetwork!.observedAtMs, AT + 20_000, "receipt/failure time cannot renew retained successful sources");
    assert.equal(saved.lastGoodNetwork!.publicObservedAtMs, AT + 20_000);
    assert.equal(saved.lastGoodNetwork!.privateObservedAtMs, AT + 10_000);
    const bytes = await readFile(journal(directory), "utf8");
    const exposed = store.list(fleet, [address]);
    exposed[0]!.lastGoodNetwork!.publicIp = "9.9.9.9";
    assert.equal(store.get(fleet, address)!.lastGoodNetwork!.publicIp, "1.1.1.1", "cache reads cannot mutate stored evidence");
    now += 10 * 60 * 60_000;
    const reopened = await PcObservations.load(directory, () => now);
    assert.deepEqual(reopened.get(fleet, address), saved);
    view = networkView(reopened.get(fleet, address), now);
    assert.equal(view.publicRetained, true); assert.equal(view.privateRetained, true);
    assert.equal(view.status, "unavailable");
    assert.equal(await readFile(journal(directory), "utf8"), bytes, "view/list/reopen do not rewrite or refresh observations");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("network observations reject source rollback independently and cannot cross a replaced node binding", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-network-order-"));
  let now = AT + 20_000;
  try {
    const store = await PcObservations.load(directory, () => now);
    await store.update(fleet, [address], [update(network("1.1.1.1", "192.168.5.42", now))]);
    now += 10_000;
    await store.update(fleet, [address], [update(network("8.8.8.8", "192.168.5.41", AT))]);
    assert.equal(store.get(fleet, address)!.network!.publicIp, "1.1.1.1", "an older whole response cannot replace the newer one");
    const mixed = network("8.8.8.8", "192.168.5.43", now); mixed.publicObservedAtMs = AT + 10_000;
    await store.update(fleet, [address], [update(mixed)]);
    const view = networkView(store.get(fleet, address), now);
    assert.equal(view.publicIp, "1.1.1.1", "a newer envelope cannot overwrite a newer individual public measurement");
    assert.equal(view.publicObservedAtMs, AT + 20_000); assert.equal(view.publicRetained, true);
    assert.equal(view.privateIp, "192.168.5.43"); assert.equal(view.privateObservedAtMs, now); assert.equal(view.privateRetained, false);
    now += 10_000;
    const privateRollback = network("9.9.9.9", "192.168.5.42", now); privateRollback.privateObservedAtMs = AT + 25_000;
    await store.update(fleet, [address], [update(privateRollback)]);
    const independent = networkView(store.get(fleet, address), now);
    assert.equal(independent.publicIp, "9.9.9.9"); assert.equal(independent.publicRetained, false);
    assert.equal(independent.privateIp, "192.168.5.43"); assert.equal(independent.privateObservedAtMs, AT + 30_000);
    assert.equal(independent.privateRetained, true, "an older private sample cannot inherit a newer public sample's time");
    await store.update(fleet, [address], [{ address, binding: { ...binding, nodeContainerId: "c".repeat(64) }, network: network(null, null, now) }]);
    const replaced = networkView(store.get(fleet, address), now);
    assert.equal(replaced.publicIp, null); assert.equal(replaced.privateIp, null);
    assert.equal(store.get(fleet, address)!.lastGoodNetwork, undefined, "the previous container's addresses cannot leak into a new binding");
    assert.equal(store.get("00000000-0000-4000-8000-000000000002", address), undefined);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("network freshness expires each original timestamp and future PC times cannot establish fresh data", async () => {
  const parsed = parsePcNetwork({ ...network(), privateObservedAtMs: AT - 60_000 });
  assert.equal(networkView({ network: parsed }, AT + 35 * 60_000 - 1).publicRetained, false);
  assert.equal(networkView({ network: parsed }, AT + 35 * 60_000).publicRetained, true);
  assert.equal(networkView({ network: parsed }, AT + 34 * 60_000).privateRetained, true);
  assert.equal(networkView({ network: parsed }, AT - 1).publicRetained, true, "future timestamps are not current evidence");
  const directory = await mkdtemp(join(tmpdir(), "gridlens-network-clock-"));
  try {
    const store = await PcObservations.load(directory, () => AT);
    await store.update(fleet, [address], [update(network())]);
    await store.update(fleet, [address], [update(network("1.1.1.1", "10.0.0.42", AT + 5001))]);
    const record = store.get(fleet, address)!;
    assert.equal(record.network!.status, "unavailable"); assert.equal(record.network!.publicIp, null);
    const view = networkView(record, AT);
    assert.equal(view.publicIp, "8.8.8.8"); assert.equal(view.publicObservedAtMs, AT); assert.equal(view.publicRetained, true);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("older observation journals without network fields reopen unchanged and stay unknown", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-network-compat-"));
  try {
    const old = { address, binding, receivedAtMs: AT, rewards: { schema: 1, address, observedAtMs: AT, status: "ok", amount: "0.000000" } };
    assert.equal(parseSavedObservation(old).network, undefined);
    await mkdir(join(directory, "pc-observations"));
    const bytes = JSON.stringify({ snapshot: old }) + "\n";
    await writeFile(journal(directory), bytes);
    const store = await PcObservations.load(directory, () => AT + 86_400_000);
    assert.equal(store.error, null); assert.deepEqual(store.get(fleet, address), old);
    assert.deepEqual(networkView(store.get(fleet, address), AT), { publicIp: null, privateIp: null,
      publicObservedAtMs: null, privateObservedAtMs: null, publicRetained: false, privateRetained: false, status: "unavailable" });
    assert.equal(await readFile(journal(directory), "utf8"), bytes);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("Android IP mode cycle matches public/private reference while preserving full IPv6 and unknown slots", () => {
  const addresses = { global: "8.8.8.8", internal: "192.168.5.42" };
  let mode: IPDisplayMode = "dot";
  for (const expected of ["•", "8.8.8.8 / 192.168.5.42", "8", "42", "•"]) {
    assert.equal(formatMobileIPDisplay(addresses, mode).text, expected); mode = nextIPMode(mode);
  }
  assert.equal(formatIPDisplay(addresses, "ext").text, ".8", "the inherited non-mobile formatter retains its original suffix convention");
  assert.equal(formatMobileIPDisplay({ global: null, internal: null }, "full").text, "− / −");
  assert.equal(formatMobileIPDisplay({ global: null, internal: null }, "dot").text, "•");
  assert.equal(formatMobileIPDisplay({ global: null, internal: "10.0.0.102" }, "full").text, "− / 10.0.0.102");
  assert.equal(formatMobileIPDisplay({ global: null, internal: "10.0.0.102" }, "ext").text, "−");
  assert.equal(formatMobileIPDisplay({ global: "8.8.8.8", internal: null }, "int").text, "−");
  assert.equal(formatMobileIPDisplay({ global: "8.8.8.8", internal: null }, "full").text, "8.8.8.8 / −");
  const ipv6 = { global: "2606:4700:4700::1111", internal: "fd12:3456::1" };
  assert.equal(formatMobileIPDisplay(ipv6, "ext").text, ipv6.global);
  assert.equal(formatMobileIPDisplay(ipv6, "int").text, ipv6.internal);
  assert.match(formatMobileIPDisplay(addresses, "ext").description, /8\.8\.8\.8/);
  assert.match(formatMobileIPDisplay(addresses, "ext").description, /192\.168\.5\.42/);
});
