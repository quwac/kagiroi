<p align="center">
  <img src="docs/assets/kagiroi-logo.svg" alt="Kagiroi logo" width="420">
</p>

<h1 align="center">Kagiroi</h1>

<p align="center">
  <a href="https://github.com/quwac/kagiroi/actions/workflows/ci.yml">
    <img src="https://img.shields.io/badge/CI-Verify-2088FF?logo=githubactions&amp;logoColor=white" alt="CI: Verify">
  </a>
  <a href="https://kotlinlang.org/">
    <img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin 2.4.21">
  </a>
  <a href="#scope">
    <img src="https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin Multiplatform">
  </a>
  <a href="https://gradle.org/">
    <img src="https://img.shields.io/badge/Gradle-9.8.2-02303A?logo=gradle&amp;logoColor=white" alt="Gradle 9.8.2">
  </a>
  <a href="https://openjdk.org/">
    <img src="https://img.shields.io/badge/JDK-17-007396?logo=openjdk&amp;logoColor=white" alt="JDK 17">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT License">
  </a>
</p>

Kagiroi is a Kotlin Multiplatform database toolkit for Kotlin/JS and Kotlin/Wasm.
It provides typed schema definitions, parameterized SQL, typed row access, and
dialect-specific SQL rendering.

Kagiroi is experimental. Check the target dialect's type mappings and supported
operations before using it in an application.

## Start here

1. Add the BOM and the dialect/driver modules for the database you use.
2. Define tables with `kagiroi-schema`.
3. Follow the [Kagiroi cheat sheet](docs/CHEATSHEET.md) for typed
   queries, DML, DDL, transactions, and dialect boundaries.
4. Confirm physical types and codecs in [Kagiroi type mappings](docs/TYPE_MAPPINGS.md).

## Install

Use `kagiroi-bom` as a Gradle platform to align every Kagiroi artifact.
Then add only the modules your application needs.

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-sqlite-file-driver")
}
```

`kagiroi-bom` contributes version constraints only; it does not add a runtime
library to the application classpath.

## Modules

| Need | Modules |
| --- | --- |
| Core database contracts | `kagiroi-core` |
| Table definitions and explicit application schema | `kagiroi-schema` |
| Typed queries, writes, and DDL | `kagiroi-sql` |
| Optional `kotlinx-datetime` columns | `kagiroi-kotlinx-datetime` and a dialect extension |
| Optional BigNumber columns | `kagiroi-quwac-bignumber` and a dialect extension |
| Version alignment | `kagiroi-bom` |

`kagiroi-schema` contains tables, columns, expressions, and `schema(...)`.
`kagiroi-sql` contains statements, parameter rendering, and driver-facing execution
contracts. Dialects contain SQL syntax and physical types; drivers contain host and
connection integration.

## Connection targets and execution paths

| Connection target / execution path | Dialect module | Driver module | Test target |
| --- | --- | --- | --- |
| SQLite, in memory | `kagiroi-sqlite` | `kagiroi-sqlite-inmemory-driver` | `sqlite-inmemory` |
| SQLite, file-backed | `kagiroi-sqlite` | `kagiroi-sqlite-file-driver` | `sqlite-file` |
| Cloudflare D1 | `kagiroi-sqlite` | `kagiroi-d1-driver` | `d1` |
| PostgreSQL, direct | `kagiroi-postgres` | `kagiroi-postgres-node-driver` | `postgres-direct` |
| PostgreSQL through Cloudflare Hyperdrive | `kagiroi-postgres` | `kagiroi-postgres-hyperdrive-driver` | `postgres-hyperdrive` |
| PGlite | PostgreSQL dialect | `kagiroi-pglite-driver` | `pglite` |
| MySQL, direct | `kagiroi-mysql` | `kagiroi-mysql-node-driver` | `mysql-direct` |
| MySQL through Cloudflare Hyperdrive | `kagiroi-mysql` | `kagiroi-mysql-hyperdrive-driver` | `mysql-hyperdrive` |
| MariaDB, direct | `kagiroi-mariadb` | `kagiroi-mysql-node-driver` | `mariadb-direct` |
| MariaDB through Cloudflare Hyperdrive | `kagiroi-mariadb` | `kagiroi-mysql-hyperdrive-driver` | `mariadb-hyperdrive` |
| SQL Server, direct | `kagiroi-sqlserver` | `kagiroi-sqlserver-node-driver` | `sqlserver-direct` |
| Oracle Database 23ai+, direct | `kagiroi-oracle` | `kagiroi-node-oracle-driver` | `oracle-direct` |

They do not provide Kotlin/Native, standalone Wasm, or WASI database clients.

## Scope

Values are bound as parameters; schema identifiers are application declarations.
Dialect-specific operations such as conflict handling, `RETURNING`/`OUTPUT`, and
advanced DDL stay visible in the API. Unsupported typed operations fail rather
than being rewritten.

Typed DDL runs only when explicitly executed. Kagiroi does not introspect or
synchronize databases, or apply migration history. Use parameterized raw SQL for
a complete vendor-specific statement outside the typed API.

## Documentation

- [Kagiroi cheat sheet](docs/CHEATSHEET.md): table declarations,
  queries, DML, DDL, transactions, and dialect differences
- [Kagiroi type mappings](docs/TYPE_MAPPINGS.md): physical types, codecs, precision,
  JSON boundaries, and intentional exclusions
- [Contributing](CONTRIBUTING.md): local setup, checks, and dependency updates

## License

[MIT](LICENSE)
