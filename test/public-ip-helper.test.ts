import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { readFileSync } from "node:fs";
import { isIP } from "node:net";
import { runInNewContext } from "node:vm";
import test from "node:test";

const java = readFileSync(new URL("../android/app/src/main/java/com/gridlens/android/PublicIpReader.java", import.meta.url), "utf8");
const expression = java.split("static final String SCRIPT = ")[1]!.split("private static final Pattern STORED")[0]!;
const script = [...expression.matchAll(/"(?:\\.|[^"\\])*"/g)].map(match => JSON.parse(match[0]) as string).join("");

function fixture() {
  const request = new EventEmitter();
  let callback: ((response: EventEmitter & { statusCode: number; resume(): void }) => void) | undefined;
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
    process: { exit, argv: ["node", "untrusted-url", "https://example.invalid"], stdout: { write: (value: string) => { output += value; } } },
  });
  return {
    options: () => options,
    output: () => output,
    timeout: () => timeout?.(),
    error: () => request.emit("error", new Error("fixture certificate failure")),
    respond: (statusCode: number, body: string) => {
      const response = Object.assign(new EventEmitter(), { statusCode, resume() {} });
      callback!(response);
      response.emit("data", Buffer.from(body)); response.emit("end");
    },
  };
}

test("actual native helper makes only the fixed bounded TLS request without addresses or credentials", () => {
  const source = fixture();
  const options = source.options()!;
  assert.equal(options.hostname, "api64.ipify.org"); assert.equal(options.port, 443);
  assert.equal(options.path, "/"); assert.equal(options.method, "GET"); assert.equal(options.agent, false);
  assert.equal(options.maxHeaderSize, 8192);
  assert.deepEqual(Object.keys(options.headers as object), ["Accept"]);
  assert.equal((options.headers as Record<string, string>).Accept, "text/plain");
  assert.equal(options.rejectUnauthorized, undefined); // Keep Node's certificate verification default.
  source.respond(200, "8.8.8.8\n"); assert.equal(source.output(), "8.8.8.8\n");
  source.timeout(); // Successful completion cleared the total deadline.
});

test("IPv6 succeeds; redirects, non-IP bodies, overflow and TLS failure stay unavailable", () => {
  const ipv6 = fixture(); ipv6.respond(200, "2606:4700::1111"); assert.equal(ipv6.output(), "2606:4700::1111\n");
  for (const [status, body] of [[301, "8.8.8.8"], [503, "8.8.8.8"], [200, "{\"ip\":\"8.8.8.8\"}"], [200, "x".repeat(129)]] as const) {
    const source = fixture(); assert.throws(() => source.respond(status, body), /exit:1/); assert.equal(source.output(), "");
  }
  const tls = fixture(); assert.throws(() => tls.error(), /exit:1/); assert.equal(tls.output(), "");
  const stalled = fixture(); assert.throws(() => stalled.timeout(), /exit:1/); assert.equal(stalled.output(), "");
});
