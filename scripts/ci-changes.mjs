/**
 * Selects CI test tasks from the files changed between two Git revisions.
 *
 * Usage:
 *   node scripts/ci-changes.mjs <base-revision> <head-revision>
 *   node scripts/ci-changes.mjs --all
 *
 * The output uses GitHub Actions' GITHUB_OUTPUT format:
 *   library=true|false
 *   compile=true|false
 *   connection_targets=[...]
 */
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

export const CONNECTION_TARGETS = [
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

const SHARED_CONNECTION_PREFIXES = [
  "kagiroi-core/",
  "kagiroi-schema/",
  "kagiroi-sql/",
  "kagiroi-kotlinx-datetime/",
  "kagiroi-quwac-bignumber/",
  "test-support/",
];
const GLOBAL_CI_FILES = new Set([
  ".github/workflows/ci.yml",
  "build.gradle.kts",
  "bun.lock",
  "bunfig.toml",
  "gradle.properties",
  "gradlew",
  "gradlew.bat",
  "mise.lock",
  "mise.toml",
  "package.json",
  "scripts/ci-changes.mjs",
  "scripts/test-ci-changes.mjs",
  "scripts/test-connection-target.mjs",
  "scripts/test-database-containers.mjs",
  "scripts/test-database-environment.mjs",
  "settings.gradle.kts",
]);

const CONNECTION_TARGET_GROUPS = [
  {
    prefixes: [
      "kagiroi-sqlite/",
      "kagiroi-sqlite-kotlinx-datetime/",
      "kagiroi-sqlite-quwac-bignumber/",
      "kagiroi-sqlite-file-driver/",
      "kagiroi-sqlite-inmemory-driver/",
      "kagiroi-d1-driver/",
    ],
    targets: ["sqlite-inmemory", "sqlite-file", "d1"],
  },
  {
    prefixes: [
      "kagiroi-postgres/",
      "kagiroi-postgres-kotlinx-datetime/",
      "kagiroi-postgres-quwac-bignumber/",
    ],
    targets: ["postgres-direct", "postgres-hyperdrive", "pglite"],
  },
  {
    prefixes: ["kagiroi-postgres-node-driver/"],
    targets: ["postgres-direct"],
  },
  {
    prefixes: ["integration-tests/postgres-hyperdrive/", "kagiroi-postgres-hyperdrive-driver/"],
    targets: ["postgres-hyperdrive"],
  },
  {
    prefixes: [
      "kagiroi-mariadb/",
      "kagiroi-mysql/",
      "kagiroi-mysql-kotlinx-datetime/",
      "kagiroi-mysql-quwac-bignumber/",
    ],
    targets: ["mysql-direct", "mysql-hyperdrive", "mariadb-direct", "mariadb-hyperdrive"],
  },
  {
    prefixes: ["kagiroi-mysql-node-driver/"],
    targets: ["mysql-direct", "mariadb-direct"],
  },
  {
    prefixes: ["integration-tests/mysql-hyperdrive/", "kagiroi-mysql-hyperdrive-driver/"],
    targets: ["mysql-hyperdrive", "mariadb-hyperdrive"],
  },
  {
    prefixes: ["kagiroi-pglite-driver/"],
    targets: ["pglite"],
  },
  {
    prefixes: [
      "kagiroi-sqlserver/",
      "kagiroi-sqlserver-kotlinx-datetime/",
      "kagiroi-sqlserver-node-driver/",
      "kagiroi-sqlserver-quwac-bignumber/",
    ],
    targets: ["sqlserver-direct"],
  },
  {
    prefixes: [
      "kagiroi-node-oracle-driver/",
      "kagiroi-oracle/",
      "kagiroi-oracle-kotlinx-datetime/",
      "kagiroi-oracle-quwac-bignumber/",
    ],
    targets: ["oracle-direct"],
  },
];

export function ciTaskPlan(changedFiles) {
  const hasGlobalCiChange = changedFiles.some(
    (file) =>
      GLOBAL_CI_FILES.has(file) ||
      file.startsWith("gradle/") ||
      file.startsWith("kotlin-js-store/"),
  );
  const hasKagiroiSourceChange = changedFiles.some((file) => file.startsWith("kagiroi-"));
  const hasCompileFixtureChange = changedFiles.some(
    (file) => file.startsWith("compile-tests/") || file === "scripts/test-compile.mjs",
  );
  const connectionTargets = new Set();

  if (
    hasGlobalCiChange ||
    changedFiles.some((file) =>
      SHARED_CONNECTION_PREFIXES.some((prefix) => file.startsWith(prefix)),
    )
  ) {
    CONNECTION_TARGETS.forEach((target) => connectionTargets.add(target));
  } else {
    for (const { prefixes, targets } of CONNECTION_TARGET_GROUPS) {
      if (changedFiles.some((file) => prefixes.some((prefix) => file.startsWith(prefix)))) {
        targets.forEach((target) => connectionTargets.add(target));
      }
    }
  }

  return {
    library:
      hasGlobalCiChange ||
      hasKagiroiSourceChange ||
      changedFiles.includes("scripts/test-library.mjs"),
    compile: hasGlobalCiChange || hasKagiroiSourceChange || hasCompileFixtureChange,
    connectionTargets: CONNECTION_TARGETS.filter((target) => connectionTargets.has(target)),
  };
}

function changedFilesBetween(baseRevision, headRevision) {
  const result = spawnSync(
    "git",
    ["diff", "--name-only", "--diff-filter=ACDMRTUXB", `${baseRevision}...${headRevision}`],
    { encoding: "utf8" },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr);
  return result.stdout.split("\n").filter(Boolean);
}

function printPlan(plan) {
  console.log(`library=${plan.library}`);
  console.log(`compile=${plan.compile}`);
  console.log(`connection_targets=${JSON.stringify(plan.connectionTargets)}`);
}

function isCliInvocation() {
  return import.meta.url === pathToFileURL(process.argv[1]).href;
}

if (isCliInvocation()) {
  const argv = process.argv.slice(2);
  if (argv.length === 1 && argv[0] === "--all") {
    printPlan({ library: true, compile: true, connectionTargets: CONNECTION_TARGETS });
  } else if (argv.length === 2) {
    printPlan(ciTaskPlan(changedFilesBetween(argv[0], argv[1])));
  } else {
    throw new Error("Specify <base-revision> <head-revision> or --all.");
  }
}
