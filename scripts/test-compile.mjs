import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const reports = new URL("../build/reports/compile-tests/", import.meta.url);
await mkdir(reports, { recursive: true });

function parseShardValue(name, fallback) {
  const value = process.env[name];
  if (value === undefined) return fallback;
  assert.match(value, /^\d+$/, `${name} must be a non-negative integer`);
  return Number(value);
}

const cases = [
  ["NativeSecurityPositive", []],
  ["WrongRlsPredicate", ["ARGUMENT_TYPE_MISMATCH", "RETURN_TYPE_MISMATCH"]],
  ["WrongSecurityArguments", ["ARGUMENT_TYPE_MISMATCH"]],
  ["RlsContextOutsideTransaction", ["UNRESOLVED_REFERENCE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER"]],
  ["WrongNullableRlsCast", ["INITIALIZER_TYPE_MISMATCH"]],
  ["CommonDriversPositive", []],
  ["WrongCommonDrivers", ["NONE_APPLICABLE", "ARGUMENT_TYPE_MISMATCH"]],
  ["HyperdrivePositive", []],
  ["WrongHyperdriveConnection", ["NONE_APPLICABLE", "ARGUMENT_TYPE_MISMATCH"]],
  ["KagiroiPositive", []],
  ["IdentityOptionsPositive", []],
  ["KagiroiAlterTablePositive", []],
  ["FunctionExpressionsPositive", []],
  ["CastDistinctCasePositive", []],
  ["PostgresQueryExtensionsPositive", []],
  ["WindowExpressionsPositive", []],
  ["GroupingExpressionsPositive", []],
  ["AdvancedGroupingExpressionsPositive", []],
  ["JoinExpressionsPositive", []],
  ["SubqueriesPositive", []],
  ["LateralRelationsPositive", []],
  ["CommonTableExpressionsPositive", []],
  ["WithDmlPositive", []],
  ["SetOperationsPositive", []],
  ["RecursiveCtesPositive", []],
  ["SavepointScopePositive", []],
  ["ReturningScopePositive", []],
  ["DialectInsertConflictPositive", []],
  ["MergeReplacePositive", []],
  ["WrongBigNumberComparison", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["WrongEnumComparison", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["WrongMappedColumnComparison", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["WrongRowRead", ["INITIALIZER_TYPE_MISMATCH"]],
  ["NoAutoCommit", ["UNRESOLVED_REFERENCE"]],
  ["SavepointOutsideTransaction", ["UNRESOLVED_REFERENCE"]],
  ["NestedTransaction", ["DSL_SCOPE_VIOLATION"]],
  ["EmptySelect", ["NO_VALUE_FOR_PARAMETER"]],
  ["EmptyOrderBy", ["NO_VALUE_FOR_PARAMETER"]],
  ["NullSqlComparison", ["NULL_FOR_NONNULL_TYPE", "NONE_APPLICABLE"]],
  [
    "OutOfOrderSqlClauses",
    ["NONE_APPLICABLE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER", "UNRESOLVED_REFERENCE"],
  ],
  [
    "RepeatedSqlClauses",
    ["NONE_APPLICABLE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER", "UNRESOLVED_REFERENCE"],
  ],
  ["WrongSqlComparison", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  [
    "WrongUpdateFromOrder",
    ["NONE_APPLICABLE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER", "UNRESOLVED_REFERENCE"],
  ],
  ["WrongFunctionExpressions", ["ARGUMENT_TYPE_MISMATCH"]],
  ["WrongIndexDefinitions", ["ARGUMENT_TYPE_MISMATCH"]],
  ["WrongExternalIndexDeclaration", ["UNRESOLVED_REFERENCE"]],
  ["WrongCastDistinctCase", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  [
    "WrongWindowExpressions",
    ["ARGUMENT_TYPE_MISMATCH", "UNRESOLVED_REFERENCE_WRONG_RECEIVER", "UNRESOLVED_REFERENCE"],
  ],
  ["WrongWindowNavigationDefault", ["ARGUMENT_TYPE_MISMATCH"]],
  [
    "WrongGroupingExpressions",
    ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER"],
  ],
  ["WrongAdvancedGrouping", ["INITIALIZER_TYPE_MISMATCH", "NO_VALUE_FOR_PARAMETER"]],
  [
    "WrongSubqueryExpressions",
    ["ARGUMENT_TYPE_MISMATCH", "INITIALIZER_TYPE_MISMATCH", "NONE_APPLICABLE"],
  ],
  ["WrongLateralProjectionLookup", ["ARGUMENT_TYPE_MISMATCH"]],
  ["WrongSetOperations", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["WrongRecursiveCtes", ["RETURN_TYPE_MISMATCH"]],
  [
    "WrongJoinExpressions",
    ["INITIALIZER_TYPE_MISMATCH", "ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"],
  ],
  ["WrongCrossJoin", ["NAMED_PARAMETER_NOT_FOUND"]],
  ["WrongForeignKeyReference", ["ARGUMENT_TYPE_MISMATCH"]],
  [
    "WrongAlterTableDefinitions",
    ["ARGUMENT_TYPE_MISMATCH", "RETURN_TYPE_MISMATCH", "TYPE_MISMATCH"],
  ],
  ["WrongSequenceDefault", ["NONE_APPLICABLE", "ARGUMENT_TYPE_MISMATCH"]],
  ["WrongIterableInsert", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["WrongEmptyDdlSequence", ["ARGUMENT_TYPE_MISMATCH"]],
  ["NonBooleanCheck", ["RETURN_TYPE_MISMATCH", "TYPE_MISMATCH"]],
  ["WrongWriteAssignment", ["ARGUMENT_TYPE_MISMATCH"]],
  ["ComputedWriteAssignment", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["GeneratedWriteAssignment", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["GeneratedInsertSelect", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["WrongDialectInsertConflict", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["WrongMergeAssignment", ["ARGUMENT_TYPE_MISMATCH", "NONE_APPLICABLE"]],
  ["StringIdentity", ["NONE_APPLICABLE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER"]],
  ["UnqualifiedDelete", ["NONE_APPLICABLE", "ARGUMENT_TYPE_MISMATCH"]],
];

const shardCount = parseShardValue("COMPILE_TEST_SHARD_COUNT", 1);
const shardIndex = parseShardValue("COMPILE_TEST_SHARD_INDEX", 0);
assert.ok(shardCount > 0, "COMPILE_TEST_SHARD_COUNT must be greater than zero");
assert.ok(
  shardIndex < shardCount,
  "COMPILE_TEST_SHARD_INDEX must be less than COMPILE_TEST_SHARD_COUNT",
);

const selectedCases = cases.filter((_, index) => index % shardCount === shardIndex);
assert.ok(selectedCases.length > 0, "The selected compile-test shard is empty");

for (const [fixture, diagnostics] of selectedCases) {
  const result = spawnSync(
    "./gradlew",
    [":compile-tests:compileKotlinWasmJs", `-PcompileFixture=${fixture}`, "--console=plain"],
    { cwd: root, encoding: "utf8", timeout: 180_000 },
  );
  if (result.error) throw result.error;
  const output = result.stdout + result.stderr;
  await writeFile(new URL(`${fixture}.log`, reports), output);
  if (diagnostics.length === 0) {
    assert.equal(result.status, 0, `Positive compilation failed:\n${output}`);
  } else {
    assert.notEqual(result.status, 0, `${fixture} unexpectedly compiled`);
    assert.ok(
      output
        .split("\n")
        .some(
          (line) =>
            line.includes(`${fixture}.kt:`) &&
            diagnostics.some((code) => line.includes(`[${code}]`)),
        ),
      `${fixture} failed without the expected type diagnostic:\n${output}`,
    );
  }
  console.log(`Compile ${fixture}: ${diagnostics.length ? "rejected as expected" : "passed"}`);
}
