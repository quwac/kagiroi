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
  <a href="https://kotlinlang.org/docs/js-overview.html">
    <img src="https://img.shields.io/badge/Kotlin-JS-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin/JS">
  </a>
  <a href="https://kotlinlang.org/docs/wasm-overview.html">
    <img src="https://img.shields.io/badge/Kotlin-Wasm-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin/Wasm">
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

Kagiroi (pronounced "kah-gee-roh-ee") is a Kotlin Multiplatform database toolkit
for Kotlin/JS and Kotlin/Wasm.
It provides typed schema definitions, parameterized SQL, typed row access, and
dialect-specific SQL rendering.

Kagiroi is experimental. Check the target dialect's type mappings and supported
operations before using it in an application.

## Supported databases

| Database | Connection options |
| --- | --- |
| <img src="https://cdn.jsdelivr.net/gh/devicons/devicon@v2.16.0/icons/sqlite/sqlite-original.svg" alt="" width="28"> SQLite | [In memory](#sqlite-in-memory), [file-backed](#sqlite-file-backed) |
| <img src="https://cf-icons.pages.dev/d1.svg" alt="" width="28"> Cloudflare D1 | [Cloudflare Workers](#cloudflare-d1) |
| <img src="https://cdn.jsdelivr.net/gh/devicons/devicon@v2.16.0/icons/postgresql/postgresql-original.svg" alt="" width="28"> PostgreSQL | [Direct](#postgresql-direct), <img src="https://cf-icons.pages.dev/hyperdrive.svg" alt="" width="20"> [Cloudflare Hyperdrive](#postgresql-through-cloudflare-hyperdrive) |
| <img src="https://raw.githubusercontent.com/electric-sql/pglite/main/docs/public/img/brand/logo-light.svg" alt="" width="80"> PGlite | [PGlite driver](#pglite) |
| <img src="https://cdn.jsdelivr.net/gh/devicons/devicon@v2.16.0/icons/mysql/mysql-original.svg" alt="" width="28"> MySQL | [Direct](#mysql-direct), <img src="https://cf-icons.pages.dev/hyperdrive.svg" alt="" width="20"> [Cloudflare Hyperdrive](#mysql-through-cloudflare-hyperdrive) |
| <img src="https://cdn.jsdelivr.net/gh/devicons/devicon@v2.16.0/icons/mariadb/mariadb-original.svg" alt="" width="28"> MariaDB | [Direct](#mariadb-direct), <img src="https://cf-icons.pages.dev/hyperdrive.svg" alt="" width="20"> [Cloudflare Hyperdrive](#mariadb-through-cloudflare-hyperdrive) |
| <img src="https://cdn.jsdelivr.net/gh/devicons/devicon@v2.16.0/icons/microsoftsqlserver/microsoftsqlserver-original.svg" alt="" width="28"> SQL Server | [Direct](#sql-server-direct) |
| <img src="https://cdn.jsdelivr.net/gh/devicons/devicon@v2.16.0/icons/oracle/oracle-original.svg" alt="" width="40"> Oracle Database 23ai+ | [Direct](#oracle-database-23ai-direct) |

## Start here

1. Add the BOM and the dialect/driver modules for the database you use.
2. Define tables with `kagiroi-schema`.
3. Follow the [Kagiroi cheat sheet](docs/CHEATSHEET.md) for typed
   queries, DML, DDL, transactions, and dialect boundaries.
4. Confirm physical types and codecs in [Kagiroi type mappings](docs/TYPE_MAPPINGS.md).

## Install

Use `kagiroi-bom` as a Gradle platform to align every Kagiroi artifact, and
choose the dependencies for your connection target. The BOM contributes version
constraints only; it does not add a runtime library to the application classpath.

### SQLite, in memory

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-sqlite-inmemory-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlite-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlite-quwac-bignumber")
}
```

### SQLite, file-backed

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-sqlite-file-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlite-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlite-quwac-bignumber")
}
```

### Cloudflare D1

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-d1-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlite-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlite-quwac-bignumber")
}
```

### PostgreSQL, direct

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-postgres-node-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-postgres-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-postgres-quwac-bignumber")
}
```

### PostgreSQL through Cloudflare Hyperdrive

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-postgres-hyperdrive-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-postgres-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-postgres-quwac-bignumber")
}
```

### PGlite

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-pglite-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-postgres-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-postgres-quwac-bignumber")
}
```

### MySQL, direct

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-mysql-node-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-quwac-bignumber")
}
```

### MySQL through Cloudflare Hyperdrive

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-mysql-hyperdrive-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-quwac-bignumber")
}
```

### MariaDB, direct

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-mariadb")
    implementation("dev.quwac.kagiroi:kagiroi-mysql-node-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-quwac-bignumber")
}
```

### MariaDB through Cloudflare Hyperdrive

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-mariadb")
    implementation("dev.quwac.kagiroi:kagiroi-mysql-hyperdrive-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-mysql-quwac-bignumber")
}
```

### SQL Server, direct

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-sqlserver-node-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlserver-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-sqlserver-quwac-bignumber")
}
```

### Oracle Database 23ai+, direct

```kotlin
dependencies {
    implementation(platform("dev.quwac.kagiroi:kagiroi-bom:<version>"))
    implementation("dev.quwac.kagiroi:kagiroi-node-oracle-driver")

    // Optional: kotlinx-datetime columns
    // implementation("dev.quwac.kagiroi:kagiroi-oracle-kotlinx-datetime")

    // Optional: BigNumber columns
    // implementation("dev.quwac.kagiroi:kagiroi-oracle-quwac-bignumber")
}
```

Each driver exposes its dialect and the core SQL and schema modules transitively.
MariaDB also requires its own dialect module alongside the MySQL driver.
Optional dialect extensions expose their shared type modules transitively; see
[Kagiroi type mappings](docs/TYPE_MAPPINGS.md) for the supported types.

They do not provide Kotlin/Native, standalone Wasm, or WASI database clients.

## Modules

| Need | Modules |
| --- | --- |
| Core database contracts | `kagiroi-core` |
| Table definitions and explicit application schema | `kagiroi-schema` |
| Typed queries, writes, and DDL | `kagiroi-sql` |
| Optional [`kotlinx-datetime`](https://github.com/Kotlin/kotlinx-datetime) columns | Dialect-specific `kotlinx-datetime` extension |
| Optional [BigNumber](https://github.com/quwac/big-number) columns | Dialect-specific BigNumber extension |
| Version alignment | `kagiroi-bom` |

`kagiroi-schema` contains tables, columns, expressions, and `schema(...)`.
`kagiroi-sql` contains statements, parameter rendering, and driver-facing execution
contracts. Dialects contain SQL syntax and physical types; drivers contain host and
connection integration.

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
