import { spawn } from "node:child_process";

import { MariaDbContainer } from "@testcontainers/mariadb";
import { MSSQLServerContainer } from "@testcontainers/mssqlserver";
import { MySqlContainer } from "@testcontainers/mysql";
import { OracleDbContainer } from "@testcontainers/oraclefree";
import { PostgreSqlContainer } from "@testcontainers/postgresql";

const DATABASES = ["postgres", "mysql", "mariadb", "sqlserver", "oracle"];
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
const requestedDatabases = selections.length === 0 ? DATABASES : selections;

for (const database of requestedDatabases) {
  if (!DATABASES.includes(database)) {
    throw new Error(`Unknown database '${database}'. Expected one of: ${DATABASES.join(", ")}.`);
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

async function withContainer(name, start, run) {
  console.log(`\n== ${name} ==`);
  const container = await start();

  try {
    await run(container);
  } finally {
    await container.stop({ timeout: 30_000 });
  }
}

async function testPostgres() {
  await withContainer(
    "PostgreSQL",
    () =>
      new PostgreSqlContainer("postgres:17-alpine")
        .withDatabase(TEST_DATABASE)
        .withUsername(TEST_USER)
        .withPassword(TEST_PASSWORD)
        .start(),
    (container) =>
      runGradle(
        [
          ":kagiroi-postgres-node-driver:jsNodeTest",
          ":kagiroi-postgres-node-driver:wasmJsNodeTest",
          ":kagiroi-postgres-hyperdrive-driver:jsNodeTest",
          ":kagiroi-postgres-hyperdrive-driver:wasmJsNodeTest",
        ],
        { KAGIROI_POSTGRES_TEST_URL: container.getConnectionUri() },
      ),
  );
}

async function testMySql() {
  await withContainer(
    "MySQL",
    () =>
      new MySqlContainer("mysql:8.4")
        .withDatabase(TEST_DATABASE)
        .withUsername(TEST_USER)
        .withUserPassword(TEST_PASSWORD)
        .withRootPassword(TEST_PASSWORD)
        .start(),
    (container) =>
      runGradle(
        [
          ":kagiroi-mysql-node-driver:jsNodeTest",
          ":kagiroi-mysql-node-driver:wasmJsNodeTest",
          ":kagiroi-mysql-hyperdrive-driver:jsNodeTest",
          ":kagiroi-mysql-hyperdrive-driver:wasmJsNodeTest",
        ],
        { KAGIROI_MYSQL_TEST_URL: container.getConnectionUri() },
      ),
  );
}

async function testMariaDb() {
  await withContainer(
    "MariaDB",
    () =>
      new MariaDbContainer("mariadb:11.4")
        .withDatabase(TEST_DATABASE)
        .withUsername(TEST_USER)
        .withUserPassword(TEST_PASSWORD)
        .withRootPassword(TEST_PASSWORD)
        .start(),
    (container) =>
      runGradle(
        [
          ":kagiroi-mysql-node-driver:jsNodeTest",
          ":kagiroi-mysql-node-driver:wasmJsNodeTest",
          ":kagiroi-mysql-hyperdrive-driver:jsNodeTest",
          ":kagiroi-mysql-hyperdrive-driver:wasmJsNodeTest",
        ],
        { KAGIROI_MARIADB_TEST_URL: mysqlUrl(container.getConnectionUri()) },
      ),
  );
}

async function testSqlServer() {
  await withContainer(
    "SQL Server",
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

async function testOracle() {
  await withContainer(
    "Oracle Database Free",
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

const tests = {
  postgres: testPostgres,
  mysql: testMySql,
  mariadb: testMariaDb,
  sqlserver: testSqlServer,
  oracle: testOracle,
};

for (const database of requestedDatabases) {
  await tests[database]();
}
