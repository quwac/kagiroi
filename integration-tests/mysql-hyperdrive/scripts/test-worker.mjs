/**
 * Starts the generated MySQL or MariaDB Hyperdrive Worker in local Miniflare
 * and invokes its contract or smoke endpoint.
 *
 * Usage:
 *   node integration-tests/mysql-hyperdrive/scripts/test-worker.mjs <target> [mode]
 *
 * Arguments:
 *   target  Required. Generated Worker target: js or wasm.
 *   mode    Optional. contract (default), smoke, mariadb, or mariadb-smoke.
 *
 * Required environment:
 *   CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE
 *       Testcontainers MySQL or MariaDB connection string for the local binding.
 *
 * Examples:
 *   node integration-tests/mysql-hyperdrive/scripts/test-worker.mjs js
 *   node integration-tests/mysql-hyperdrive/scripts/test-worker.mjs wasm mariadb
 */
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const targetName = process.argv[2];
if (targetName !== "js" && targetName !== "wasm") {
  throw new Error("Expected target 'js' or 'wasm'");
}
const mode = process.argv[3] ?? "contract";
if (!["contract", "smoke", "mariadb", "mariadb-smoke"].includes(mode)) {
  throw new Error("Expected mode 'contract', 'smoke', 'mariadb', or 'mariadb-smoke'");
}
const database = mode.startsWith("mariadb") ? "MariaDB" : "MySQL";
const testKind = mode.endsWith("smoke") ? "smoke" : "contract";
const localConnectionString = process.env.CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE;
if (!localConnectionString) {
  throw new Error("CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE must be set");
}

const fixture = fileURLToPath(new URL("../", import.meta.url));
const repository = path.resolve(fixture, "../..");
const port = targetName === "js" ? 8793 : 8794;
const wrangler = path.join(repository, "node_modules/wrangler/bin/wrangler.js");
const configuration = path.join(fixture, `build/worker-${targetName}/wrangler.jsonc`);
const output = [];
const processHandle = spawn(
  process.execPath,
  [wrangler, "dev", "--config", configuration, "--ip", "127.0.0.1", "--port", String(port)],
  {
    cwd: repository,
    env: {
      ...process.env,
      CI: "true",
      CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE: localConnectionString,
    },
    stdio: ["ignore", "pipe", "pipe"],
  },
);

processHandle.stdout.on("data", capture);
processHandle.stderr.on("data", capture);

try {
  await waitForReady();
  await new Promise((resolve) => setTimeout(resolve, 1_000));
  const endpoint = `http://127.0.0.1:${port}/${mode}`;
  const health = await fetch(endpoint, {
    signal: AbortSignal.timeout(30_000),
  });
  if (health.status !== 405) {
    throw new Error(`Local Hyperdrive MySQL fixture health check returned ${health.status}`);
  }
  const response = await fetch(endpoint, {
    method: "POST",
    signal: AbortSignal.timeout(120_000),
  });
  const body = await response.text();
  if (!response.ok || body !== '{"ok":true}') {
    throw new Error(`Local Hyperdrive MySQL contract failed with ${response.status}: ${body}`);
  }
  console.log(`Local Hyperdrive ${database} ${testKind} passed for ${targetName}`);
} finally {
  processHandle.kill("SIGTERM");
  await Promise.race([
    new Promise((resolve) => processHandle.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 5_000)),
  ]);
  if (processHandle.exitCode === null) processHandle.kill("SIGKILL");
}

function capture(chunk) {
  const text = chunk.toString();
  process.stderr.write(text);
  output.push(text);
  if (output.join("").length > 40_000) output.shift();
}

async function waitForReady() {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    const text = output.join("");
    if (/Ready on http:\/\/127\.0\.0\.1:/u.test(text)) return;
    if (processHandle.exitCode !== null) {
      throw new Error(`Wrangler exited before readiness:\n${text}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Timed out waiting for local Wrangler:\n${output.join("")}`);
}
