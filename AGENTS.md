# Working on Kagiroi

## Branches and documentation

- Use ASCII branch names beginning with `feature/` or `hotfix/`.
  Rename a nonconforming working branch before making changes.
- Explanatory documents, designs, and specifications describe current behavior
  and guarantees. Do not include implementation history or a chronology of changes.
- When documentation and existing code disagree, follow the code and list every
  discrepancy found in the final response.
- The README addresses library users: supported databases, connection options,
  installation, and current scope. Put contributor tooling and maintenance
  instructions in `CONTRIBUTING.md`, API examples in `docs/CHEATSHEET.md`, and
  physical types and codec limits in `docs/TYPE_MAPPINGS.md`.
- Update the relevant documentation when changing public APIs, module coverage,
  dialect support, or type mappings.
- `AGENTS_ja.md` is a local Japanese translation for the repository owner.
  Keep it out of Git and update it alongside this file when it exists.

## Design constraints

- Prefer typed schema declarations, query expressions, parameterized SQL, and
  typed row access. Keep raw SQL an explicit escape hatch.
- Keep Core independent of SQL, connection management, and host APIs. The SQL
  layer owns execution contracts; drivers own physical sessions and host bindings.
- Keep shared APIs in `commonMain` and host interop in `jsMain` and `wasmJsMain`.
  Preserve behavior on both targets when changing shared APIs or drivers.
- Model queries as typed expressions and render SQL through the dialect.
  Keep values in bind parameters and apply dialect identifier quoting.
- Keep dialect capabilities and SQL semantics explicit. Reject unsupported
  operations; do not silently emulate a dialect feature with different behavior.
  Native conflict handling, `REPLACE`, `MERGE`, and returned write rows belong
  to their dialect APIs.
- Preserve execution-bound APIs such as `database.mergeInto(...).execute()`
  alongside SQL-only query construction. Building a query must not execute it.
- Drivers expose only capabilities they implement. Preserve transaction and
  savepoint boundaries, cancellation-safe cleanup, original failures, and the
  declared streaming or buffering behavior.
- Preserve exact numeric values and explicit nullability across host boundaries.
  Keep optional BigNumber and kotlinx-datetime support in their extension modules.
  Validate codecs and physical type limits against the target database.
- Do not claim that Kotlin types validate raw SQL, external data, database
  constraints, or server-specific behavior at compile time.

## Scope and modules

Library modules target Kotlin `js` and `wasmJs`. JDK runs Gradle, the Kotlin
compiler, and detekt; it is a build requirement, not a JVM library target.
The package namespace and Maven group are `dev.quwac.kagiroi`.

- `kagiroi-core`: database entry point and typed capability lookup.
- `kagiroi-schema`: tables, columns, constraints, indexes, and schema expressions.
- `kagiroi-sql`: query expressions, rendering contracts, typed rows, execution,
  transactions, and DDL.
- `kagiroi-sqlite`, `kagiroi-postgres`, `kagiroi-mysql`, `kagiroi-mariadb`,
  `kagiroi-sqlserver`, and `kagiroi-oracle`: dialect syntax, types, and operations.
- `kagiroi-*-driver`: host-specific database access, including SQLite, D1,
  PGlite, direct Node.js connections, and Cloudflare Hyperdrive.
- `kagiroi-quwac-bignumber`, `kagiroi-kotlinx-datetime`, and their dialect
  extension modules: optional types and codecs.
- `kagiroi-bom`: Gradle platform aligning public library versions without
  adding runtime dependencies. Include new public modules in its constraints
  and the root build's `publishedProjects`.
- `integration-tests/*`: local Hyperdrive Worker contracts.
- `compile-tests`: positive and deliberately invalid Kotlin/Wasm compiler fixtures,
  included only when `-PcompileFixture=<name>` is set.

## Setup and checks

```sh
mise trust
mise install --locked java bun node hk rumdl pkl lychee gh zizmor betterleaks \
    github:github/gh-actions-lock github:jdx/jactionlint
mise run setup
```

Run commands in a mise-activated shell. Otherwise prefix them with `mise exec --`.
Bun installs repository dependencies; Gradle manages Kotlin JS/Wasm dependencies
and test runners. Node.js runs the library and database contract scripts.

| Check | Command |
| --- | --- |
| Local quality checks | `hk check --all` |
| Quality checks including online verification | `hk check --all --profile ci` |
| All local test lanes | `mise run test` |
| Library unit tests without connection-target tests | `mise run test:kotlin` |
| PGlite JS/Wasm bundles on Bun | `mise run test:pglite:bun` |
| Positive and negative compiler fixtures | `mise run test:compile` |
| One connection target | `mise run test:connection-target <target>` |
| All container-backed connection targets | `mise run test:containers` |
| CI change routing | `mise run test:ci-changes` |

`hk fix --all` applies supported fixes. Pre-commit checks staged files without
automatically fixing or staging them. Keep lint and formatting checks in `hk.pkl`.
Do not repeat rules enforced by hk in this file; refer to the check configuration.

Run checks appropriate to the change. Typed API changes require compiler fixtures,
including the expected diagnostics for negative cases. Rendering and codec changes
require the affected dialect's unit tests; driver behavior changes require the
affected connection-target contracts on both JS and Wasm. CI routing changes
require `mise run test:ci-changes`.

Connection target names are listed in `CONTRIBUTING.md` and
`scripts/test-connection-target.mjs`. Container-backed targets require a working
container runtime. Hyperdrive contracts run local Wrangler/Miniflare Workers
against disposable databases; they do not verify remote pooling or query caching.
Use the local test runners to avoid accidental use of external database settings.

Report only checks actually performed. CLI Gradle success does not establish a
successful IDE sync, remote CI result, or deployed service behavior.

## Dependencies and local settings

- Do not disable strict dependency verification or regenerate expected hashes to
  make a normal build pass. Verify artifact provenance before accepting new hashes.
- Regenerate Kotlin JS/Wasm package locks with the owning Gradle tasks; do not
  edit them by hand. Install root Bun dependencies with `bun install --frozen-lockfile`
  during normal setup and checks.
- After changing workflow or action dependencies, regenerate
  `.github/workflows/actions.lock` with `gh-actions-lock --no-narrow`.
  Do not edit that lockfile by hand.
- Use Git-ignored `mise.local.toml` for account-specific local settings and
  synthetic credentials in tests. Do not include real secrets in reports.
