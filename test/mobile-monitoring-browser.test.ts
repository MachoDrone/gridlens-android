import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import playwright from "../vendor/gridlens/node_modules/playwright/index.js";
import type { Locator, Page } from "../vendor/gridlens/node_modules/playwright/index.js";
import { startAndroidRuntime, SESSION_COOKIE } from "../runtime/server.ts";
import type { GpuSample, PcUpdate } from "../vendor/gridlens/src/fleet/pc-observations.ts";
import type { JobHistoryRecord } from "../vendor/gridlens/src/fleet/job-history.ts";

const first = "1".repeat(32), second = "1".repeat(31) + "2";
const gpuUuid = "GPU-00000000-1111-2222-3333-444444444444";
const binding = { pcId: "a".repeat(64), runtime: "docker" as const, wrapperId: null, nodeContainerId: "b".repeat(64) };
const row = (page: Page, address: string) => page.locator(`#fleet-rows > tr[data-host="${address}"]`);
const history = (page: Page, address: string) => page.locator(`tr[data-job-history-host="${address}"]`);
async function exactText(locator: Locator, text: string): Promise<void> {
  await locator.filter({ hasText: new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`) }).waitFor();
  assert.equal(await locator.innerText(), text);
}
function historyRecord(address: string, at: number): JobHistoryRecord {
  const ids = address === first ? ["4", "5", "6", "3"] : ["7", "8", "9", "A"];
  const intervals = [[-60, 0], [-180, -120], [-50, -5], [-600, 0]] as const;
  return { address, jobs: intervals.map(([start, end], index) => ({
    address: "1".repeat(31) + ids[index]!, node: address, state: index === 3 ? "RUNNING" as const : "COMPLETED" as const,
    result: index === 3 ? null : "success" as const, timeStart: at / 1000 + start, timeEnd: index === 3 ? null : at / 1000 + end,
    timeout: 600, checkedAtMs: at,
  })), checkedAtMs: at, validUntilMs: at + 300_000, totalJobs: 4,
    retained: false, pending: false, retryAtMs: null, error: null };
}

test("phone Fleet reads exact rewards and retained failures from the native cache, with fixed monitoring navigation", { timeout: 120_000 }, async () => {
  const dataDirectory = await mkdtemp(join(tmpdir(), "gridlens-monitoring-phone-"));
  await writeFile(join(dataDirectory, "hosts.json"), "[]"); await writeFile(join(dataDirectory, "real200.json"), "[]");
  const empty = async () => ({ hosts: [], nextRefreshAtMs: Date.now() + 60000 });
  const runtime = await startAndroidRuntime({ dataDirectory, directory: resolve("dist/runtime/web"), preferredPort: 0,
    providers: { metadataProvider: empty, nodeInfoProvider: empty, jobProvider: empty, queueProvider: empty,
      solProvider: empty, nosProvider: empty, stakingProvider: empty } });
  const origin = `http://127.0.0.1:${runtime.port}`;
  const native = { "X-GridLens-Token": runtime.token, "Content-Type": "application/json" };
  const browser = await playwright.chromium.launch({ headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) });
  try {
    const created = await fetch(origin + "/mobile/fleets", { method: "POST", headers: native,
      body: JSON.stringify({ op: "create", name: "Synthetic monitored fleet", addresses: [first, second] }) });
    assert.equal(created.status, 200);
    const { fleetId } = await created.json() as { fleetId: string };
    const at = Math.floor(Date.now() / 1000) * 1000 - 5000;
    const post = async (updates: PcUpdate[]): Promise<void> => {
      const response = await fetch(origin + "/mobile/observations", { method: "POST", headers: native,
        body: JSON.stringify({ schema: 1, fleetId, updates }) });
      assert.equal(response.status, 200, await response.text());
    };
    const gpuSamples: GpuSample[] = [-60000, -45000, 0].map((offset, index) =>
      [at + offset, 0, 15, 0, index === 0 ? 100 : 200, null, null, null]);
    await post([
      { address: first, binding, rewards: { schema: 1, address: first, observedAtMs: at, status: "ok", amount: "123.456789" },
        monitoring: { status: "active", checkedAtMs: at },
        gpu: { schema: 2, address: first, observedAtMs: at, status: "ok", gpuUuids: [gpuUuid], intervalSeconds: 15,
          fromAtMs: at - 60000, toAtMs: at, truncated: false, clockTrusted: true, samples: gpuSamples } },
      { address: second, binding: { ...binding, nodeContainerId: "c".repeat(64) },
        rewards: { schema: 1, address: second, observedAtMs: at, status: "ok", amount: "0.000000" },
        monitoring: { status: "active", checkedAtMs: at } },
    ]);
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    const forbidden: string[] = [], errors: string[] = [], historyRequests: string[] = [];
    await context.route("**/*", async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.origin !== origin || request.method() !== "GET" || /^\/mobile\/(?:discovery|select|monitoring)$/.test(url.pathname)) {
        forbidden.push(request.url()); await route.abort(); return;
      }
      if (url.pathname === "/preview/job-history.json") {
        const addresses = url.searchParams.getAll("host");
        assert.ok(addresses.length > 0 && addresses.every(address => address === first || address === second));
        historyRequests.push(...addresses);
        await route.fulfill({ json: { hosts: addresses.map(address => historyRecord(address, at)), serverTimeMs: Date.now() } });
        return;
      }
      await route.continue();
    });
    await context.addCookies([{ name: SESSION_COOKIE, value: runtime.token, url: origin, httpOnly: true, sameSite: "Strict" }]);
    const page = await context.newPage(); page.on("pageerror", error => errors.push(error.message));
    await page.goto(origin + `/?scenario=fleet:${fleetId}`);
    const amount = row(page, first).locator('td[data-col="rewards"]');
    const zero = row(page, second).locator('td[data-col="rewards"]');
    await exactText(amount, "123.456789"); await exactText(zero, "0");
    assert.equal(await amount.evaluate(cell => cell.classList.contains("metadata-retained")), false);
    assert.equal(await zero.evaluate(cell => cell.classList.contains("metadata-retained")), false);
    assert.match(await amount.getAttribute("aria-label") ?? "", new RegExp(new Date(at).toISOString().replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    assert.equal(await page.locator("#mobile-enable-monitoring").getAttribute("href"), `/mobile/monitoring?fleet=${fleetId}`);
    assert.equal(await page.locator("#mobile-enable-monitoring").innerText(), "Enable / repair monitoring");
    assert.equal(await page.evaluate(() => document.cookie), "", "native token remains HttpOnly");

    await page.locator('#column-headings th[data-col="rewards"]').click();
    await page.waitForFunction(address => document.querySelector<HTMLTableRowElement>('#fleet-rows > tr[data-host]')?.dataset.host === address, second);
    await page.locator('#column-headings th[data-col="rewards"]').click();
    await page.waitForFunction(address => document.querySelector<HTMLTableRowElement>('#fleet-rows > tr[data-host]')?.dataset.host === address, first);

    await page.locator("#preview-tools > summary").click();
    await page.locator("#column-info").click();
    assert.match(await page.locator("#info-dialog").innerText(), /authorized node reader/);
    assert.doesNotMatch(await page.locator("#info-dialog").innerText(), /source is not connected yet/);
    await page.locator("#close-info").click();
    await page.locator("#preview-tools > summary").click();
    await row(page, first).locator(".jobs-expand").click();
    const conditions = history(page, first).locator('td[data-history-col="gpu conditions"]');
    const unended = history(page, first).locator(`tr[data-job="${"1".repeat(31)}3"] td[data-history-col="gpu conditions"]`);

    await conditions.filter({ hasText: "Thermal slowdown" }).waitFor();
    assert.equal(await conditions.count(), 4);
    await exactText(unended, "—");
    assert.match(await unended.getAttribute("aria-label") ?? "", /not currently confirmed/);
    const recordedJob = history(page, first).locator(`tr[data-job="${"1".repeat(31)}4"] td[data-history-col="gpu conditions"]`);
    await exactText(recordedJob, "Thermal slowdown");
    const detail = await recordedJob.getAttribute("aria-label") ?? "";
    assert.match(detail, /Observed during this job/); assert.match(detail, /counter increase/);
    assert.match(detail, /does not establish/); assert.match(detail, new RegExp(gpuUuid));
    assert.match(detail, /Partial coverage/); assert.match(detail, /largest interior gap 45s/);
    const oldJob = history(page, first).locator(`tr[data-job="${"1".repeat(31)}5"] td[data-history-col="gpu conditions"]`);
    await exactText(oldJob, "—"); assert.match(await oldJob.getAttribute("aria-label") ?? "", /No GPU samples overlap/);
    const partiallyCovered = history(page, first).locator(`tr[data-job="${"1".repeat(31)}6"] td[data-history-col="gpu conditions"]`);
    assert.match(await partiallyCovered.getAttribute("aria-label") ?? "", /Partial coverage/);
    await row(page, second).locator(".jobs-expand").click();
    await history(page, second).locator(".job-history-table tbody > tr").nth(2).waitFor();
    assert.deepEqual(await history(page, second).locator('td[data-history-col="gpu conditions"]').allTextContents(), ["—", "—", "—", "—"],
      "a different host cannot borrow another PC's GPU samples");

    await post([
      { address: first, binding, rewards: { schema: 1, address: first, observedAtMs: at + 1000, status: "authentication-failed" },
        monitoring: { status: "authorization-required", checkedAtMs: at + 1000 } },
      { address: second, binding: { ...binding, nodeContainerId: "c".repeat(64) },
        rewards: { schema: 1, address: second, observedAtMs: at + 1000, status: "unavailable" },
        monitoring: { status: "unreachable", checkedAtMs: at + 1000 } },
    ]);
    await page.locator("#preview-tools > summary").click();
    await page.locator("#refresh-view").click();
    await page.waitForFunction(address => document.querySelector(`#fleet-rows > tr[data-host="${address}"] td[data-col="rewards"]`)?.classList.contains("metadata-retained"), first);
    await exactText(amount, "123.456789"); await exactText(zero, "0");
    assert.match(await amount.getAttribute("aria-label") ?? "", /Retained 123\.456789 NOS/);
    assert.match(await amount.getAttribute("aria-label") ?? "", /authentication-failed/);
    assert.ok((await amount.getAttribute("aria-label"))!.includes(new Date(at).toISOString()), "failed attempts do not replace original success time");
    assert.equal(await zero.evaluate(cell => cell.classList.contains("metadata-retained")), true);
    await page.reload();
    await exactText(row(page, first).locator('td[data-col="rewards"]'), "123.456789");
    assert.equal(await row(page, first).locator('td[data-col="rewards"]').evaluate(cell => cell.classList.contains("metadata-retained")), true);
    assert.deepEqual(forbidden, [], "browser cannot initiate native actions or public requests during cache refresh");
    assert.ok(historyRequests.includes(first) && historyRequests.includes(second));
    assert.deepEqual(errors, []);
    await context.close();
  } finally {
    await browser.close(); runtime.server.closeAllConnections();
    await new Promise<void>(accept => runtime.server.close(() => accept()));
    await rm(dataDirectory, { recursive: true, force: true });
  }
});
