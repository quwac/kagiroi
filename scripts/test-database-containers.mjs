/**
 * Runs Testcontainers-backed connection-target contract tests.
 *
 * Usage:
 *   node scripts/test-database-containers.mjs [target...]
 *
 * Arguments:
 *   target  Optional. One or more of: postgres-direct, postgres-hyperdrive,
 *           mysql-direct, mysql-hyperdrive, mariadb-direct,
 *           mariadb-hyperdrive, sqlserver-direct, oracle-direct.
 *           Omit all targets to run every target above.
 *
 * Examples:
 *   mise run test:containers
 *   mise run test:containers postgres-direct postgres-hyperdrive
 */
import { spawn } from "node:child_process";

import { MariaDbContainer } from "@testcontainers/mariadb";
import { MSSQLServerContainer } from "@testcontainers/mssqlserver";
import { MySqlContainer } from "@testcontainers/mysql";
import { OracleDbContainer } from "@testcontainers/oraclefree";
import { PostgreSqlContainer } from "@testcontainers/postgresql";

const CONNECTION_TARGETS = [
  "postgres-direct",
  "postgres-hyperdrive",
  "mysql-direct",
  "mysql-hyperdrive",
  "mariadb-direct",
  "mariadb-hyperdrive",
  "sqlserver-direct",
  "oracle-direct",
];
const TEST_DATABASE = "kagiroi";
const TEST_USER = "kagiroi";
const TEST_PASSWORD = ["Kagiroi", "!", "Passw0rd"].join("");
const ORACLE_DATABASE = "FREEPDB1";
const SQLSERVER_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
const EXTERNAL_DATABASE_ENVIRONMENT_NAMES = [
  "KAGIROI_AURORA_MYSQL_TEST_URL",
  "KAGIROI_AURORA_POSTGRES_TEST_URL",
  "KAGIROI_MARIADB_TEST_URL",
  "KAGIROI_MYSQL_TEST_URL",
  "KAGIROI_NEON_POSTGRES_TEST_URL",
  "KAGIROI_ORACLE_TEST_CONNECT_STRING",
  "KAGIROI_ORACLE_TEST_PASSWORD",
  "KAGIROI_ORACLE_TEST_USER",
  "KAGIROI_PLANETSCALE_MYSQL_TEST_URL",
  "KAGIROI_POSTGRES_TEST_URL",
  "KAGIROI_SQLSERVER_TEST_DATABASE",
  "KAGIROI_SQLSERVER_TEST_HOST",
  "KAGIROI_SQLSERVER_TEST_PASSWORD",
  "KAGIROI_SQLSERVER_TEST_PORT",
  "KAGIROI_SQLSERVER_TEST_USER",
  "KAGIROI_SUPABASE_POSTGRES_TEST_URL",
];

const selections = process.argv.slice(2);
const requestedTargets = selections.length === 0 ? CONNECTION_TARGETS : selections;

for (const target of requestedTargets) {
  if (!CONNECTION_TARGETS.includes(target)) {
    throw new Error(
      `Unknown connection target '${target}'. Expected one of: ${CONNECTION_TARGETS.join(", ")}.`,
    );
  }
}

async function runGradle(tasks, environment) {
  for (const task of tasks) {
    await runGradleTask(task, environment);
  }
}

async function runGradleTask(task, environment) {
  const command = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
  const childEnvironment = { ...process.env };

  for (const name of EXTERNAL_DATABASE_ENVIRONMENT_NAMES) {
    delete childEnvironment[name];
  }

  Object.assign(childEnvironment, environment);

  await new Promise((resolve, reject) => {
    const child = spawn(command, [task, "--rerun-tasks", "--console=plain"], {
      cwd: process.cwd(),
      env: childEnvironment,
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
          `Gradle stopped with ${signal == null ? `exit code ${code}` : `signal ${signal}`}.`,
        ),
      );
    });
  });
}

async function runNodeScript(script, arguments_, environment) {
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [script, ...arguments_], {
      cwd: process.cwd(),
      env: { ...process.env, ...environment },
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
          `${script} stopped with ${signal == null ? `exit code ${code}` : `signal ${signal}`}.`,
        ),
      );
    });
  });
}

async function withContainer(name, start, run) {
  console.log(`\n== ${name} ==`);
  const container = await start();

  try {
    await run(container);
  } finally {
    await container.stop({ timeout: 30_000 });
  }
}

function startPostgresContainer() {
  return new PostgreSqlContainer("postgres:17-alpine")
    .withDatabase(TEST_DATABASE)
    .withUsername(TEST_USER)
    .withPassword(TEST_PASSWORD)
    .start();
}

async function testPostgresDirect() {
  await withContainer("PostgreSQL, direct", startPostgresContainer, (container) =>
    runGradle(
      [":kagiroi-postgres-node-driver:jsNodeTest", ":kagiroi-postgres-node-driver:wasmJsNodeTest"],
      { KAGIROI_POSTGRES_TEST_URL: container.getConnectionUri() },
    ),
  );
}

async function testPostgresHyperdrive() {
  await withContainer("PostgreSQL through local Hyperdrive", startPostgresContainer, (container) =>
    runPostgresHyperdriveWorkerContracts(container.getConnectionUri()),
  );
}

function startMySqlContainer() {
  return new MySqlContainer("mysql:8.4")
    .withDatabase(TEST_DATABASE)
    .withUsername(TEST_USER)
    .withUserPassword(TEST_PASSWORD)
    .withRootPassword(TEST_PASSWORD)
    .start();
}

async function testMySqlDirect() {
  await withContainer("MySQL, direct", startMySqlContainer, (container) =>
    runGradle(
      [":kagiroi-mysql-node-driver:jsNodeTest", ":kagiroi-mysql-node-driver:wasmJsNodeTest"],
      { KAGIROI_MYSQL_TEST_URL: container.getConnectionUri() },
    ),
  );
}

async function testMySqlHyperdrive() {
  await withContainer("MySQL through local Hyperdrive", startMySqlContainer, (container) =>
    runMySqlHyperdriveWorkerContracts(container.getConnectionUri(), "mysql"),
  );
}

function startMariaDbContainer() {
  return new MariaDbContainer("mariadb:11.4")
    .withDatabase(TEST_DATABASE)
    .withUsername(TEST_USER)
    .withUserPassword(TEST_PASSWORD)
    .withRootPassword(TEST_PASSWORD)
    .start();
}

async function testMariaDbDirect() {
  await withContainer("MariaDB, direct", startMariaDbContainer, (container) =>
    runGradle(
      [":kagiroi-mysql-node-driver:jsNodeTest", ":kagiroi-mysql-node-driver:wasmJsNodeTest"],
      { KAGIROI_MARIADB_TEST_URL: mysqlUrl(container.getConnectionUri()) },
    ),
  );
}

async function testMariaDbHyperdrive() {
  await withContainer("MariaDB through local Hyperdrive", startMariaDbContainer, (container) =>
    runMySqlHyperdriveWorkerContracts(mysqlUrl(container.getConnectionUri()), "mariadb"),
  );
}

async function testSqlServerDirect() {
  await withContainer(
    "SQL Server, direct",
    () =>
      new MSSQLServerContainer(SQLSERVER_IMAGE)
        .acceptLicense()
        .withPassword(TEST_PASSWORD)
        .withPlatform("linux/amd64")
        .withStartupTimeout(5 * 60 * 1000)
        .start(),
    (container) =>
      runGradle(
        [
          ":kagiroi-sqlserver-node-driver:jsNodeTest",
          ":kagiroi-sqlserver-node-driver:wasmJsNodeTest",
        ],
        {
          KAGIROI_SQLSERVER_TEST_DATABASE: container.getDatabase(),
          KAGIROI_SQLSERVER_TEST_HOST: container.getHost(),
          KAGIROI_SQLSERVER_TEST_PASSWORD: container.getPassword(),
          KAGIROI_SQLSERVER_TEST_PORT: String(container.getPort()),
          KAGIROI_SQLSERVER_TEST_USER: container.getUsername(),
        },
      ),
  );
}

async function testOracleDirect() {
  await withContainer(
    "Oracle Database Free, direct",
    () =>
      new OracleDbContainer("gvenzl/oracle-free:23-slim")
        .withDatabase(ORACLE_DATABASE)
        .withUsername(TEST_USER)
        .withPassword(TEST_PASSWORD)
        .withStartupTimeout(5 * 60 * 1000)
        .start(),
    (container) =>
      runGradle(
        [":kagiroi-node-oracle-driver:jsNodeTest", ":kagiroi-node-oracle-driver:wasmJsNodeTest"],
        {
          KAGIROI_ORACLE_TEST_CONNECT_STRING: container.getUrl(),
          KAGIROI_ORACLE_TEST_PASSWORD: container.getPassword(),
          KAGIROI_ORACLE_TEST_USER: container.getUsername(),
        },
      ),
  );
}

function mysqlUrl(mariaDbUrl) {
  const url = new URL(mariaDbUrl);
  url.protocol = "mysql:";
  return url.toString();
}

async function runPostgresHyperdriveWorkerContracts(connectionString) {
  const environment = localHyperdriveEnvironment(connectionString);
  const fixture = "integration-tests/postgres-hyperdrive";
  await runGradle(
    [
      ":integration-tests:postgres-hyperdrive:jsProductionExecutableCompileSync",
      ":integration-tests:postgres-hyperdrive:wasmJsProductionExecutableCompileSync",
    ],
    environment,
  );
  for (const target of ["js", "wasm"]) {
    await runNodeScript(`${fixture}/scripts/prepare-worker.mjs`, [target], environment);
    await runNodeScript(`${fixture}/scripts/test-worker.mjs`, [target], environment);
  }
}

async function runMySqlHyperdriveWorkerContracts(connectionString, database) {
  const environment = localHyperdriveEnvironment(connectionString);
  const fixture = "integration-tests/mysql-hyperdrive";
  await runGradle(
    [
      ":integration-tests:mysql-hyperdrive:jsProductionExecutableCompileSync",
      ":integration-tests:mysql-hyperdrive:wasmJsProductionExecutableCompileSync",
    ],
    environment,
  );
  for (const target of ["js", "wasm"]) {
    await runNodeScript(`${fixture}/scripts/prepare-worker.mjs`, [target, database], environment);
    await runNodeScript(
      `${fixture}/scripts/test-worker.mjs`,
      [target, database === "mariadb" ? "mariadb" : "contract"],
      environment,
    );
  }
}

function localHyperdriveEnvironment(connectionString) {
  return { CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE: connectionString };
}

const tests = {
  "postgres-direct": testPostgresDirect,
  "postgres-hyperdrive": testPostgresHyperdrive,
  "mysql-direct": testMySqlDirect,
  "mysql-hyperdrive": testMySqlHyperdrive,
  "mariadb-direct": testMariaDbDirect,
  "mariadb-hyperdrive": testMariaDbHyperdrive,
  "sqlserver-direct": testSqlServerDirect,
  "oracle-direct": testOracleDirect,
};

for (const target of requestedTargets) {
  await tests[target]();
}
