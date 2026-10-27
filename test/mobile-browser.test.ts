import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import playwright from "../vendor/gridlens/node_modules/playwright/index.js";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";

test("the guarded Android runtime preserves Fleet actions and saved preferences at phone size", { timeout: 120_000 }, async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-phone-browser-"));
  await writeFile(join(dataDirectory, "hosts.json"), "[]");
  await writeFile(join(dataDirectory, "real200.json"), "[]");
  const runtime = await startAndroidRuntime({ dataDirectory, directory: resolve("dist/runtime/web"), preferredPort: 0 });
  const origin = `http://127.0.0.1:${runtime.port}`;
  const browser = await playwright.chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}),
  });
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    const unexpected: string[] = [];
    await context.route("**/*", route => {
      if (new URL(route.request().url()).origin !== origin) { unexpected.push(route.request().url()); return route.abort(); }
      return route.continue();
    });
    // The native shell creates this HttpOnly session cookie before loading UI.
    await context.addCookies([{ name: SESSION_COOKIE, value: runtime.token, url: origin, httpOnly: true, sameSite: "Strict" }]);
    const page = await context.newPage();
    const errors: string[] = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
    await page.goto(origin + "/?scenario=scale");
    await page.locator('#fleet-rows > tr[data-host]').nth(199).waitFor({ state: "attached" });
    assert.equal(await page.locator('#fleet-rows > tr[data-host]').count(), 200);
    assert.equal(await page.locator('#column-headings th[data-col]').count(), 28);
    assert.match(await page.locator('#column-headings').innerText(), /Country/);
    assert.match(await page.locator('#column-headings').innerText(), /Jobs/);
    assert.equal(await page.evaluate(() => document.cookie), "", "web content cannot read the native session token");
    await page.locator('#country-display-toggle').click();
    assert.equal(await page.locator('#country-display-toggle').getAttribute('data-mode'), 'full');
    await page.reload();
    await page.locator('#fleet-rows > tr[data-host]').nth(199).waitFor({ state: "attached" });
    assert.equal(await page.locator('#country-display-toggle').getAttribute('data-mode'), 'full');
    await page.locator('#sidebar-toggle').click();
    assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'), 'true');
    await page.locator('#sidebar-toggle').click();
    assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'), 'false');
    assert.deepEqual(unexpected, [], "synthetic acceptance must never query providers");
    assert.deepEqual(errors, [], "CSP and runtime must preserve the unchanged browser app");
    await context.close();
  } finally {
    await browser.close();
    runtime.server.closeAllConnections();
    await new Promise<void>(accept => runtime.server.close(() => accept()));
    await rm(dataDirectory, { recursive: true, force: true });
  }
});

test("Android defaults to idle discovery and reopens saved fleets without discovery or native mutations", { timeout: 120_000 }, async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-saved-phone-"));
  const reads: string[][] = [];
  const empty = async (addresses: readonly string[]) => { reads.push([...addresses]); return { hosts: [], nextRefreshAtMs: Date.now() + 60000 }; };
  const runtime = await startAndroidRuntime({ dataDirectory, directory: resolve("dist/runtime/web"), preferredPort: 0,
    providers: { metadataProvider: empty, nodeInfoProvider: empty, jobProvider: empty, queueProvider: empty,
      solProvider: empty, nosProvider: empty, stakingProvider: empty } });
  const origin = `http://127.0.0.1:${runtime.port}`;
  const browser = await playwright.chromium.launch({ headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) });
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    const forbidden: string[] = [];
    await context.route("**/*", route => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.origin !== origin || request.method() !== "GET" || /\/mobile\/(discovery|select)/.test(url.pathname)) {
        forbidden.push(request.url()); return route.abort();
      }
      return route.continue();
    });
    await context.addCookies([{ name: SESSION_COOKIE, value: runtime.token, url: origin, httpOnly: true, sameSite: "Strict" }]);
    const page = await context.newPage();
    const errors: string[] = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.goto(origin);
    await page.locator("#mobile-network-search").waitFor();
    assert.equal(await page.locator("#scenario").inputValue(), "network-discovery");
    assert.equal(await page.locator("#mobile-network-search").innerText(), "Search network");
    await page.locator(".empty-content").filter({ hasText: "Search network to discover" }).waitFor();
    assert.equal(reads.length, 0, "launch does not contact any provider for empty discovery");
    assert.equal(await page.locator("#scenario option[value=supplied]").count(), 1);
    const address = "1".repeat(32);
    const gpuAt = Date.now() - 60000;
    const reasonNames = ["swThermalSlowdown", "hwThermalSlowdown", "swPowerCap", "hwPowerBrake"];
    const telemetry = { schema: 1, collector: { status: "ok" }, window: { startAtMs: gpuAt, endAtMs: gpuAt },
      gpus: [{ uuid: "GPU-11111111-2222-3333-4444-555555555555", index: null, deviceMinor: 0, observedAtMs: gpuAt,
        reasons: Object.fromEntries(reasonNames.map(key => [key, key === "swPowerCap" ? true : null])),
        durationUs: Object.fromEntries(reasonNames.map(key => [key, null])),
        history: { firstObservedAtMs: gpuAt, lastObservedAtMs: gpuAt, sampleCount: 1, maxGapMs: 0,
          reasons: Object.fromEntries(reasonNames.map(key => [key, { supportedSamples: key === "swPowerCap" ? 1 : 0,
            activeSamples: key === "swPowerCap" ? 1 : 0, counterObservedUs: null, counterIntervals: 0, counterResets: 0 }])) } }] };

    const created = await fetch(origin + "/mobile/fleets", { method: "POST", headers: {
      "X-GridLens-Token": runtime.token, "Content-Type": "application/json" },
      body: JSON.stringify({ op: "create", name: "Customer <North>", addresses: [address], pcMetadata: [{ address, pcId: "a".repeat(64), pcName: "fixture-pc", ipAddresses: ["192.168.9.42", "203.0.113.42"], observedAtMs: Date.now() - 5000, gpuTelemetry: telemetry }] }) });
    assert.equal(created.status, 200);
    const { fleetId } = await created.json() as { fleetId: string };
    await page.reload();
    await page.locator('#fleet-rows > tr[data-host]').waitFor();
    assert.equal(await page.locator("#scenario").inputValue(), `fleet:${fleetId}`);
    assert.equal(await page.locator("#scenario option:checked").innerText(), "Customer <North> (1)");
    assert.equal(await page.locator("#mobile-network-search").innerText(), "Rescan");
    assert.equal(await page.locator('td[data-col="pc"]').innerText(), "fixture-pc");
    assert.equal(await page.locator('td[data-col="ip"]').innerText(), "•");
    await page.locator('#ip-display-toggle').click();
    assert.equal(await page.locator('td[data-col="ip"]').innerText(), "- / 192.168.9.42");
    await page.locator('#ip-display-toggle').click();
    assert.equal(await page.locator('td[data-col="ip"]').innerText(), "-", "SSH destinations do not establish public egress");
    await page.locator('#ip-display-toggle').click();
    assert.equal(await page.locator('td[data-col="ip"]').innerText(), "42");
    await page.locator('td[data-col="pc"] button').click();
    assert.match(await page.locator('dialog[open]').innerText(), /Discovered:.*Discovered addresses:/);
    assert.match(await page.locator('dialog[open]').innerText(), /Saved private IP: 192\.168\.9\.42/);
    assert.match(await page.locator('dialog[open]').innerText(), /No public IP check result is available yet/);
    await page.keyboard.press("Escape");
    await page.locator('#mobile-gpu-recordings > summary').click();
    await page.locator("#mobile-gpu-recordings p").filter({ hasText: "Power limiting: active in last sample" }).waitFor();
    const gpuText = await page.locator('#mobile-gpu-recordings').innerText();
    assert.match(gpuText, /Power limiting: active in last sample/);
    assert.match(gpuText, /Thermal slowdown: unsupported \/ unknown/);
    assert.match(gpuText, /retained reading/);
    assert.match(gpuText, /no per-job throttling or causation is inferred/);
    assert(gpuText.includes(new Date(gpuAt).toISOString()));
    assert.equal(await page.locator('td[data-col="gpuID"]').innerText(), "—", "PC GPU identity is not guessed as host GPU mapping");
    assert.equal(await page.locator('td[data-col="rewards"]').innerText(), "—", "recorder access does not unlock rewards");

    assert.equal(await page.locator("#mobile-network-search").getAttribute("href"), `/mobile/discovery?fleet=${fleetId}`);
    assert.match(await page.locator("#mobile-fleet-status").innerText(), /No rescan is needed for routine refresh/);
    await page.locator("#preview-tools > summary").click();
    await page.locator("#refresh-view").click();
    await page.reload();
    await page.locator('#fleet-rows > tr[data-host]').waitFor();
    assert(reads.length > 0 && reads.every(hosts => hosts.every(host => host === address)));
    assert.deepEqual(forbidden, [], "launch/reopen/refresh make no discovery, native mutations or external browser requests");
    assert.deepEqual(errors, []);
    await context.close();
  } finally {
    await browser.close();
    runtime.server.closeAllConnections();
    await new Promise<void>(accept => runtime.server.close(() => accept()));
    await rm(dataDirectory, { recursive: true, force: true });
  }
});
