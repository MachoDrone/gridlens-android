import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import playwright from "../vendor/gridlens/node_modules/playwright/index.js";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";
import { mobilePcIpCheckHref } from "../vendor/gridlens/src/preview/mobile-scenarios.ts";

const hosts = ["1".repeat(32), "1".repeat(31) + "2", "1".repeat(31) + "3", "1".repeat(31) + "4"];
const pcId = "a".repeat(64);
const actionLabel = "Check / repair this PC’s IP";

test("native IP navigation permits only saved fleet and PC identifiers", () => {
  const fleet = "abcdefab-0000-4000-8000-000000000001";
  assert.equal(mobilePcIpCheckHref(fleet, pcId), `/mobile/ip-check?fleet=${fleet}&pc=${pcId}`);
  for (const invalid of [null, undefined, [], {}, 1, "", `${fleet}&command=curl`, "https://example.invalid", fleet.toUpperCase(), "00000000-0000-1000-8000-000000000001"]) {
    assert.equal(mobilePcIpCheckHref(invalid, pcId), null);
  }
  for (const invalid of [null, [], {}, 1, "", "A".repeat(64), "a".repeat(63), `${pcId}&host=192.0.2.1`, "../../credentials"])
    assert.equal(mobilePcIpCheckHref(fleet, invalid), null);
});

test("IP details open a scoped native repair action and separate old node status from IP results", { timeout: 90_000 }, async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-ip-repair-browser-"));
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
      op: "create", name: "Direct PC IP fixture", addresses: hosts,
      pcMetadata: hosts.slice(0, 3).map(address => ({ address, pcId, pcName: "three-host-pc", observedAtMs: at,
        ipAddresses: ["192.168.10.101", "172.17.0.1", "172.18.0.1"] })),
    }) });
    assert.equal(created.status, 200);
    const { fleetId } = await created.json() as { fleetId: string };
    const post = async (updates: unknown[]) => {
      const result = await fetch(origin + "/mobile/observations", { method: "POST", headers: native,
        body: JSON.stringify({ schema: 1, fleetId, updates }) });
      assert.equal(result.status, 200, await result.text());
    };
    await post(hosts.slice(0, 3).map((address, index) => ({ address,
      binding: { pcId, runtime: "docker", wrapperId: null, nodeContainerId: "b".repeat(63) + index },
      monitoring: { status: "unsupported", checkedAtMs: at } })));
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    const requests: string[] = [], nativeNavigations: string[] = [], errors: string[] = [];
    await context.route("**/*", async route => {
      const request = route.request(), url = new URL(request.url());
      assert.equal(url.origin, origin, "isolated UI must not contact public services");
      assert.equal(request.method(), "GET", "web content receives no mutation authority");
      requests.push(url.pathname);
      if (url.pathname === "/mobile/ip-check") {
        assert.equal(request.isNavigationRequest(), true, "native entry must be a user navigation, not fetch");
        nativeNavigations.push(url.pathname + url.search);
        await route.fulfill({ status: 204, body: "" }); return;
      }
      await route.continue();
    });
    await context.addCookies([{ name: SESSION_COOKIE, value: runtime.token, url: origin, httpOnly: true, sameSite: "Strict" }]);
    const page = await context.newPage(); page.on("pageerror", error => errors.push(error.message));
    await page.goto(origin + `/?scenario=fleet:${fleetId}`);
    await page.locator('#fleet-rows > tr[data-host]').nth(3).waitFor();
    await page.waitForLoadState("networkidle");
    const expectedHref = `/mobile/ip-check?fleet=${fleetId}&pc=${pcId}`;
    for (const address of hosts.slice(0, 3)) {
      const before = requests.length;
      await page.locator(`#fleet-rows > tr[data-host="${address}"] td[data-col="ip"] button`).click();
      const link = page.getByRole("link", { name: actionLabel, exact: true });
      assert.equal(await link.getAttribute("href"), expectedHref, "all three hosts select their one saved PC");
      const facts = await page.locator('#info-body .info-facts').innerText();
      assert.match(facts, /No public IP check result is available yet/);
      assert.match(facts, /Saved private IP: 192\.168\.10\.101/);
      assert.doesNotMatch(facts, /unsupported|PC monitoring|Node status/, "old node status is not an IP failure");
      assert.match(await page.locator('#info-body .info-source-body').textContent() ?? "", /Saved node-monitoring status: unsupported/);
      assert.ok((await page.locator('#info-body .info-source-body').textContent())!.includes(new Date(at).toISOString()));
      assert.equal(requests.length, before, "opening IP details starts no PC request");
      const target = await link.boundingBox(), dialog = await page.locator('#info-dialog').boundingBox();
      assert.ok(target && dialog && target.height >= 36 && target.x >= dialog.x && target.x + target.width <= dialog.x + dialog.width);
      await page.locator('#close-info').click();
    }
    // Missing association cannot select a credential or arbitrary SSH destination.
    await page.locator(`#fleet-rows > tr[data-host="${hosts[3]}"] td[data-col="ip"] button`).click();
    assert.equal(await page.locator('.mobile-ip-check-action').count(), 0);
    await page.locator('#close-info').click();
    // Ordinary PC details must not inherit a previously opened IP action.
    await page.locator(`#fleet-rows > tr[data-host="${hosts[0]}"] td[data-col="pc"] button`).click();
    assert.equal(await page.locator('.mobile-ip-check-action').count(), 0);
    await page.locator('#close-info').click();
    await page.locator(`#fleet-rows > tr[data-host="${hosts[0]}"] td[data-col="ip"] button`).click();
    await page.getByRole("link", { name: actionLabel, exact: true }).click();
    await page.waitForFunction(() => !document.querySelector<HTMLDialogElement>('#info-dialog')!.open);
    assert.deepEqual(nativeNavigations, [expectedHref]);
    assert.equal(new URL(page.url()).search, `?scenario=fleet:${fleetId}`, "native handler keeps the saved fleet page");
    await page.locator(`#fleet-rows > tr[data-host="${hosts[1]}"] td[data-col="ip"] button`).click();
    await page.getByRole("link", { name: actionLabel, exact: true }).focus();
    await page.keyboard.press("Enter");
    await page.waitForFunction(() => !document.querySelector<HTMLDialogElement>('#info-dialog')!.open);
    assert.deepEqual(nativeNavigations, [expectedHref, expectedHref]);
    // In an ordinary browser the fixed native path is denied, and cannot call SSH.
    const denied = await fetch(origin + expectedHref, { headers: { Cookie: `${SESSION_COOKIE}=${runtime.token}` } });
    assert.equal(denied.status, 403); await denied.body?.cancel();
    const observed = Date.now();
    await post([{ pcId, network: { schema: 1, source: "pc-egress-ipify", status: "ok", observedAtMs: observed,
      publicIp: "8.8.8.8", publicObservedAtMs: observed, privateIp: "192.168.10.101", privateObservedAtMs: observed } }]);
    await page.reload(); await page.locator('#fleet-rows > tr[data-host]').nth(3).waitFor();
    await page.locator('#ip-display-toggle').click();
    await page.waitForFunction(() => Array.from(document.querySelectorAll('td[data-col="ip"]')).filter(cell => cell.textContent === '8.8.8.8 / 192.168.10.101').length === 3);
    await page.locator(`#fleet-rows > tr[data-host="${hosts[0]}"] td[data-col="ip"] button`).click();
    assert.match(await page.locator('#info-body .info-facts').innerText(), /Public IP: 8\.8\.8\.8/);
    assert.doesNotMatch(await page.locator('#info-body .info-facts').innerText(), /unsupported/);
    assert.ok((await page.locator('#info-body .info-facts').innerText()).includes(new Date(observed).toISOString()));
    await page.locator('#close-info').click();
    await page.goto(origin + '/?scenario=fresh');
    await page.locator('#fleet-rows > tr[data-host]').first().waitFor();
    await page.locator('td[data-col="pc"] button').first().click();
    assert.equal(await page.locator('.mobile-ip-check-action').count(), 0, "synthetic details have no native connection action");
    assert.equal(nativeNavigations.length, 2);
    assert.deepEqual(errors, []);
    await context.close();
  } finally {
    await browser.close(); runtime.server.closeAllConnections();
    await new Promise<void>(accept => runtime.server.close(() => accept()));
    await rm(dataDirectory, { recursive: true, force: true });
  }
});
