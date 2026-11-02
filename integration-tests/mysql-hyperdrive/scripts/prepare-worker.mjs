/**
 * Prepares the local Wrangler Worker fixture from compiled MySQL or MariaDB
 * Hyperdrive contract tests.
 *
 * Usage:
 *   node integration-tests/mysql-hyperdrive/scripts/prepare-worker.mjs <target> [database]
 *
 * Arguments:
 *   target    Required. Kotlin compilation target: js or wasm.
 *   database  Optional. mysql (default) or mariadb.
 *
 * Prerequisite:
 *   Run the matching compileSync task before this script.
 *
 * Examples:
 *   node integration-tests/mysql-hyperdrive/scripts/prepare-worker.mjs js
 *   node integration-tests/mysql-hyperdrive/scripts/prepare-worker.mjs wasm mariadb
 */
import { copyFile, cp, mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const targetName = process.argv[2];
if (targetName !== "js" && targetName !== "wasm") {
  throw new Error("Expected target 'js' or 'wasm'");
}

const database = process.argv[3] ?? "mysql";
if (database !== "mysql" && database !== "mariadb") {
  throw new Error("Expected database 'mysql' or 'mariadb'");
}
const LOCAL_HYPERDRIVE_ID = "00000000-0000-0000-0000-000000000000";

const fixture = fileURLToPath(new URL("../", import.meta.url));
const repository = path.resolve(fixture, "../..");
const base = "kagiroi-integration-tests-mysql-hyperdrive";
const source =
  targetName === "js"
    ? path.join(fixture, "build/compileSync/js/main/productionExecutable/kotlin")
    : path.join(fixture, "build/compileSync/wasmJs/main/productionExecutable/optimized");
const target = path.join(fixture, `build/worker-${targetName}`);

await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });
await copyFile(path.join(fixture, "worker.mjs"), path.join(target, "worker.mjs"));

if (targetName === "js") {
  await prepareJs(source, target, base);
} else {
  await prepareWasm(source, target, base);
}

await writeFile(
  path.join(target, "wrangler.jsonc"),
  `${JSON.stringify(
    {
      name: `kagiroi-${database}-hyperdrive-contract-${targetName}`,
      main: "worker.mjs",
      compatibility_date: "2026-10-19",
      compatibility_flags: ["nodejs_compat"],
      alias: {
        "mysql2/promise": path.relative(
          target,
          path.join(
            repository,
            "build",
            targetName === "js" ? "js" : "wasm",
            "node_modules/mysql2/promise.js",
          ),
        ),
        "@js-joda/core": path.relative(
          target,
          path.join(
            repository,
            "build",
            targetName === "js" ? "js" : "wasm",
            "node_modules/@js-joda/core/dist/js-joda.esm.js",
          ),
        ),
        "@js-joda/timezone": path.relative(
          target,
          path.join(
            repository,
            "build",
            targetName === "js" ? "js" : "wasm",
            "node_modules/@js-joda/timezone/dist/js-joda-timezone.esm.js",
          ),
        ),
      },
      hyperdrive: [{ binding: "HYPERDRIVE", id: LOCAL_HYPERDRIVE_ID }],
    },
    null,
    2,
  )}\n`,
);

console.log(`Prepared local Hyperdrive ${database} contract Worker for ${targetName}`);

async function prepareJs(sourceDirectory, targetDirectory, moduleBase) {
  const kotlinDirectory = path.join(targetDirectory, "kotlin");
  await mkdir(kotlinDirectory, { recursive: true });
  for (const filename of await readdir(sourceDirectory)) {
    if (filename.endsWith(".mjs")) {
      await cp(path.join(sourceDirectory, filename), path.join(kotlinDirectory, filename));
    }
  }
  await writeFile(
    path.join(targetDirectory, "bridge.mjs"),
    `export { runHyperdriveMariaDbContract, runHyperdriveMariaDbSmoke, runHyperdriveMySqlContract, runHyperdriveMySqlSmoke } from "./kotlin/${moduleBase}.mjs";\n`,
  );
}

async function prepareWasm(sourceDirectory, targetDirectory, moduleBase) {
  await copyFile(
    path.join(sourceDirectory, `${moduleBase}.wasm`),
    path.join(targetDirectory, `${moduleBase}.wasm`),
  );
  await copyFile(
    path.join(sourceDirectory, `${moduleBase}.import-object.mjs`),
    path.join(targetDirectory, `${moduleBase}.import-object.mjs`),
  );

  const builtins = await readFile(
    path.join(sourceDirectory, `${moduleBase}.js-builtins.mjs`),
    "utf8",
  );
  const helper =
    /const (moduleFromCharCodeArray|moduleIntoCharCode) = new WebAssembly\.Module\(new Uint8Array\(\[([\s\S]*?)\]\)\);/gu;
  const matches = [...builtins.matchAll(helper)];
  if (
    matches.length < 1 ||
    matches.length > 2 ||
    new Set(matches.map((match) => match[1])).size !== matches.length
  ) {
    throw new Error("Kotlin JS string glue changed; review the Hyperdrive MySQL test bridge");
  }
  for (const [, name, encoded] of matches) {
    const bytes = encoded
      .split(",")
      .map((part) => part.trim())
      .filter(Boolean)
      .map(Number);
    if (bytes.some((byte) => !Number.isInteger(byte) || byte < 0 || byte > 255)) {
      throw new Error("Invalid Kotlin JS string helper bytes");
    }
    await writeFile(path.join(targetDirectory, `${name}.wasm`), new Uint8Array(bytes));
  }
  await writeFile(
    path.join(targetDirectory, `${moduleBase}.js-builtins.mjs`),
    builtins.replace(helper, (_, name) => `import ${name} from "./${name}.wasm";`),
  );
  await writeFile(
    path.join(targetDirectory, "bridge.mjs"),
    `
import wasm from "./${moduleBase}.wasm";
import { importObject, setWasmExports } from "./${moduleBase}.import-object.mjs";
const instance = new WebAssembly.Instance(wasm, importObject);
setWasmExports({ memory: importObject.intrinsics.memory });
const { runHyperdriveMariaDbContract, runHyperdriveMariaDbSmoke, runHyperdriveMySqlContract, runHyperdriveMySqlSmoke, _start } = instance.exports;
_start();
export { runHyperdriveMariaDbContract, runHyperdriveMariaDbSmoke, runHyperdriveMySqlContract, runHyperdriveMySqlSmoke };
`,
  );
}
