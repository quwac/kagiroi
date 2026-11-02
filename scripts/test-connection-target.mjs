/**
 * Runs the contract tests for exactly one README connection target.
 *
 * Usage:
 *   node scripts/test-connection-target.mjs <target>
 *
 * Arguments:
 *   target  Required. One of: sqlite-inmemory, sqlite-file, d1,
 *           postgres-direct, postgres-hyperdrive, pglite, mysql-direct,
 *           mysql-hyperdrive, mariadb-direct, mariadb-hyperdrive,
 *           sqlserver-direct, oracle-direct.
 *
 * Examples:
 *   mise run test:connection-target postgres-direct
 *   mise run test:connection-target postgres-hyperdrive
 */
import { spawn } from "node:child_process";

const CONNECTION_TARGETS = [
  "sqlite-inmemory",
  "sqlite-file",
  "d1",
  "postgres-direct",
  "postgres-hyperdrive",
  "pglite",
  "mysql-direct",
  "mysql-hyperdrive",
  "mariadb-direct",
  "mariadb-hyperdrive",
  "sqlserver-direct",
  "oracle-direct",
];
const CONTAINER_TARGETS = new Set([
  "postgres-direct",
  "postgres-hyperdrive",
  "mysql-direct",
  "mysql-hyperdrive",
  "mariadb-direct",
  "mariadb-hyperdrive",
  "sqlserver-direct",
  "oracle-direct",
]);
const GRADLE_TARGETS = {
  "sqlite-inmemory": [
    ":kagiroi-sqlite-inmemory-driver:jsNodeTest",
    ":kagiroi-sqlite-inmemory-driver:wasmJsNodeTest",
  ],
  "sqlite-file": [
    ":kagiroi-sqlite-file-driver:jsNodeTest",
    ":kagiroi-sqlite-file-driver:wasmJsNodeTest",
  ],
  d1: [":kagiroi-d1-driver:jsNodeTest", ":kagiroi-d1-driver:wasmJsNodeTest"],
};

const [target] = process.argv.slice(2);

if (target == null) {
  throw new Error(`Specify one connection target: ${CONNECTION_TARGETS.join(", ")}.`);
}

if (!CONNECTION_TARGETS.includes(target)) {
  throw new Error(
    `Unknown connection target '${target}'. Expected one of: ${CONNECTION_TARGETS.join(", ")}.`,
  );
}

if (CONTAINER_TARGETS.has(target)) {
  await run("node", ["scripts/test-database-containers.mjs", target]);
} else if (target === "pglite") {
  await run("mise", ["run", "test:pglite:bun"]);
} else {
  await run(gradleCommand(), [...GRADLE_TARGETS[target], "--rerun-tasks", "--console=plain"]);
}

function gradleCommand() {
  return process.platform === "win32" ? "gradlew.bat" : "./gradlew";
}

async function run(command, arguments_) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, {
      cwd: process.cwd(),
      env: process.env,
      stdio: "inherit",
    });

    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(
        new Error(
          `${command} stopped with ${signal == null ? `exit code ${code}` : `signal ${signal}`}.`,
        ),
      );
    });
  });
}
