import { createRequire } from "node:module";

// The released helper is CommonJS for the Node20 runtime in existing workloads.
// Its fixtures generate unfunded identities only in memory and inject every read.
createRequire(import.meta.url)("./rewards-reader-fixtures.cjs");
