import { startAndroidRuntime } from "./server.ts";

// This is the APK's dedicated executable entry. Android's /data/user/0 alias
// may resolve to /data/data in import.meta.url; comparing it with argv would
// incorrectly skip startup. Testable runtime exports live in server.ts instead.
const dataDirectory = process.argv[2];
const rawPort = process.argv[3] ?? "4173";
if (!dataDirectory || !/^\d+$/.test(rawPort)) {
  console.error("GridLens Android needs its app-private data directory and preferred port.");
  process.exitCode = 1;
} else {
  try {
    const { server } = await startAndroidRuntime({ dataDirectory, preferredPort: Number(rawPort) });
    const stop = (): void => { server.closeAllConnections(); server.close(); };
    process.once("SIGTERM", stop);
    process.once("SIGINT", stop);
  } catch {
    // Do not disclose tokens, rosters, headers or private filesystem paths.
    console.error("GridLens could not start its private local runtime.");
    process.exitCode = 1;
  }
}
