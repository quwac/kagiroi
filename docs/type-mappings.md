# Kagiroi type mappings

`kagiroi-schema` supplies portable column types. Dialect modules supply explicit
physical types. Values remain bound parameters. Columns are `NOT NULL` by default;
`nullable()` changes both the Kotlin and SQL type. `transform` and `asEnum` retain
the column's physical storage type.

## Standard schema types

| Kotlin type | SQLite (in-memory/file) | Cloudflare D1 | PostgreSQL | MySQL | MariaDB | SQL Server | Oracle 23ai+ |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `String` | `TEXT` | `TEXT` | `TEXT` | `TEXT` | `TEXT` | `NVARCHAR(MAX)` | `VARCHAR2(4000 CHAR)`‡ |
| `Boolean` | `INTEGER` (`0` or `1`) | `INTEGER` (`0` or `1`) | `BOOLEAN` | `BOOLEAN` | `BOOLEAN` | `BIT` | `BOOLEAN` |
| `Byte` | `INTEGER` | `INTEGER` | `SMALLINT` | `TINYINT` | `TINYINT` | `SMALLINT` | `NUMBER(3,0)` |
| `Short` | `INTEGER` | `INTEGER` | `SMALLINT` | `SMALLINT` | `SMALLINT` | `SMALLINT` | `NUMBER(5,0)` |
| `Int` | `INTEGER` | `INTEGER` | `INTEGER` | `INT` | `INT` | `INT` | `NUMBER(10,0)` |
| `Long` | `INTEGER` | `INTEGER` | `BIGINT` | `BIGINT` | `BIGINT` | `BIGINT` | `NUMBER(19,0)` |
| `UByte` | `INTEGER` | `INTEGER` | `SMALLINT` | `TINYINT UNSIGNED` | `TINYINT UNSIGNED` | `TINYINT` | `NUMBER(3,0)` |
| `UShort` | `INTEGER` | `INTEGER` | `INTEGER` | `SMALLINT UNSIGNED` | `SMALLINT UNSIGNED` | `INT` | `NUMBER(5,0)` |
| `UInt` | `INTEGER` | `INTEGER` | `BIGINT` | `INT UNSIGNED` | `INT UNSIGNED` | `BIGINT` | `NUMBER(10,0)` |
| `ULong` | `INTEGER`* | `INTEGER`* | `NUMERIC` | `BIGINT UNSIGNED` | `BIGINT UNSIGNED` | `DECIMAL(20,0)` | `NUMBER(20,0)` |
| `Float` | `REAL` | `REAL` | `REAL` | `FLOAT` | `FLOAT` | `REAL` | `BINARY_FLOAT` |
| `Double` | `REAL` | `REAL` | `DOUBLE PRECISION` | `DOUBLE` | `DOUBLE` | `FLOAT` | `BINARY_DOUBLE` |
| `ByteArray` | `BLOB` | `BLOB` | `BYTEA` | `BLOB` | `BLOB` | `VARBINARY(MAX)` | `RAW(2000)` |
| `Uuid` | `TEXT` | `TEXT` | `UUID` | `CHAR(36)` | `UUID`† | `UNIQUEIDENTIFIER` | `RAW(16)` |
| `kotlin.time.Instant` | ISO 8601 `TEXT` | ISO 8601 `TEXT` | `TIMESTAMP WITH TIME ZONE` | `TIMESTAMP(6)` | `DATETIME(6)` | `DATETIMEOFFSET(7)` | `TIMESTAMP(9) WITH TIME ZONE` |
| `kotlin.time.Duration` | ISO 8601 `TEXT` | ISO 8601 `TEXT` | ISO 8601 `TEXT` | ISO 8601 `TEXT` | ISO 8601 `TEXT` | ISO 8601 `NVARCHAR(MAX)` | ISO 8601 `VARCHAR2(4000 CHAR)` |

\* SQLite has signed 64-bit `INTEGER` storage. Its local drivers reject an
`ULong` greater than `Long.MAX_VALUE`; D1 also rejects integer bindings outside
the JavaScript safe-integer range. Neither transport silently converts those
values through JavaScript `Number`.

† MariaDB native `UUID` requires MariaDB 10.7 or newer. `MariaDbTypes.charUuid`
and `charUuid(name)` select portable `CHAR(36)`; `MariaDbTypes.binaryUuid` and
`binaryUuid(name)` select `BINARY(16)`. Each physical type carries its own safe
scalar codec.

‡ Oracle treats a zero-length character value as NULL. Oracle's typed and raw
parameter adapters reject Kotlin `""`, so `String` does not claim empty-string
round-trip semantics on this dialect.

`Duration` uses `Duration.toIsoString()` and `Duration.parseIsoString()` over
`TEXT`. It deliberately does not use PostgreSQL `INTERVAL` or MySQL/MariaDB
`TIME`: those types have calendar or range semantics that differ from Kotlin's
fixed elapsed-time value.

## Dialect physical types

Use `Table.column(name, type)` to opt into a physical type. The type factory
selects the DDL declaration and its scalar transport together; application code
does not interpolate values into SQL.

```kotlin
import dev.quwac.kagiroi.mariadb.MariaDbTypes

object Events : Table("events") {
    val source = column("source", MariaDbTypes.varchar(100))
    val address = column("address", MariaDbTypes.inet6)
    val payload = column("payload", MariaDbTypes.json)
    val amount = column("amount", MariaDbTypes.decimal(30, 18))
}
```

An exact-number or domain value can use a factory overload with an explicit
`ColumnMapping`, or transform a physical scalar column. This keeps precision,
validation, and conversion policy visible at the declaration site.

Each catalog uses one stable spelling for one storage semantics. SQL aliases that do
not add a distinct transport or DDL behavior are not duplicated; for example,
PostgreSQL `decimal` is selected by `numeric`, and MySQL `INTEGER` is selected by
`integer`. SQL-mode- or server-configuration-dependent forms such as MySQL `REAL`,
national-character aliases, and MariaDB Oracle-mode aliases are intentionally not
modeled as unconditional types. A dialect extension or parameterized raw DDL can make
that server-specific choice explicitly.

### SQLite and Cloudflare D1

SQLite has runtime storage classes rather than a closed catalog of native column
types. `SqliteTypes` exposes every declarable storage class with one lossless
Kagiroi scalar contract:

| Factory | DDL | Kotlin transport |
| --- | --- | --- |
| `SqliteTypes.integer` | `INTEGER` | `Long` |
| `SqliteTypes.real` | `REAL` | `Double` |
| `SqliteTypes.text` | `TEXT` | `String` |
| `SqliteTypes.blob` | `BLOB` | `ByteArray` |

`SqliteTypes.mapped(...)` creates a domain mapping over one of those declarations.
`SqliteTypes.named(...)` and `namedText(...)` quote a caller-selected SQLite type name
and render zero, one, or two nonnegative numeric attributes structurally. They require an
explicit scalar mapping, so a conventional or extension type stays typed without a raw DDL
string. `NUMERIC` affinity and STRICT-table `ANY` can hold more than one runtime storage
class, so they are not presented as a misleading `Column<Any>` mapping. D1 uses the same
SQLite declarations and transport boundary.

### PostgreSQL

`PostgresTypes` exposes PostgreSQL built-ins as typed physical declarations.
Types whose protocol text syntax is PostgreSQL-specific intentionally use `String`
until an application supplies a stricter `ColumnMapping`.

| Family | Factories | Kotlin transport |
| --- | --- | --- |
| scalar and generated shorthand | `boolean`, `smallInt`, `integer`, `bigInt`, `smallSerial`, `serial`, `bigSerial`, `real`, `doublePrecision` | matching Kotlin scalar |
| exact numeric | `numeric()`, `numeric(p, s)`, `numeric(p, s, mapping)` | exact `String` or an explicit mapped application type; `p` is 1 through 1000 and `s` is -1000 through 1000 |
| character and binary | `text`, `catalogCharacter`, `name`, `character(n)`, `varchar()`, `varchar(n)`, `bit(n)`, `varbit(n?)`, `bytea`, `uuid` | `String`, except `bytea` is `ByteArray` and `uuid` is `Uuid` |
| temporal | `date`, `time`, `timeWithTimeZone`, `timestamp`, `timestampWithTimeZone`, precision overloads, `interval(...)` | `String` by default; explicit mappings are available for `DATE`, `TIME WITHOUT TIME ZONE(p)`, and `TIMESTAMP WITHOUT TIME ZONE(p)` |
| document, network, and geometric | `money`, `json`, `jsonb`, `jsonPath`, `inet`, `cidr`, `macaddr`, `macaddr8`, `point`, `line`, `lineSegment`, `box`, `path`, `polygon`, `circle`, `xml` | `String` |
| search and replication | `textSearchQuery`, `textSearchVector`, `logSequenceNumber`, `transactionSnapshot`, `txidSnapshot` | `String` |
| ranges | `int4Range`, `int8Range`, `numericRange`, `timestampRange`, `timestampWithTimeZoneRange`, `dateRange`, and the six matching multiranges | `String` |
| arrays and named types | `array(element)`, `namedText(...)`, `named(...)` | `String`, or the caller's explicit mapping |
| object identifiers | `oid`, `xid`, `cid`, `xid8`, `tupleId`, `int2Vector`, `oidVector`, `aclItem`, and `regClass`/`regCollation`/`regConfig`/`regDictionary`/`regNamespace`/`regOper`/`regOperator`/`regProcedure`/`regProc`/`regRole`/`regType` | `UInt`, `ULong`, or `String` as selected by the factory |

`namedText` and `named` quote each schema and type-name component. They support
pre-existing PostgreSQL enum, domain, composite, and extension types without
pretending that their value syntax is generic SQL. PostgreSQL pseudo-types are
not table-column types and are intentionally absent. `txidSnapshot` exists for
existing schemas; PostgreSQL marks it deprecated in favor of `pg_snapshot`.

`smallSerial`, `serial`, and `bigSerial` retain PostgreSQL's legacy sequence-backed
DDL behavior. They remain explicit PostgreSQL physical types. Generic
`long(...).identity()` instead renders PostgreSQL `GENERATED BY DEFAULT AS IDENTITY`.
`identity(startWith = ..., incrementBy = ...)` retains those explicit identity options
as schema metadata and renders PostgreSQL `START WITH` / `INCREMENT BY` options.
Independent generic sequences use `sequence(name)`, `createSequence(...)`, a
`long(...).default(sequence.nextValue())` declaration, and
`nextSequenceValue(sequence)` for one explicit allocation; they do not provide a
generated-key API. For a migration-managed schema, declare the Sequence as a property inside a
registered `Table`; the migration registry collects Table-declared sequences rather than
standalone DDL values.

### MySQL

`MySqlTypes` selects MySQL declarations and exact scalar transports.

| Family | Factories | Kotlin transport |
| --- | --- | --- |
| integer and generated shorthand | `boolean`, signed/unsigned `tinyInt`, `smallInt`, `mediumInt`, `integer`, `bigInt`, `serial`, `year` | matching Kotlin scalar; `serial` is `ULong` |
| approximate and exact numeric | `float`, `double`, `decimal(p, s)`, `numeric(p, s)`, mapping overloads | `Float`, `Double`, exact `String`, or an explicit mapped application type |
| character and binary | `tinyText`, `text`, `mediumText`, `longText`, `char(n)`, `varchar(n)`, `tinyBlob`, `blob`, `mediumBlob`, `longBlob`, `binary(n)`, `varbinary(n)`, `bit(n)`, `charUuid`, `binaryUuid` | `String`, `ByteArray`, or `Uuid` |
| temporal | `date()`, `time(fsp)`, `dateTime(fsp)`, `timestamp(fsp)` | `String` by default; explicit mappings are available for `DATE`, `TIME(fsp)`, and `DATETIME(fsp)` |
| structured | `json`, `enum(first, vararg remaining)` / `enum(NonEmptyList)`, `set(first, vararg remaining)` / `set(NonEmptyList)` | `String` |
| spatial | `geometry`, `point`, `lineString`, `polygon`, `multiPoint`, `multiLineString`, `multiPolygon`, `geometryCollection` | MySQL's SRID-prefixed WKB `ByteArray` |

`DECIMAL` and `NUMERIC` factory overloads accept a `ColumnMapping`, so
arbitrary-precision application values can use a chosen `(p,s)` declaration
without a raw DDL escape hatch. mysql2 is configured with `supportBigNumbers`,
`bigNumberStrings`, `decimalNumbers: false`, and `jsonStrings`; `BIGINT`,
unsigned `BIGINT`, `DECIMAL`, and JSON do not silently pass through JavaScript
`Number`. mysql2 geometry objects are converted to the server binary format
before they cross the Kotlin boundary.

`SERIAL` keeps MySQL's `BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE`
shorthand inside the MySQL dialect catalog. It does not provide a portable key
retrieval abstraction.

### MariaDB

`MariaDbTypes` reuses only physical declarations that remain semantically and
transport-compatible with MySQL: integer families, `SERIAL`, floating point,
text/blob families, fixed/variable character and binary strings, `BIT`, temporal
types, `ENUM`, `SET`, and spatial types. `MySqlTypes.json` is deliberately not
accepted by `MariaDbDialect`.

| MariaDB-specific factory | DDL | Kotlin transport | Availability |
| --- | --- | --- | --- |
| `uuid` | `UUID` | `Uuid` | MariaDB 10.7+ |
| `charUuid` | `CHAR(36)` | `Uuid` | portable representation |
| `binaryUuid` | `BINARY(16)` | `Uuid` | RFC 4122 network-byte-order bytes |
| `json` | `JSON` | `String` | validated `LONGTEXT` alias |
| `inet6` | `INET6` | canonical `String` | MariaDB 10.5+ |
| `inet4` | `INET4` | canonical `String` | MariaDB 10.10+ |
| `vector(n)` | `VECTOR(n)` | little-endian Float32 `ByteArray` | MariaDB 11.7+ |
| `xmlType` | `XMLTYPE` | `String` | MariaDB 12.3+ |
| `decimal(p,s)` / `numeric(p,s)` | `DECIMAL(p,s)` | exact `String` or an explicit mapped application type | precision 1–65, scale 0–38 |

MariaDB `JSON` is a `LONGTEXT` alias with `utf8mb4_bin` collation and JSON validation;
it retains assigned text and compares as text. MySQL `JSON` has native binary JSON
storage and different comparison behavior. The two catalog entries therefore stay
separate even though both bind a JSON document as a parameterized `String`.

MariaDB `UUID` stores 128 bits and returns the RFC 4122 string representation.
`CHAR(36)` and `BINARY(16)` remain available for cross-version or existing schemas.
MariaDB `ROW`, `TYPE OF`, and `ROW TYPE OF` are stored-program declarations and
function-return syntax, not table-column types, so they do not appear in this catalog.

### SQL Server

`SqlServerTypes` exposes the SQL Server declarations whose storage and transport
contracts are explicit in Kagiroi.

| Family | Factories | Kotlin transport |
| --- | --- | --- |
| scalar | `bit`, `tinyInt`, `smallInt`, `integer`, `bigInt`, `real`, `float`, `uniqueIdentifier` | matching Kotlin scalar; `bigInt` uses lossless text transport |
| character and binary | `nVarchar`, `nVarchar(n)`, `varchar(n)`, `varBinary`, `varBinary(n)` | `String` or `ByteArray` |
| exact numeric | `decimal(p,s)`, `numeric(p,s)`, and mapping overloads | exact `String`, or an explicit mapped application type |
| temporal | `date`, `time(p)`, `dateTime2(p)`, `dateTimeOffset(p)` | `String` by default; `Instant` for the standard `DATETIMEOFFSET(7)` mapping, with explicit local mappings available |

The standard mapping table above gives the default physical types. `BIGINT`, ULong
decimal storage, exact numeric, and temporal values use lossless text transport before
strict Kotlin parsing. `BIT` must arrive as a native Boolean. Raw SQL rejects DECIMAL,
NUMERIC, MONEY, and SMALLMONEY values already decoded as a JavaScript Number; use
`CONVERT(NVARCHAR, value)` for lossless raw results.

Bounded `NVARCHAR` accepts 1–4,000 characters; `VARCHAR` and `VARBINARY` accept
1–8,000 bytes. `DECIMAL(p,s)` and `NUMERIC(p,s)` require p 1–38 and s 0–p.

Temporal factories use text transport with p 0–7. The standard Instant mapping is
`DATETIMEOFFSET(7)`; `kagiroi-sqlserver-kotlinx-datetime` adds local DATE, TIME,
and DATETIME2 mappings. DATETIME is not a standard catalog type.

`kagiroi-sqlserver-quwac-bignumber` provides explicit `decimalInteger(p)`,
`decimal(p,s)`, and `numeric(p,s)` mappings. It neither chooses precision/scale nor
rounds values automatically.

### Oracle Database

`OracleTypes` targets Oracle Database 23ai+. Standard `String` storage is Unicode
database-character-set `VARCHAR2(4000 CHAR)`, while `nVarchar2(n)`, `char(n)`,
`nChar(n)`, `clob`, and `nClob` make the national-character, fixed-width, and LOB
choices explicit. `RAW(2000)` is the standard `ByteArray` representation; `blob` is
available for an application that accepts buffered LOB limits. LOB streaming is not a
Kagiroi capability.

| Family | Factories | Kotlin transport |
| --- | --- | --- |
| character | `varchar2`, `varchar2(n)`, `nVarchar2`, `nVarchar2(n)`, `char(n)`, `nChar(n)`, `clob`, `nClob` | `String` |
| exact and approximate numeric | `number(p,s)`, mapping overload, `number3`, `number5`, `number10`, `number19`, `number20`, `binaryFloat`, `binaryDouble` | exact `String` or explicit mapping; matching scalar for fixed declarations |
| binary and UUID | `raw`, `raw(n)`, `blob`, `rawUuid` | `ByteArray` or RFC 4122 `Uuid` |
| temporal | `date`, `timestamp`, `timestampWithTimeZone`, `timestampWithLocalTimeZone` | explicit `String` declarations; calendar semantics remain application-defined |

Oracle NUMBER crosses node-oracledb as decimal text. Long, ULong, BigInteger, and
BigDecimal never cross the JavaScript boundary as a Number. `OracleQuwacBigNumber`
maps logical Pure Kotlin BigNumber columns to NUMBER without choosing a precision or
scale; `OracleTypes.number(p,s,mapping)` supplies an application-owned declaration.

`OracleKotlinxDateTime` maps LocalDate to DATE and LocalDateTime to TIMESTAMP(9).
The standard Oracle mapping stores Instant as TIMESTAMP(9) WITH TIME ZONE. DATE includes a time-of-day. TIMESTAMP WITH
LOCAL TIME ZONE is session-zone normalized and remains a raw explicit declaration,
not a local-value mapping. `OracleTypes.rawUuid` and the standard `Uuid` mapping use
RAW(16) in RFC 4122 byte order; VARCHAR2(36) remains an application-selected mapping.

## Optional `kotlinx-datetime` types

Install `kagiroi-kotlinx-datetime` for the schema columns and the dialect
extension named in the table. SQLite's extension is reusable by D1.

| Kotlin type | SQLite / D1 (`SqliteKotlinxDateTime`) | PostgreSQL (`PostgresKotlinxDateTime`) | MySQL / MariaDB (`MySqlKotlinxDateTime`) | SQL Server (`SqlServerKotlinxDateTime`) |
| --- | --- | --- | --- | --- |
| `LocalDate` | ISO 8601 `TEXT` | `DATE` | `DATE` | `DATE` |
| `LocalTime` | ISO 8601 `TEXT` | `TIME WITHOUT TIME ZONE` | `TIME(6)` | `TIME(7)` |
| `LocalDateTime` | ISO 8601 `TEXT` | `TIMESTAMP WITHOUT TIME ZONE` | `DATETIME(6)` | `DATETIME2(7)` |
| `UtcOffset` | ISO 8601 offset `TEXT` | ISO 8601 offset `TEXT` | ISO 8601 offset `TEXT` | ISO 8601 offset `NVARCHAR(MAX)` |
| `TimeZone` | IANA identifier `TEXT` | IANA identifier `TEXT` | IANA identifier `TEXT` | IANA identifier `NVARCHAR(MAX)` |

The PostgreSQL, MySQL, and MariaDB time-of-day and timestamp mappings accept at
most six fractional-second digits; SQL Server accepts at most seven. Kagiroi rejects
a value with higher precision instead of rounding it. `UtcOffset` and `TimeZone`
deliberately remain lossless text codecs: database time types do not retain an IANA
zone identifier.

### Physical local temporal mappings

Physical temporal factories default to `String`, so an application never acquires a
time-zone, calendar, or range policy merely by choosing a DDL declaration. The optional
datetime modules provide explicit `ColumnMapping` values for the local-value subset:

```kotlin
import dev.quwac.kagiroi.mariadb.MariaDbTypes
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlCompatibleKotlinxDateTimeMappings
import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTimeMappings

val mysqlStartDate = MySqlTypes.date(MySqlCompatibleKotlinxDateTimeMappings.localDate)
val mysqlStartTime = MySqlTypes.time(6, MySqlCompatibleKotlinxDateTimeMappings.localTime(6))
val mariaDbStart = MariaDbTypes.dateTime(6, MySqlCompatibleKotlinxDateTimeMappings.localDateTime(6))
val postgresStart = PostgresTypes.timestamp(6, PostgresKotlinxDateTimeMappings.localDateTime(6))
```

`MySqlCompatibleKotlinxDateTimeMappings` is shared by MySQL and MariaDB only for
the compatible `DATE`, `TIME`, and `DATETIME` text contracts. It maps `DATE` to
`LocalDate`, `TIME(fsp)` to `LocalTime`, and `DATETIME(fsp)` to `LocalDateTime`.
The PostgreSQL mappings cover `DATE`, `TIME WITHOUT TIME ZONE(p)`, and `TIMESTAMP
WITHOUT TIME ZONE(p)` with the matching local Kotlin types. Each `fsp` or `p` is
supplied to both the physical factory and mapping; a value that would require rounding
is rejected before parameter binding.

A physical mapped column carries its own codec, so it does not require installing a
datetime dialect extension. The logical `localDate`, `localTime`, and `localDateTime`
schema columns still use their installed dialect extension to select a native declaration.

MySQL/MariaDB `TIMESTAMP` remains raw text because its value is converted through the
session time zone. PostgreSQL `TIME WITH TIME ZONE`, `TIMESTAMP WITH TIME ZONE`, and
`INTERVAL` also remain raw text in the physical catalog: an offset, instant, or
calendar-period policy must be selected explicitly by the application. MySQL/MariaDB
`TIME` and PostgreSQL `TIME` can represent values outside `LocalTime`'s civil-time
domain, so the mapping is an explicit local-time subset rather than a claim that every
stored value is parseable. The standard schema `Instant` mapping remains separate.

## Optional BigNumber types

| Kotlin type | SQLite / D1 (`SqliteQuwacBigNumber`) | PostgreSQL (`PostgresQuwacBigNumber`) | MySQL / MariaDB (`MySqlQuwacBigNumber`) | SQL Server (`SqlServerQuwacBigNumber`) |
| --- | --- | --- | --- | --- |
| `BigInteger` | canonical decimal `TEXT` | `NUMERIC` | lossless text codec | `DECIMAL(p,0)` |
| `BigDecimal` | canonical decimal `TEXT` | `NUMERIC` | lossless text codec | `DECIMAL(p,s)` / `NUMERIC(p,s)` |

`BigNumberTextMappings` supplies the standard exact mapping from decimal text to
`BigInteger` or `BigDecimal`. Each physical exact-numeric catalog accepts it while the
application selects precision and scale:

```kotlin
import dev.quwac.kagiroi.mariadb.MariaDbTypes
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings

val mysqlInteger = MySqlTypes.decimal(30, 0, BigNumberTextMappings.integer)
val mariaDbAmount = MariaDbTypes.decimal(50, 38, BigNumberTextMappings.decimal)
val postgresAmount = PostgresTypes.numeric(30, 18, BigNumberTextMappings.decimal)
```

The default exact-numeric mapping remains `String`; it is the low-level lossless transport
and does not impose a BigNumber dependency. A column built with a physical mapping carries
its own codec, so it does not require a BigNumber driver extension. The generic `bigInteger`
and `bigDecimal` columns still do not choose a MySQL/MariaDB or SQL Server `DECIMAL(p,s)`
policy. PostgreSQL accepts unconstrained `NUMERIC` for its existing BigNumber extension.

## Indexes and JSON

Generic MySQL-compatible Typed DDL rejects indexes on `TEXT`/`BLOB` families because
they require an explicit prefix. It also rejects direct JSON, spatial, VECTOR, and
XMLTYPE indexes because they need a generated/functional, spatial, vector, or other
dialect-specific index definition. MySQL functional keys are available through a typed
expression key only when the expression maps to a bounded, indexable physical type;
for example, `lower(column("code", MySqlTypes.varchar(40)))`. MariaDB's generic DDL
does not render functional keys. Index prefixes, spatial indexes, vector indexes,
collations, engine attributes, non-portable generated expressions, and Sequence
allocation options remain dialect-specific DDL work.
