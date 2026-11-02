/**
 * Runs Kotlin library unit tests without connection-target or external database
 * integration tests.
 *
 * Usage:
 *   node scripts/test-library.mjs
 *
 * Environment:
 *   KAGIROI_*_TEST_* variables are removed before Gradle starts.
 */
import { spawn } from "node:child_process";

import { localDatabaseTestEnvironment } from "./test-database-environment.mjs";

const command = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const child = spawn(command, ["libraryTests"], {
  cwd: process.cwd(),
  env: localDatabaseTestEnvironment(),
  stdio: "inherit",
});

await new Promise((resolve, reject) => {
  child.once("error", reject);
  child.once("exit", (code, signal) => {
    if (code === 0) {
      resolve();
      return;
    }
    reject(
      new Error(
        `Gradle stopped with ${signal == null ? `exit code ${code}` : `signal ${signal}`}.`,
      ),
    );
  });
});
