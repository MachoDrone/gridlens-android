import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { isIP } from "node:net";
import { runInNewContext } from "node:vm";
import test from "node:test";

const java = readFileSync(new URL("../android/app/src/main/java/com/gridlens/android/PublicIpReader.java", import.meta.url), "utf8");
const expression = java.split("static final String SCRIPT = ")[1]!.split("private static final Pattern STORED")[0]!;
const script = [...expression.matchAll(/"(?:\\.|[^"\\])*"/g)].map(match => JSON.parse(match[0]) as string).join("");

function fixture(family = "4") {
  const request = new EventEmitter();
  let callback: ((response: EventEmitter & { statusCode: number; headers: Record<string, string>; resume(): void }) => void) | undefined;
  let timeout: (() => void) | undefined;
  let options: Record<string, unknown> | undefined;
  let output = "";
  const exit = (code: number): never => { throw new Error(`exit:${code}`); };
  runInNewContext(script, {
    Buffer,
    require: (name: string) => {
      if (name === "node:net") return { isIP };
      assert.equal(name, "node:https");
      return { get: (args: Record<string, unknown>, next: typeof callback) => { options = args; callback = next; return request; } };
    },
    setTimeout: (next: () => void, ms: number) => { assert.equal(ms, 5000); timeout = next; return 1; },
    clearTimeout: (id: number) => { assert.equal(id, 1); timeout = undefined; },
    process: { exit, argv: ["node", family, "https://example.invalid"], stdout: { write: (value: string, complete?: () => void) => { output += value; complete?.(); } } },
  });
  return {
    options: () => options,
    output: () => output,
    timeout: () => timeout?.(),
    error: () => request.emit("error", new Error("fixture certificate failure")),
    respond: (statusCode: number, body: string, headers: Record<string, string> = {}) => {
      const response = Object.assign(new EventEmitter(), { statusCode, headers, resume() {} });
      callback!(response);
      response.emit("data", Buffer.from(body)); response.emit("end");
    },
  };
}

test("actual native helper measures NAT IPv4 with one fixed bounded TLS request", () => {
  const source = fixture();
  const options = source.options()!;
  assert.equal(options.hostname, "api.ipify.org"); assert.equal(options.family, 4); assert.equal(options.port, 443);
  assert.equal(options.path, "/"); assert.equal(options.method, "GET"); assert.equal(options.agent, false);
  assert.equal(options.maxHeaderSize, 8192);
  assert.deepEqual(Object.keys(options.headers as object), ["Accept"]);
  assert.equal((options.headers as Record<string, string>).Accept, "text/plain");
  assert.equal(options.rejectUnauthorized, undefined); // Keep Node's certificate verification default.
  source.respond(200, "8.8.8.8\n"); assert.equal(source.output(), "8.8.8.8\n");
  source.timeout(); // Successful completion cleared the total deadline.
});

test("native-approved IPv6 fallback has a fixed destination and refuses another address family", () => {
  const ipv6 = fixture("6");
  assert.equal(ipv6.options()!.hostname, "api64.ipify.org"); assert.equal(ipv6.options()!.family, 6);
  ipv6.respond(200, "2606:4700::1111"); assert.equal(ipv6.output(), "2606:4700::1111\n");
  assert.throws(() => fixture("https://example.invalid"), /exit:13/);
  assert.throws(() => fixture().respond(200, "2606:4700::1111"), /exit:13/);
  assert.throws(() => fixture("6").respond(200, "8.8.8.8"), /exit:13/);
});

test("redirects, non-IP bodies, overflow and TLS failure cannot become an address", () => {
  for (const [status, body] of [[301, "8.8.8.8"], [503, "8.8.8.8"], [200, "{\"ip\":\"8.8.8.8\"}"], [200, "x".repeat(129)]] as const) {
    const source = fixture(); assert.throws(() => source.respond(status, body), status === 200 ? /exit:13/ : /exit:12/); assert.equal(source.output(), "");
  }
  const tls = fixture(); assert.throws(() => tls.error(), /exit:10/); assert.equal(tls.output(), "");
  const stalled = fixture(); assert.throws(() => stalled.timeout(), /exit:11/); assert.equal(stalled.output(), "");
});

test("HTTP refusal exports only bounded retry metadata and never starts another request", () => {
  const throttled = fixture();
  assert.throws(() => throttled.respond(429, "untrusted response", { "retry-after": "3600" }), /exit:12/);
  assert.equal(throttled.output(), "retry-seconds:3600\n");
  const dated = fixture();
  assert.throws(() => dated.respond(503, "untrusted response", { "retry-after": "Wed, 01 Jan 2031 00:00:00 GMT" }), /exit:12/);
  assert.equal(dated.output(), `retry-at:${Date.parse("Wed, 01 Jan 2031 00:00:00 GMT")}\n`);
  const invalid = fixture();
  assert.throws(() => invalid.respond(403, "secret fixture", { "retry-after": "2031-01-01" }), /exit:12/);
  assert.equal(invalid.output(), "");
});

test("the shipped script also works through real Node -e argument and stdout handling", () => {
  const directory = mkdtempSync(join(tmpdir(), "gridlens-public-ip-script-"));
  try {
    const preload = join(directory, "https-fixture.cjs");
    writeFileSync(preload, `const {EventEmitter}=require('node:events');
require('node:https').get=(options, callback)=>{
  if(options.hostname!==(options.family===4?'api.ipify.org':'api64.ipify.org'))throw Error('Unexpected destination');
  const request=new EventEmitter();
  queueMicrotask(()=>{const response=Object.assign(new EventEmitter(),{statusCode:200,headers:{}});callback(response);
    response.emit('data',Buffer.from(options.family===4?'8.8.8.8':'2606:4700::1111'));response.emit('end');});
  return request;
};`);
    for (const [family, address] of [["4", "8.8.8.8"], ["6", "2606:4700::1111"]]) {
      const child = spawnSync(process.execPath, ["--require", preload, "-e", script, family!], {
        encoding: "utf8", timeout: 7000, maxBuffer: 2048, env: { NODE_NO_WARNINGS: "1" },
      });
      assert.equal(child.status, 0, child.stderr);
      assert.equal(child.stdout, `${address}\n`);
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
