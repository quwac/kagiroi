# Kagiroi type mappings

`kagiroi-schema` supplies portable types; dialect modules supply physical types.
Columns are `NOT NULL` by default. `nullable()` changes the Kotlin and SQL type.

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

\* SQLite defaults to `INTEGER` for `ULong` arithmetic and rejects values above
`Long.MAX_VALUE`. Use `textUlong(name)` for the full range as zero-padded `TEXT`;
SQLite arithmetic on this mapping is imprecise.
D1 also rejects integer bindings outside the JavaScript safe-integer range.
Neither transport silently converts those values through JavaScript `Number`.

† MariaDB native `UUID` requires MariaDB 10.7 or newer. `MariaDbTypes.charUuid`
and `charUuid(name)` select portable `CHAR(36)`; `MariaDbTypes.binaryUuid` and
`binaryUuid(name)` select `BINARY(16)`. Each physical type carries its own safe
scalar codec.

‡ Oracle treats a zero-length character value as NULL. Oracle's typed and raw
parameter adapters reject Kotlin `""`, so `String` does not claim empty-string
round-trip semantics on this dialect.

`Duration` uses `Duration.toIsoString()` / `Duration.parseIsoString()` over `TEXT`.
PostgreSQL `INTERVAL` and MySQL/MariaDB `TIME` have different calendar or range semantics.

## Dialect physical types

Use `Table.column(name, type)` for a physical type. Factories select DDL and scalar
transport together.

```kotlin
import dev.quwac.kagiroi.mariadb.MariaDbTypes

object Events : Table("events") {
    val source = column("source", MariaDbTypes.varchar(100))
    val address = column("address", MariaDbTypes.inet6)
    val payload = column("payload", MariaDbTypes.json)
    val amount = column("amount", MariaDbTypes.decimal(30, 18))
}
```

Use factory overloads with `ColumnMapping` for exact numbers and domain values.
Catalogs expose one stable spelling per storage contract: PostgreSQL uses `numeric`
for `decimal`, and MySQL uses `integer` for `INTEGER`. Server-mode-dependent forms
belong in a dialect extension or parameterized raw DDL.

### SQLite and Cloudflare D1

`SqliteTypes` exposes SQLite storage classes and a full-range `ULong` mapping:

| Factory | DDL | Kotlin transport |
| --- | --- | --- |
| `SqliteTypes.integer` | `INTEGER` | `Long` |
| `SqliteTypes.real` | `REAL` | `Double` |
| `SqliteTypes.text` | `TEXT` | `String` |
| `Table.textUlong(name)` / `SqliteTypes.textUlong` | `TEXT` | `ULong` (20-digit zero-padded) |
| `SqliteTypes.blob` | `BLOB` | `ByteArray` |

`mapped(...)` adds a domain mapping. `named(...)` and `namedText(...)` quote a caller-selected
type name with zero, one, or two nonnegative attributes and require an explicit scalar mapping.
`NUMERIC` affinity and STRICT `ANY` have no `Column<Any>` mapping. D1 uses the same boundary.

### PostgreSQL

`PostgresTypes` exposes typed PostgreSQL declarations. PostgreSQL-specific protocol text is
`String` until the application supplies a `ColumnMapping`.

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

`namedText` and `named` quote each schema and type component for existing enum, domain,
composite, and extension types. PostgreSQL pseudo-types are not table columns.
`txidSnapshot` is supported for existing schemas but PostgreSQL deprecates it for `pg_snapshot`.

`smallSerial`, `serial`, and `bigSerial` are explicit legacy sequence-backed declarations.
Use `long(...).identity()` for `GENERATED BY DEFAULT AS IDENTITY`; its `startWith` and
`incrementBy` options render native identity options. For independent sequences, declare
`sequence(name)` inside a registered `Table`, then use `createSequence(...)`,
`sequence.nextValue()`, and `nextSequenceValue(sequence)`.

### MySQL

`MySqlTypes` selects MySQL declarations and scalar transports.

| Family | Factories | Kotlin transport |
| --- | --- | --- |
| integer and generated shorthand | `boolean`, signed/unsigned `tinyInt`, `smallInt`, `mediumInt`, `integer`, `bigInt`, `serial`, `year` | matching Kotlin scalar; `serial` is `ULong` |
| approximate and exact numeric | `float`, `double`, `decimal(p, s)`, `numeric(p, s)`, mapping overloads | `Float`, `Double`, exact `String`, or an explicit mapped application type |
| character and binary | `tinyText`, `text`, `mediumText`, `longText`, `char(n)`, `varchar(n)`, `tinyBlob`, `blob`, `mediumBlob`, `longBlob`, `binary(n)`, `varbinary(n)`, `bit(n)`, `charUuid`, `binaryUuid` | `String`, `ByteArray`, or `Uuid` |
| temporal | `date()`, `time(fsp)`, `dateTime(fsp)`, `timestamp(fsp)` | `String` by default; explicit mappings are available for `DATE`, `TIME(fsp)`, and `DATETIME(fsp)` |
| structured | `json`, `enum(first, vararg remaining)` / `enum(NonEmptyList)`, `set(first, vararg remaining)` / `set(NonEmptyList)` | `String` |
| spatial | `geometry`, `point`, `lineString`, `polygon`, `multiPoint`, `multiLineString`, `multiPolygon`, `geometryCollection` | MySQL's SRID-prefixed WKB `ByteArray` |

`DECIMAL` and `NUMERIC` accept `ColumnMapping` for arbitrary-precision values.
mysql2 uses lossless text settings for `BIGINT`, unsigned `BIGINT`, `DECIMAL`, and JSON;
geometry objects become server binary before the Kotlin boundary.

`SERIAL` is MySQL's `BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE` shorthand. It has no
portable key-retrieval API.

### MariaDB

`MariaDbTypes` reuses compatible MySQL declarations. `MySqlTypes.json` is not accepted by
`MariaDbDialect`.

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

MariaDB `JSON` is validated `LONGTEXT` with text comparison; MySQL JSON has binary storage.
Both bind parameterized `String`, but use separate catalog entries. MariaDB `UUID` stores 128 bits
and returns RFC 4122 text; `CHAR(36)` and `BINARY(16)` remain available. `ROW`, `TYPE OF`, and
`ROW TYPE OF` are not table-column types.

### SQL Server

`SqlServerTypes` exposes SQL Server declarations with explicit storage and transport.

| Family | Factories | Kotlin transport |
| --- | --- | --- |
| scalar | `bit`, `tinyInt`, `smallInt`, `integer`, `bigInt`, `real`, `float`, `uniqueIdentifier` | matching Kotlin scalar; `bigInt` uses lossless text transport |
| character and binary | `nVarchar`, `nVarchar(n)`, `varchar(n)`, `varBinary`, `varBinary(n)` | `String` or `ByteArray` |
| exact numeric | `decimal(p,s)`, `numeric(p,s)`, and mapping overloads | exact `String`, or an explicit mapped application type |
| temporal | `date`, `time(p)`, `dateTime2(p)`, `dateTimeOffset(p)` | `String` by default; `Instant` for the standard `DATETIMEOFFSET(7)` mapping, with explicit local mappings available |

`BIGINT`, ULong decimal storage, exact numerics, and temporal values use lossless text before
strict Kotlin parsing; `BIT` must be a Boolean. Raw SQL rejects Number-decoded DECIMAL, NUMERIC,
MONEY, and SMALLMONEY; use `CONVERT(NVARCHAR, value)` for a lossless raw result.

Bounded `NVARCHAR` accepts 1–4,000 characters; `VARCHAR` and `VARBINARY` 1–8,000 bytes.
`DECIMAL`/`NUMERIC` require p 1–38 and s 0–p. Temporal precision is 0–7; standard `Instant`
uses `DATETIMEOFFSET(7)`. `kagiroi-sqlserver-kotlinx-datetime` adds local DATE, TIME, and
DATETIME2 mappings. `DATETIME` is not a standard catalog type.

`kagiroi-sqlserver-quwac-bignumber` provides explicit `decimalInteger(p)`, `decimal(p,s)`, and
`numeric(p,s)` mappings without choosing precision, scale, or rounding.

### Oracle Database

`OracleTypes` targets Oracle Database 23ai+. Standard `String` is `VARCHAR2(4000 CHAR)`;
national-character, fixed-width, and LOB choices are explicit. `RAW(2000)` is standard
`ByteArray`; `blob` has buffered LOB limits and no streaming API.

| Family | Factories | Kotlin transport |
| --- | --- | --- |
| character | `varchar2`, `varchar2(n)`, `nVarchar2`, `nVarchar2(n)`, `char(n)`, `nChar(n)`, `clob`, `nClob` | `String` |
| exact and approximate numeric | `number(p,s)`, mapping overload, `number3`, `number5`, `number10`, `number19`, `number20`, `binaryFloat`, `binaryDouble` | exact `String` or explicit mapping; matching scalar for fixed declarations |
| binary and UUID | `raw`, `raw(n)`, `blob`, `rawUuid` | `ByteArray` or RFC 4122 `Uuid` |
| temporal | `date`, `timestamp`, `timestampWithTimeZone`, `timestampWithLocalTimeZone` | explicit `String` declarations; calendar semantics remain application-defined |

Oracle NUMBER crosses node-oracledb as decimal text; Long, ULong, BigInteger, and BigDecimal
never cross as JavaScript Number. `OracleQuwacBigNumber` maps logical BigNumber to NUMBER;
`OracleTypes.number(p,s,mapping)` supplies precision and scale.

`OracleKotlinxDateTime` maps LocalDate to DATE and LocalDateTime to TIMESTAMP(9). Standard
Instant is `TIMESTAMP(9) WITH TIME ZONE`; DATE includes time-of-day. `TIMESTAMP WITH LOCAL TIME
ZONE` is session-normalized raw storage. `rawUuid` and standard `Uuid` use `RAW(16)` in RFC 4122
byte order; `VARCHAR2(36)` is application-selected.

## Optional `kotlinx-datetime` types

Install the dialect-specific `kotlinx-datetime` extension for your connection target.
It exposes `kagiroi-kotlinx-datetime` transitively. SQLite's extension also serves D1.
See the [README](../README.md#install) for dependency declarations.

| Kotlin type | SQLite / D1 (`SqliteKotlinxDateTime`) | PostgreSQL (`PostgresKotlinxDateTime`) | MySQL / MariaDB (`MySqlKotlinxDateTime`) | SQL Server (`SqlServerKotlinxDateTime`) |
| --- | --- | --- | --- | --- |
| `LocalDate` | ISO 8601 `TEXT` | `DATE` | `DATE` | `DATE` |
| `LocalTime` | ISO 8601 `TEXT` | `TIME WITHOUT TIME ZONE` | `TIME(6)` | `TIME(7)` |
| `LocalDateTime` | ISO 8601 `TEXT` | `TIMESTAMP WITHOUT TIME ZONE` | `DATETIME(6)` | `DATETIME2(7)` |
| `UtcOffset` | ISO 8601 offset `TEXT` | ISO 8601 offset `TEXT` | ISO 8601 offset `TEXT` | ISO 8601 offset `NVARCHAR(MAX)` |
| `TimeZone` | IANA identifier `TEXT` | IANA identifier `TEXT` | IANA identifier `TEXT` | IANA identifier `NVARCHAR(MAX)` |

The PostgreSQL datetime module exposes typed `atTimeZone(zone)` expressions:
`SqlExpression<Instant>` converts to `SqlExpression<LocalDateTime>`, and the reverse
interprets a non-null local date-time in that zone. `currentInstant().atTimeZone("UTC")`
is an explicit UTC local date-time for DEFAULT and UPDATE expressions, independent of
the session time zone. Other dialects reject this PostgreSQL operator.

PostgreSQL, MySQL, and MariaDB accept at most six fractional digits; SQL Server accepts seven.
Higher precision is rejected. `UtcOffset` and `TimeZone` use lossless text because database time
types do not retain an IANA zone identifier.

### Physical local temporal mappings

Physical temporal factories default to `String`. Optional datetime modules provide explicit
`ColumnMapping` values for local values:

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

`MySqlCompatibleKotlinxDateTimeMappings` covers MySQL/MariaDB `DATE`, `TIME`, and `DATETIME`.
PostgreSQL mappings cover `DATE`, `TIME WITHOUT TIME ZONE`, and `TIMESTAMP WITHOUT TIME ZONE`.
Pass the same `fsp` or `p` to factory and mapping; values requiring rounding are rejected.

Physical mapped columns carry their own codec. Logical `localDate`, `localTime`, and
`localDateTime` columns need their dialect extension. MySQL/MariaDB `TIMESTAMP`, PostgreSQL
zoned temporal types, and `INTERVAL` remain raw text. MySQL/MariaDB `TIME` and PostgreSQL `TIME`
can exceed `LocalTime`'s civil-time domain; standard `Instant` remains separate.

## Optional BigNumber types

| Kotlin type | SQLite / D1 (`SqliteQuwacBigNumber`) | PostgreSQL (`PostgresQuwacBigNumber`) | MySQL / MariaDB (`MySqlQuwacBigNumber`) | SQL Server (`SqlServerQuwacBigNumber`) |
| --- | --- | --- | --- | --- |
| `BigInteger` | canonical decimal `TEXT` | `NUMERIC` | lossless text codec | `DECIMAL(p,0)` |
| `BigDecimal` | canonical decimal `TEXT` | `NUMERIC` | lossless text codec | `DECIMAL(p,s)` / `NUMERIC(p,s)` |

`BigNumberTextMappings` maps decimal text to `BigInteger` or `BigDecimal`; choose precision and
scale at the physical declaration:

```kotlin
import dev.quwac.kagiroi.mariadb.MariaDbTypes
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings

val mysqlInteger = MySqlTypes.decimal(30, 0, BigNumberTextMappings.integer)
val mariaDbAmount = MariaDbTypes.decimal(50, 38, BigNumberTextMappings.decimal)
val postgresAmount = PostgresTypes.numeric(30, 18, BigNumberTextMappings.decimal)
```

Default exact numerics use lossless `String` transport. Physical mappings carry their own codec.
Generic `bigInteger` and `bigDecimal` do not select MySQL/MariaDB or SQL Server `DECIMAL(p,s)`;
PostgreSQL accepts unconstrained `NUMERIC` for its BigNumber extension.

## Indexes and JSON

MySQL-compatible Typed DDL rejects `TEXT`/`BLOB` indexes without a prefix and direct JSON,
spatial, VECTOR, or XMLTYPE indexes. MySQL functional keys require a bounded typed expression,
for example `lower(column("code", MySqlTypes.varchar(40)))`; MariaDB generic DDL has no
functional keys. Prefixes, spatial/vector indexes, collations, engine attributes, non-portable
generated expressions, and Sequence allocation options remain dialect-specific DDL.
