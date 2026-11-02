import assert from "node:assert/strict";

import { CONNECTION_TARGETS, ciTaskPlan } from "./ci-changes.mjs";

assert.deepEqual(ciTaskPlan(["README.md"]), {
  library: false,
  compile: false,
  connectionTargets: [],
});

assert.deepEqual(ciTaskPlan(["kagiroi-core/src/commonMain/kotlin/Database.kt"]), {
  library: true,
  compile: true,
  connectionTargets: CONNECTION_TARGETS,
});

assert.deepEqual(ciTaskPlan(["kagiroi-postgres-node-driver/src/jsMain/kotlin/PostgresJs.kt"]), {
  library: true,
  compile: true,
  connectionTargets: ["postgres-direct"],
});

assert.deepEqual(
  ciTaskPlan(["kagiroi-mysql-hyperdrive-driver/src/jsMain/kotlin/MySqlHyperdriveJs.kt"]),
  {
    library: true,
    compile: true,
    connectionTargets: ["mysql-hyperdrive", "mariadb-hyperdrive"],
  },
);

assert.deepEqual(ciTaskPlan(["scripts/test-database-containers.mjs"]), {
  library: true,
  compile: true,
  connectionTargets: CONNECTION_TARGETS,
});

console.log("CI change routing passed");
