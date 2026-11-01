# Contributing to Kagiroi

Kagiroi is a Kotlin Multiplatform database toolkit. Its public APIs preserve
database-specific semantics through explicit types and dialect-owned rendering.

## Setup

```sh
mise trust
mise install --locked java bun node hk pkl lychee gh zizmor betterleaks \\
    github:github/gh-actions-lock github:jdx/jactionlint
mise run setup
```

`mise run deps` installs the locked JavaScript dependencies used by the quality
checks and database contracts.

## Checks

Run quality checks in a mise-activated shell. Without activation, prefix a
command with `mise exec --`.

```sh
hk check --all
hk check --all --profile ci
mise run test:kotlin
mise run test:pglite:bun
mise run test:compile
```

`mise run test:containers` runs disposable PostgreSQL, MySQL, MariaDB, SQL
Server, and Oracle compatibility contracts through Testcontainers. It requires a
working container runtime.

Remote Hyperdrive contracts are opt-in because they require configured Cloudflare
bindings:

```sh
mise run test:hyperdrive:remote
mise run test:hyperdrive:mysql:remote
mise run test:hyperdrive:mariadb:remote
```

`hk fix --all` applies supported formatting fixes. Pre-commit checks staged code
without automatically fixing or staging it.

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
