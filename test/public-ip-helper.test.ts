import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { createServer } from "node:https";
import type { AddressInfo } from "node:net";
import { runInNewContext } from "node:vm";
import test from "node:test";

// Execute the production command expression, with only its transport redirected
// by an isolated PATH wrapper. Actual curl performs TLS, HTTP, family selection,
// configuration suppression, body bounds and the ten-second request timeout.
const java = await readFile(new URL("../android/app/src/main/java/com/gridlens/android/PublicIpReader.java", import.meta.url), "utf8");
const commandMethod = java.split("static String command(int family) {")[1]!.split("\n    }")[0]!;
const expression = commandMethod.match(/return ([\s\S]*);\s*$/)![1]!;
const command = (family: 4 | 6): string => runInNewContext(expression, { family }) as string;
const execute = (text: string, env: NodeJS.ProcessEnv): Promise<{ code: number | null; stdout: string; stderr: string }> =>
  new Promise((accept, reject) => {
    const child = spawn("/bin/sh", ["-c", text], { env, timeout: 15_000 });
    let stdout = "", stderr = "";
    child.stdout.on("data", data => { stdout += String(data); });
    child.stderr.on("data", data => { stderr += String(data); });
    child.on("error", reject);
    child.on("close", code => accept({ code, stdout, stderr }));
  });

test("actual host curl commands use isolated IPv4/IPv6 HTTPS with TLS, no redirects/config, bounds and waits", { timeout: 40_000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-host-curl-"));
  const cert = join(directory, "cert.pem"), key = join(directory, "key.pem");
  const generated = spawnSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key,
    "-out", cert, "-days", "1", "-subj", "/CN=api.ipify.org", "-addext", "subjectAltName=DNS:api.ipify.org,DNS:api6.ipify.org"], { stdio: "ignore" });
  assert.equal(generated.status, 0, "isolated TLS certificate generation");
  let mode = "nat";
  const requests: { host: string | undefined; path: string | undefined; family: string | undefined }[] = [];
  const server = createServer({ key: await readFile(key), cert: await readFile(cert) }, (request, response) => {
    requests.push({ host: request.headers.host, path: request.url, family: request.socket.remoteFamily });
    if (mode === "timeout") return;
    if (mode === "redirect") { response.writeHead(302, { Location: "https://example.invalid/forbidden" }); response.end(); return; }
    if (mode === "refused") { response.writeHead(429, { "Retry-After": "7200" }); response.end("wait"); return; }
    if (mode === "oversize") { response.writeHead(200, { "Content-Length": "129" }); response.end("x".repeat(129)); return; }
    response.end(mode === "ipv6" ? "2606:4700::1111\n" : mode === "malformed" ? "not-an-ip\n" : "8.8.8.8\n");
  });
  server.on("tlsClientError", () => {});
  await new Promise<void>((accept, reject) => { server.once("error", reject); server.listen(0, "::", accept); });
  const port = (server.address() as AddressInfo).port;
  try {
    await writeFile(join(directory, "curl"), `#!${process.execPath}
const {spawnSync}=require('node:child_process');
const args=process.argv.slice(2);
if(args[0]!=='-q')process.exit(99);
const family=args[1]; const host=family==='-4'?'api.ipify.org':'api6.ipify.org';
if(!['-4','-6'].includes(family)||args[args.length-1]!=='https://'+host)process.exit(98);
const transport=host+':443:'+(family==='-4'?'127.0.0.1':'[::1]')+':${port}';
const trust=process.env.FIXTURE_UNTRUSTED==='yes'?[]:['--cacert',${JSON.stringify(cert)}];
const r=spawnSync('/usr/bin/curl',['-q',...trust,'--noproxy','*','--connect-to',transport,...args.slice(1)],{stdio:'inherit'});
process.exit(r.status??97);
`, { mode: 0o700 });
    // If startup config were read, these options could permit redirects or add
    // requests. The production first -q must suppress it completely.
    await writeFile(join(directory, ".curlrc"), 'insecure\nlocation\nurl = "https://example.invalid/forbidden"\n');
    const env = { ...process.env, PATH: directory, CURL_HOME: directory, NODE_OPTIONS: "" };
    const nat = await execute(command(4), env);
    assert.equal(nat.code, 0, nat.stderr); assert.match(nat.stdout, /HTTP\/1\.1 200/); assert.match(nat.stdout, /8\.8\.8\.8\n$/);
    const sharedNat = await execute(command(4), env);
    assert.equal(sharedNat.code, 0); assert.match(sharedNat.stdout, /8\.8\.8\.8\n$/);
    assert.deepEqual(requests.map(value => value.host), ["api.ipify.org", "api.ipify.org"]);
    mode = "ipv6";
    const ipv6 = await execute(command(6), env);
    assert.equal(ipv6.code, 0, ipv6.stderr); assert.match(ipv6.stdout, /2606:4700::1111\n$/);
    assert.equal(requests.at(-1)!.host, "api6.ipify.org"); assert.equal(requests.at(-1)!.family, "IPv6");
    mode = "redirect";
    const redirect = await execute(command(4), env);
    assert.equal(redirect.code, 0); assert.match(redirect.stdout, /HTTP\/1\.1 302/);
    assert.equal(requests.length, 4, "redirect must not dispatch another request");
    mode = "refused";
    const refused = await execute(command(4), env);
    assert.match(refused.stdout, /HTTP\/1\.1 429/); assert.match(refused.stdout, /Retry-After: 7200/);
    mode = "oversize";
    assert.equal((await execute(command(4), env)).code, 63, "curl must reject bodies over 128 bytes");
    mode = "malformed";
    assert.match((await execute(command(4), env)).stdout, /not-an-ip/, "bounded invalid body is supplied to native validation");
    assert.equal((await execute(command(4), { ...env, FIXTURE_UNTRUSTED: "yes" })).code, 60, "TLS must reject an untrusted certificate");
    mode = "timeout";
    const started = performance.now();
    assert.equal((await execute(command(4), env)).code, 28);
    assert.ok(performance.now() - started >= 9000 && performance.now() - started < 14000, "actual curl ten-second total deadline");
    assert.ok(requests.every(value => value.path === "/"));
    assert.doesNotMatch(command(4), /sudo|docker|podman|node |--insecure|--location/);
  } finally {
    server.closeAllConnections(); await new Promise<void>(accept => server.close(() => accept()));
    await rm(directory, { recursive: true, force: true });
  }
});

test("missing host curl fails explicitly without package or container fallback", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gridlens-no-curl-"));
  try {
    const result = await execute(command(4), { ...process.env, PATH: directory });
    assert.equal(result.code, 127); assert.equal(result.stdout, "");
  } finally { await rm(directory, { recursive: true, force: true }); }
});
