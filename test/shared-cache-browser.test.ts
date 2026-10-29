import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import playwright from "../vendor/gridlens/node_modules/playwright/index.js";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";
import { emptyFleetSnapshots } from "../vendor/gridlens/src/server/snapshot-store.ts";
import type { FleetResource } from "../vendor/gridlens/src/contracts/fleet.ts";
import { SharedCacheError } from "../runtime/shared-cache.ts";

test("bundled phone UI shows shared mode separately from freshness and retains explicit standalone controls", { timeout: 120_000 }, async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-shared-browser-"));
  await writeFile(join(dataDirectory, "hosts.json"), "[]"); await writeFile(join(dataDirectory, "real200.json"), "[]");
  const address = "1".repeat(32), now = Date.now(), context = { scopeId: "generated-browser-fleet", rosterRevision: "v1", addresses: [address] };
  let localReads = 0, offline = false;
  const snapshots = emptyFleetSnapshots(context.addresses);
  snapshots["node-info"].hosts[0] = { address, values: null, fetchedAtMs: now, validUntilMs: now + 30_000, retained: false, error: null, apiCheck: { status: "online", checkedAtMs: now } };
  snapshots.metadata.hosts[0] = { address, fetchedAtMs: now, retained: false, error: null, marketError: null,
    values: { status: "PREMIUM", marketAddress: address, marketLabel: "Generated GPU", ram: null, disk: null, cpu: null, cuda: null, nvidia: null, currentNode: null, system: null } };
  const evidence = { provider: "generated", endpoint: "generated", contextSlot: 1, blockTimeMs: now, checkedAtMs: now, validUntilMs: now + 35 * 60_000 };
  Object.assign(snapshots.staking.hosts[0]!, { marketAddress: address, marketFetchedAtMs: now });
  Object.assign(snapshots.staking.hosts[0]!.stake, evidence, { stakeAccount: address, vaultAccount: address, amount: "2000000", recordedAmount: "2000000", xnos: "2000000", durationSeconds: "100", unstakeAtSeconds: "0", accountState: "initialized" });
  Object.assign(snapshots.staking.hosts[0]!.requirement, evidence, { marketAddress: address, targetAmount: "1000000", xnosMinimum: "1000000" });
  const runtime = await startAndroidRuntime({ dataDirectory, directory: resolve("dist/runtime/web"), preferredPort: 0,
    providers: { metadataProvider: async addresses => { localReads++; return emptyFleetSnapshots(addresses).metadata; } },
    sharedTransport: async input => {
      if (offline) throw new SharedCacheError("unavailable");
      if (input.path === "/api/shared/pair") return { status: 200, value: { version: 1, serverId: "generated-server", deviceId: "generated-phone", token: "generated-token", role: "viewer", context, capabilities: [] } };
      if (input.path === "/api/shared/status") return { status: 200, value: { version: 1, serverId: "generated-server", serverTimeMs: now, context, capabilities: [], providerWaits: [] } };
      if (input.path === "/api/shared/observations") return { status: 200, value: { version: 1, serverId: "generated-server", scopeId: context.scopeId, rosterRevision: context.rosterRevision, associations: [], observations: [], networks: [] } };
      const resource = input.path.split("/").at(-1)!.split(".")[0] as FleetResource;
      return { status: 200, value: { ...snapshots[resource], serverTimeMs: now } };
    } });
  const origin = `http://127.0.0.1:${runtime.port}`;
  const browser = await playwright.chromium.launch({ headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) });
  try {
    const pair = await fetch(origin + "/mobile/shared", { method: "POST", headers: { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" },
      body: JSON.stringify({ op: "pair", configuration: { origin: "https://generated.test", serverId: "generated-server", fingerprint: "a".repeat(64), name: "Test backend", deviceName: "Fixture", code: "generated" } }) });
    assert.equal(pair.status, 200); const result = await pair.json();
    const session = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    const external: string[] = [];
    await session.route("**/*", route => { if (new URL(route.request().url()).origin !== origin) { external.push(route.request().url()); return route.abort(); } return route.continue(); });
    await session.addCookies([{ name: SESSION_COOKIE, value: runtime.token, url: origin, httpOnly: true, sameSite: "Strict" }]);
    const page = await session.newPage(), errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
    await page.goto(`${origin}/?scenario=${result.selectedScenario}`);
    await page.locator('#fleet-rows > tr[data-host]').first().waitFor({ state: "attached" });
    assert.match(await page.locator("#mobile-fleet-status").innerText(), /Shared cache/);
    assert.match(await page.locator("#mobile-fleet-status").innerText(), /freshness/);
    assert.equal(await page.locator("#mobile-network-search").isVisible(), false);
    assert.equal(await page.locator("#mobile-enable-monitoring").isVisible(), false);
    assert.equal(await page.locator("#mobile-shared-cache").innerText(), "Retry / Use standalone");
    assert.equal(localReads, 0);
    await page.waitForFunction(() => document.querySelector('[data-col="api"][data-api-current="true"]'));
    await page.waitForFunction(() => document.querySelector('[data-col="stakedNos"] .staking-actual:not(.staking-retained):not(.staking-unknown)'));
    offline = true;
    await page.evaluate(async (scenario: string) => {
      window.dispatchEvent(new PageTransitionEvent("pagehide"));
      await fetch(`/preview/metadata.json?scenario=${scenario}`);
      window.dispatchEvent(new PageTransitionEvent("pageshow"));
    }, result.selectedScenario);
    await page.waitForFunction(() => document.getElementById("mobile-fleet-status")?.textContent?.includes("VPN or backend unavailable"));
    await page.reload();
    await page.waitForFunction(() => document.querySelector('[data-col="api"][data-api-status="Online"][data-api-current="false"]'));
    assert.equal(await page.locator('[data-col="api"][data-api-status="Online"]').getAttribute("data-api-checked-at"), String(now), "retained styling never renews source time");
    await page.waitForFunction(() => document.querySelector('[data-col="stakedNos"] .staking-actual.staking-retained'));
    assert(Date.now() - now < 30_000, "retained API display was checked before the original Online proof expired");
    await page.locator('#fleet-rows > tr[data-host]').first().waitFor({ state: "attached" });
    offline = false;
    await page.reload();
    await page.waitForFunction(() => document.querySelector('[data-col="api"][data-api-status="Online"][data-api-current="true"]'));
    await page.waitForFunction(() => document.querySelector('[data-col="stakedNos"] .staking-actual:not(.staking-retained):not(.staking-unknown)'));
    assert.equal(await page.locator('[data-col="api"][data-api-status="Online"]').getAttribute("data-api-checked-at"), String(now));
    assert.equal(localReads, 0); assert.deepEqual(external, []); assert.deepEqual(errors, []);
    await session.close();
  } finally { await browser.close(); runtime.server.closeAllConnections(); await new Promise<void>(accept => runtime.server.close(() => accept())); await rm(dataDirectory, { recursive: true, force: true }); }
});
