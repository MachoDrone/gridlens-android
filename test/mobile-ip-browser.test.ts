import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import playwright from "../vendor/gridlens/node_modules/playwright/index.js";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";
import type { PcNetworkObservation, PublicNetworkFailure } from "../vendor/gridlens/src/fleet/pc-observations.ts";

const preference = "gridlens.preview.ip-mode.v1";
const modes = ["dot", "full", "public-full", "ext", "private-full", "int"] as const;
const labels = ["hidden", "both full addresses", "external IP / NAT", "external last octet", "internal IP", "internal last octet"];
const hosts = ["1".repeat(32), "1".repeat(31) + "2", "1".repeat(31) + "3", "1".repeat(31) + "4"];
const pcIds = ["a".repeat(64), "a".repeat(64), "b".repeat(64), "c".repeat(64)];

test("Android six IP modes preserve saved meanings, PC-only observations, full sorting and zero toggle requests", { timeout: 120_000 }, async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-six-ip-browser-"));
  const empty = async () => ({ hosts: [], nextRefreshAtMs: Date.now() + 60_000 });
  const runtime = await startAndroidRuntime({ dataDirectory, directory: resolve("dist/runtime/web"), preferredPort: 0,
    providers: { metadataProvider: empty, nodeInfoProvider: empty, jobProvider: empty, queueProvider: empty,
      solProvider: empty, nosProvider: empty, stakingProvider: empty } });
  const origin = `http://127.0.0.1:${runtime.port}`;
  const native = { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" };
  const browser = await playwright.chromium.launch({ headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) });
  try {
    const at = Date.now() - 60_000;
    const created = await fetch(origin + "/mobile/fleets", { method: "POST", headers: native, body: JSON.stringify({
      op: "create", name: "PC IP fixture", addresses: hosts, pcMetadata: hosts.map((address, index) => ({
        address, pcId: pcIds[index], pcName: `fixture-${index}`, observedAtMs: at,
        ipAddresses: [index < 2 ? "192.168.9.42" : index === 2 ? "8.8.8.8" : "fd12:3456::1"],
      })),
    }) });
    assert.equal(created.status, 200);
    const { fleetId } = await created.json() as { fleetId: string };
    const observation = (publicIp: string | null, privateIp: string | null, observedAtMs = at): PcNetworkObservation => ({
      schema: 1, source: "pc-egress-ipify", observedAtMs, status: publicIp ? "ok" : "unavailable",
      publicIp, publicObservedAtMs: publicIp ? observedAtMs : null, privateIp, privateObservedAtMs: privateIp ? observedAtMs : null,
    });
    const post = async (pcId: string, network: PcNetworkObservation): Promise<void> => {
      const result = await fetch(origin + "/mobile/observations", { method: "POST", headers: native,
        body: JSON.stringify({ schema: 1, fleetId, updates: [{ pcId, network }] }) });
      assert.equal(result.status, 200, await result.text());
    };
    await post(pcIds[0]!, observation("8.8.4.4", "192.168.9.42"));
    await post(pcIds[2]!, observation("8.8.4.4", null));
    await post(pcIds[3]!, observation("2606:4700:4700::1111", "fd12:3456::1"));
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    const requests: string[] = [], errors: string[] = [];
    await context.route("**/*", route => {
      const request = route.request(), url = new URL(request.url()); requests.push(url.pathname);
      assert.equal(url.origin, origin, "isolated fixture cannot access public services");
      assert.equal(request.method(), "GET", "WebView has no mutation authority");
      return route.continue();
    });
    await context.addCookies([{ name: SESSION_COOKIE, value: runtime.token, url: origin, httpOnly: true, sameSite: "Strict" }]);
    const page = await context.newPage(); page.on("pageerror", error => errors.push(error.message));
    const expected = [
      ["•", "8.8.4.4 / 192.168.9.42", "8.8.4.4", ".4", "192.168.9.42", ".42"],
      ["•", "8.8.4.4 / 192.168.9.42", "8.8.4.4", ".4", "192.168.9.42", ".42"],
      ["•", "8.8.4.4 / −", "8.8.4.4", ".4", "−", "−"],
      ["•", "2606:4700:4700::1111 / fd12:3456::1", "2606:4700:4700::1111", "2606:4700:4700::1111", "fd12:3456::1", "fd12:3456::1"],
    ];
    await page.goto(origin + `/?scenario=fleet:${fleetId}`);
    await page.locator('#fleet-rows > tr[data-host]').nth(3).waitFor();
    await page.locator('#column-headings th[data-col="ip"] .column-label').click();
    await page.waitForLoadState("networkidle");
    const initialOrder = await page.locator('#fleet-rows > tr[data-host]').evaluateAll(rows => rows.map(row => row.getAttribute("data-host")));
    const beforeRequests = requests.length;
    const cycle = await page.evaluate(({ hosts, preference }) => {
      const toggle = document.querySelector<HTMLButtonElement>("#ip-display-toggle")!;
      const capture = () => ({ mode: toggle.dataset.mode, title: toggle.title, label: toggle.getAttribute("aria-label"),
        saved: localStorage.getItem(preference), values: hosts.map(host => document.querySelector(`#fleet-rows > tr[data-host="${host}"] td[data-col="ip"]`)!.textContent),
        order: Array.from(document.querySelectorAll('#fleet-rows > tr[data-host]')).map(row => row.getAttribute("data-host")) });
      const results = [capture()];
      for (let index = 0; index < 6; index++) { toggle.click(); results.push(capture()); }
      return results;
    }, { hosts, preference });
    for (let step = 0; step < cycle.length; step++) {
      const index = step % 6, result = cycle[step]!;
      assert.equal(result.mode, modes[index]);
      assert.deepEqual(result.values, expected.map(values => values[index]));
      assert.deepEqual(result.order, initialOrder, "display mode must not change full-address sorting");
      assert.match(result.title, new RegExp(`IP display: ${labels[index]}; next: ${labels[(index + 1) % 6]}`));
      assert.equal(result.label, `Toggle IP display. ${result.title}`);
      if (step > 0) assert.equal(result.saved, modes[index]);
    }
    assert.equal(requests.length, beforeRequests, "all six toggle clicks are presentation-only");
    for (let index = 0; index < modes.length; index++) {
      await page.evaluate(({ preference, mode }) => localStorage.setItem(preference, mode), { preference, mode: modes[index]! });
      await page.reload();
      await page.waitForFunction(({ address, text }) => document.querySelector(`#fleet-rows > tr[data-host="${address}"] td[data-col="ip"]`)?.textContent === text,
        { address: hosts[0]!, text: expected[0]![index]! });
      assert.equal(await page.locator('#ip-display-toggle').getAttribute("data-mode"), modes[index]);
      assert.deepEqual(await page.locator('#fleet-rows > tr[data-host]').evaluateAll(rows => rows.map(row => row.getAttribute("data-host"))), initialOrder);
    }
    for (const saved of [null, "invalid", "FULL"]) {
      await page.evaluate(({ preference, saved }) => saved === null ? localStorage.removeItem(preference) : localStorage.setItem(preference, saved), { preference, saved });
      await page.reload(); await page.locator('#fleet-rows > tr[data-host]').nth(3).waitFor();
      assert.equal(await page.locator('#ip-display-toggle').getAttribute("data-mode"), "dot");
      assert.deepEqual(await page.locator('td[data-col="ip"]').allTextContents(), ["•", "•", "•", "•"]);
    }
    // A PC record supplies every associated host even without any node binding/status.
    const cache = await fetch(origin + `/mobile/observations?fleet=${fleetId}`, { headers: native });
    const records = await cache.json() as { hosts: unknown[]; pcNetworks: unknown[] };
    assert.equal(records.hosts.length, 0); assert.equal(records.pcNetworks.length, 3);
    const failures: [PublicNetworkFailure, string][] = [["missing-curl", "curl is not installed"], ["dns", "DNS lookup failed"],
      ["connection", "connection failed"], ["timeout", "request timed out"], ["tls", "TLS verification"],
      ["http-refusal", "HTTP request refused (HTTP 429)"], ["invalid-response", "invalid response"]];
    for (const [index, [kind, text]] of failures.entries()) {
      const network = observation(null, null, at + index + 1);
      network.publicFailure = kind; network.privateFailure = "interface-read-failure";
      network.retryAtMs = at + 3_600_000;
      if (kind === "http-refusal") network.httpStatus = 429;
      await post(pcIds[0]!, network);
      await page.reload(); await page.locator('#fleet-rows > tr[data-host]').nth(3).waitFor();
      await page.waitForLoadState("networkidle");
      await page.locator(`#fleet-rows > tr[data-host="${hosts[0]}"] td[data-col="ip"] button`).click();
      const content = await page.locator('#info-dialog').innerText();
      assert.ok(content.includes(text), content); assert.match(content, /Private IP failure: interface read failed/);
      assert.match(content, /Public IP retry eligible/); assert.match(content, /Retained public IP: 8\.8\.4\.4/);
      assert.ok(content.includes(new Date(at).toISOString()), "failure retains original successful source time");
      await page.locator('#close-info').click();
    }
    // Synthetic Android views use the same cycle, including placeholders and leading dots.
    await page.evaluate(preference => localStorage.removeItem(preference), preference);
    await page.goto(origin + "/?scenario=scale");
    await page.locator('#fleet-rows > tr[data-host]').nth(199).waitFor();
    const syntheticValues: string[][] = [];
    for (let step = 0; step < 7; step++) {
      syntheticValues.push(await page.locator('td[data-col="ip"]').allTextContents());
      assert.equal(await page.locator('#ip-display-toggle').getAttribute('data-mode'), modes[step % 6]);
      if (step < 6) await page.locator('#ip-display-toggle').click();
    }
    assert.ok(syntheticValues[1]!.some(text => text.startsWith("− / ")));
    assert.ok(syntheticValues[1]!.some(text => text.endsWith(" / −")));
    assert.ok(syntheticValues[3]!.every(text => /^\.\d+$|^−$/.test(text)));
    assert.ok(syntheticValues[5]!.every(text => /^\.\d+$|^−$/.test(text)));
    assert.deepEqual(syntheticValues[6], syntheticValues[0]);
    assert.deepEqual(errors, []);
    await context.close();
  } finally {
    await browser.close(); runtime.server.closeAllConnections();
    await new Promise<void>(accept => runtime.server.close(() => accept()));
    await rm(dataDirectory, { recursive: true, force: true });
  }
});
