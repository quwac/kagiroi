# Contributing to Kagiroi

Kagiroi is a Kotlin Multiplatform database toolkit for Kotlin/JS and Kotlin/Wasm.

## Setup

```sh
mise trust
mise install --locked java bun node hk pkl lychee gh zizmor betterleaks \
    github:github/gh-actions-lock github:jdx/jactionlint
mise run setup
```

`mise run setup` installs locked JavaScript dependencies and registers the `hk`
Git hooks.

## Checks

Run commands from a mise-activated shell. Otherwise prefix a command with
`mise exec --`.

| Change | Command |
| --- | --- |
| Formatting, Markdown, links, Actions, and static checks | `hk check --all` |
| The CI-only online checks | `hk check --all --profile ci` |
| All local test lanes | `mise run test` |
| Library unit tests, without a connection target | `mise run test:kotlin` |
| PGlite contracts on Bun | `mise run test:pglite:bun` |
| Positive and negative compiler fixtures | `mise run test:compile` |
| One README connection target contract | `mise run test:connection-target <target>` |
| Testcontainers-backed connection-target contracts | `mise run test:containers` |

`mise run test` runs library tests, every README connection target, and compiler fixtures.
It removes external database connection variables before running tests.
`mise run test:containers` starts disposable database containers through Testcontainers
and requires a working container runtime.

Run one connection target with `mise run test:connection-target <target>`. The
target names mirror the README: `sqlite-inmemory`, `sqlite-file`, `d1`,
`postgres-direct`, `postgres-hyperdrive`, `pglite`, `mysql-direct`,
`mysql-hyperdrive`, `mariadb-direct`, `mariadb-hyperdrive`, `sqlserver-direct`,
and `oracle-direct`. `test:containers` runs its eight container-backed targets.

`hk fix --all` applies supported fixes. The pre-commit hook checks staged files
without fixing or staging them.

## Local Hyperdrive Worker contracts

The `postgres-hyperdrive`, `mysql-hyperdrive`, and `mariadb-hyperdrive` targets
start generated JS and Wasm Workers with Wrangler's local Miniflare runtime. The runner supplies the
Testcontainers connection string through
`CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE`; no Cloudflare account,
Hyperdrive ID, or remote database is used. This does not test Hyperdrive pooling or
query caching.

## Dependencies and GitHub Actions

Keep versions, lockfiles, and SHA-256 verification metadata consistent. Normal
Gradle builds must verify dependencies without regenerating expected hashes.

After changing workflow or action dependencies, regenerate
`.github/workflows/actions.lock` with `gh-actions-lock --no-narrow`, then run
`gh-actions-lock --verify`. Do not edit the lockfile by hand. Workflow `uses`
references are pinned to full commit SHAs with version comments.

The dependency-update cooldown is three days. It applies to mise range
resolution, Bun resolutions, and Dependabot updates for Actions, Gradle, and
Bun.
