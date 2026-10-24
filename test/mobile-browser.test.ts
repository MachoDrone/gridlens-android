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
