package dev.quwac.kagiroi.postgres.node

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.kotlinx.datetime.localDate
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.kotlinx.datetime.localTime
import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.quwac.bignumber.bigInteger
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.ExpectedOneRowButFoundMultiple
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.SqlDatabaseContractConfiguration
import dev.quwac.kagiroi.sql.testing.returningContract
import dev.quwac.kagiroi.sql.testing.savepointContract
import dev.quwac.kagiroi.sql.testing.sqlDatabaseContract
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Shared direct-driver compatibility suite for local PostgreSQL and configured providers. */
internal suspend fun nodePostgresDriverContract(database: NodePostgresDatabase, progress: (String) -> Unit = {}) {
    try {
        sqlDatabaseContract(database, postgresCommonContract, progress)
        progress("reset-tables")
        dropContractTables(database)
        progress("create-tables")
        createContractTables(database)
        val fixture = ContractFixture()
        progress("typed-ddl")
        verifyTypedDdl(database)
        progress("configured-identity")
        verifyConfiguredIdentity(database)
        progress("typed-values")
        verifyTypedValues(database, fixture)
        progress("physical-types")
        verifyPostgresPhysicalTypes(database)
        progress("update")
        verifyUpdate(database, fixture.firstId)
        progress("transactions")
        verifyTransactions(database, fixture.secondId)
        progress("savepoints")
        savepointContract(database)
        progress("returning")
        returningContract(database)
        progress("constraints-and-queries")
        verifyConstraintViolations(database)
        verifyQueryContracts(database, fixture)
        progress("concurrent-queries")
        verifyConcurrentQueries(database)
        progress("complete")
    } finally {
        try {
            progress("drop-tables")
            dropContractTables(database)
        } finally {
            progress("close")
            database.close()
        }
    }
}

private suspend fun createContractTables(database: NodePostgresDatabase) {
    database.executeRaw(contractTableDdl)
    database.executeRaw("CREATE TABLE kagiroi_node_postgres_parent (id INTEGER PRIMARY KEY)")
    database.executeRaw(
        """
        CREATE TABLE kagiroi_node_postgres_child (
            id INTEGER PRIMARY KEY,
            parent_id INTEGER NOT NULL REFERENCES kagiroi_node_postgres_parent(id),
            required_value TEXT NOT NULL,
            positive_value INTEGER NOT NULL CHECK (positive_value > 0)
        )
        """.trimIndent(),
    )
    database.executeRaw("INSERT INTO kagiroi_node_postgres_parent (id) VALUES ($1)", listOf(1))
}

private suspend fun dropContractTables(database: NodePostgresDatabase) {
    database.execute(dropTable(TypedDdlRows, ifExists = true))
    database.execute(dropTable(ConfiguredIdentityRows, ifExists = true))
    database.execute(dropTable(CommentedRows, ifExists = true))
    database.execute(dropTable(PostgresPhysicalTypes, ifExists = true))
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_node_postgres_child")
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_node_postgres_parent")
    database.executeRaw(CONTRACT_TABLE_DROP)
}

private suspend fun verifyTypedDdl(database: NodePostgresDatabase) {
    val duration = Duration.parseIsoString("-PT900H0.000000001S")
    database.execute(createTable(TypedDdlRows))
    database.insertInto(TypedDdlRows).values {
        set(TypedDdlRows.id, 1)
        set(TypedDdlRows.stringValue, "Ada")
        set(TypedDdlRows.durationValue, duration)
        set(TypedDdlRows.offsetValue, UtcOffset.parse("+09:00"))
        set(TypedDdlRows.timeZoneValue, TimeZone.of("Asia/Tokyo"))
    }.execute()

    val row = database.select(
        TypedDdlRows.stringValue,
        TypedDdlRows.durationValue,
        TypedDdlRows.offsetValue,
        TypedDdlRows.timeZoneValue,
    )
        .from(TypedDdlRows)
        .one()
    assertEquals("Ada", row[TypedDdlRows.stringValue])
    assertEquals(duration, row[TypedDdlRows.durationValue])
    assertEquals(UtcOffset.parse("+09:00"), row[TypedDdlRows.offsetValue])
    assertEquals(TimeZone.of("Asia/Tokyo"), row[TypedDdlRows.timeZoneValue])

    val physicalTypes = database.queryRaw(
        "SELECT column_name AS kagiroi_column_name, data_type AS kagiroi_data_type " +
            "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = $1",
        listOf(TypedDdlRows.tableName),
    ).toList().associate { result ->
        requireNotNull(result.string("kagiroi_column_name")) to
            requireNotNull(result.string("kagiroi_data_type")).lowercase()
    }
    assertEquals("text", physicalTypes["string_value"])
    assertEquals("text", physicalTypes["duration_value"])
    assertEquals("text", physicalTypes["offset_value"])
    assertEquals("text", physicalTypes["time_zone_value"])

    database.execute(createTable(CommentedRows))

    assertEquals(
        "Application users",
        database.queryRaw(
            "SELECT obj_description('kagiroi_node_postgres_comments'::regclass, 'pg_class') AS comment",
        ).toList().single().string("comment"),
    )
    assertEquals(
        "Primary email address",
        database.queryRaw(
            "SELECT col_description('kagiroi_node_postgres_comments'::regclass, 2) AS comment",
        ).toList().single().string("comment"),
    )
}

private suspend fun verifyTypedValues(database: NodePostgresDatabase, fixture: ContractFixture) {
    val insert = database.insertInto(ContractRows).values {
        set(ContractRows.id, fixture.firstId)
        set(ContractRows.name, "Ada")
        set(ContractRows.active, true)
        set(ContractRows.payload, fixture.payload)
        set(ContractRows.nickname, null)
        set(ContractRows.instantValue, fixture.instant)
        set(ContractRows.durationValue, fixture.duration)
        set(ContractRows.dateValue, fixture.date)
        set(ContractRows.timeValue, fixture.time)
        set(ContractRows.dateTimeValue, fixture.dateTime)
        set(ContractRows.offsetValue, fixture.offset)
        set(ContractRows.timeZoneValue, fixture.timeZone)
        set(ContractRows.integerValue, fixture.integer)
        set(ContractRows.decimalValue, fixture.decimal)
        set(ContractRows.byteValue, fixture.byteValue)
        set(ContractRows.shortValue, fixture.shortValue)
        set(ContractRows.intValue, fixture.intValue)
        set(ContractRows.longValue, fixture.longValue)
        set(ContractRows.ulongValue, fixture.ulongValue)
        set(ContractRows.floatValue, fixture.floatValue)
        set(ContractRows.doubleValue, fixture.doubleValue)
    }.execute()
    assertEquals(1, insert.affectedRows)

    val row = database.select(
        ContractRows.id, ContractRows.active, ContractRows.payload,
        ContractRows.nickname, ContractRows.instantValue, ContractRows.durationValue,
        ContractRows.dateValue, ContractRows.timeValue, ContractRows.dateTimeValue,
        ContractRows.offsetValue, ContractRows.timeZoneValue,
        ContractRows.integerValue, ContractRows.decimalValue, ContractRows.byteValue,
        ContractRows.shortValue, ContractRows.intValue, ContractRows.longValue,
        ContractRows.ulongValue, ContractRows.floatValue, ContractRows.doubleValue,
    ).from(ContractRows).where(ContractRows.id eq fixture.firstId).one()
    assertEquals(fixture.firstId, row[ContractRows.id])
    assertTrue(row[ContractRows.active])
    assertContentEquals(fixture.payload, row[ContractRows.payload])
    assertNull(row[ContractRows.nickname])
    assertEquals(fixture.instant, row[ContractRows.instantValue])
    assertEquals(fixture.duration, row[ContractRows.durationValue])
    assertEquals(fixture.date, row[ContractRows.dateValue])
    assertEquals(fixture.time, row[ContractRows.timeValue])
    assertEquals(fixture.dateTime, row[ContractRows.dateTimeValue])
    assertEquals(fixture.offset, row[ContractRows.offsetValue])
    assertEquals(fixture.timeZone, row[ContractRows.timeZoneValue])
    assertEquals(fixture.integer, row[ContractRows.integerValue])
    // NUMERIC(40, 18) preserves numeric precision but materializes the column's scale.
    assertEquals(0, fixture.decimal.compareTo(row[ContractRows.decimalValue]))
    assertEquals(fixture.byteValue, row[ContractRows.byteValue])
    assertEquals(fixture.shortValue, row[ContractRows.shortValue])
    assertEquals(fixture.intValue, row[ContractRows.intValue])
    assertEquals(fixture.longValue, row[ContractRows.longValue])
    assertEquals(fixture.ulongValue, row[ContractRows.ulongValue])
    assertEquals(fixture.floatValue, row[ContractRows.floatValue])
    assertEquals(fixture.doubleValue, row[ContractRows.doubleValue])
}

private suspend fun verifyUpdate(database: NodePostgresDatabase, firstId: Uuid) {
    val update = database.update(ContractRows) { set(ContractRows.nickname, "updated") }
        .where(ContractRows.id eq firstId)
        .execute()
    assertEquals(1, update.affectedRows)
    assertEquals(
        "updated",
        database.select(ContractRows.nickname).from(ContractRows).where(ContractRows.id eq firstId)
            .one()[ContractRows.nickname],
    )
}

private suspend fun verifyTransactions(database: NodePostgresDatabase, secondId: Uuid) {
    database.transaction(isolation = TransactionIsolation.REPEATABLE_READ) {
        insertContractRow(secondId, "committed")
    }
    assertFailsWith<IllegalStateException> {
        database.transaction {
            insertContractRow(Uuid.parse("123e4567-e89b-12d3-a456-426614174002"), "rolled-back")
            error("rollback")
        }
    }
    assertNull(
        database.select(ContractRows.id).from(ContractRows)
            .where(ContractRows.name eq "rolled-back")
            .oneOrNull(),
    )
}

private suspend fun verifyConstraintViolations(database: NodePostgresDatabase) {
    val unique = assertFailsWith<ConstraintViolation> {
        database.transaction {
            insertContractRow(Uuid.parse("123e4567-e89b-12d3-a456-426614174003"), "Ada")
        }
    }
    assertEquals(ConstraintKind.UNIQUE, unique.kind)
    assertEquals("23505", unique.metadata.sqlState)

    val foreignKey = assertFailsWith<ConstraintViolation> {
        database.executeRaw(
            """
            INSERT INTO kagiroi_node_postgres_child
                (id, parent_id, required_value, positive_value)
            VALUES ($1, $2, $3, $4)
            """.trimIndent(),
            listOf(1, 999, "value", 1),
        )
    }
    assertEquals(ConstraintKind.FOREIGN_KEY, foreignKey.kind)
    assertEquals("23503", foreignKey.metadata.sqlState)

    val notNull = assertFailsWith<ConstraintViolation> {
        database.executeRaw(
            """
            INSERT INTO kagiroi_node_postgres_child
                (id, parent_id, required_value, positive_value)
            VALUES ($1, $2, $3, $4)
            """.trimIndent(),
            listOf(2, 1, null, 1),
        )
    }
    assertEquals(ConstraintKind.NOT_NULL, notNull.kind)
    assertEquals("23502", notNull.metadata.sqlState)

    val check = assertFailsWith<ConstraintViolation> {
        database.executeRaw(
            """
            INSERT INTO kagiroi_node_postgres_child
                (id, parent_id, required_value, positive_value)
            VALUES ($1, $2, $3, $4)
            """.trimIndent(),
            listOf(3, 1, "value", 0),
        )
    }
    assertEquals(ConstraintKind.CHECK, check.kind)
    assertEquals("23514", check.metadata.sqlState)
}

private suspend fun verifyQueryContracts(database: NodePostgresDatabase, fixture: ContractFixture) {
    assertEquals(2, database.select(ContractRows.id).from(ContractRows).stream().toList().size)
    assertFailsWith<ExpectedOneRowButFoundMultiple> {
        database.select(ContractRows.id).from(ContractRows).one()
    }
    val raw = database.queryRaw(
        "SELECT name, active, payload FROM kagiroi_node_postgres_contract WHERE id = $1",
        listOf(fixture.firstId),
    ).toList().single()
    assertEquals("Ada", raw.string("name"))
    assertEquals(true, raw["active"])
    assertContentEquals(fixture.payload, raw.bytes("payload"))

    val delete = database.deleteFrom(ContractRows).where(ContractRows.id eq fixture.secondId).execute()
    assertEquals(1, delete.affectedRows)
    assertNull(
        database.select(ContractRows.id).from(ContractRows).where(ContractRows.id eq fixture.secondId).oneOrNull(),
    )
}

private suspend fun verifyConcurrentQueries(database: NodePostgresDatabase) = coroutineScope {
    val values = (1..CONCURRENT_QUERY_COUNT).map { value ->
        async {
            database.queryRaw("SELECT $1::INTEGER AS value", listOf(value)).toList().single().long("value")
        }
    }.awaitAll()
    assertEquals((1L..CONCURRENT_QUERY_COUNT.toLong()).toList(), values)
}

private data class ContractFixture(
    val firstId: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
    val secondId: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174001"),
    val payload: ByteArray = byteArrayOf(0, 1, Byte.MAX_VALUE, -1),
    val instant: Instant = Instant.parse("2026-10-18T01:02:03.123456Z"),
    val duration: Duration = Duration.parseIsoString("-PT900H0.000000001S"),
    val date: LocalDate = LocalDate.parse("2026-10-18"),
    val time: LocalTime = LocalTime.parse("01:02:03.123456"),
    val dateTime: LocalDateTime = LocalDateTime.parse("2026-10-18T01:02:03.123456"),
    val offset: UtcOffset = UtcOffset.parse("+09:00"),
    val timeZone: TimeZone = TimeZone.of("Asia/Tokyo"),
    val integer: BigInteger = BigInteger.parse("123456789012345678901234567890"),
    val decimal: BigDecimal = BigDecimal.parse("1234567890.123456789"),
    val byteValue: Byte = Byte.MIN_VALUE,
    val shortValue: Short = Short.MIN_VALUE,
    val intValue: Int = Int.MIN_VALUE,
    val longValue: Long = Long.MIN_VALUE,
    val ulongValue: ULong = ULong.MAX_VALUE,
    val floatValue: Float = 1.25f,
    val doubleValue: Double = 1.234567890123,
)

private suspend fun SqlExecution.insertContractRow(id: Uuid, name: String) {
    insertInto(ContractRows).values {
        set(ContractRows.id, id)
        set(ContractRows.name, name)
        set(ContractRows.active, false)
        set(ContractRows.payload, byteArrayOf())
        set(ContractRows.nickname, null)
        set(ContractRows.instantValue, Instant.parse("2026-10-18T00:00:00Z"))
        set(ContractRows.durationValue, Duration.ZERO)
        set(ContractRows.dateValue, LocalDate.parse("2026-10-18"))
        set(ContractRows.timeValue, LocalTime.parse("00:00:00"))
        set(ContractRows.dateTimeValue, LocalDateTime.parse("2026-10-18T00:00:00"))
        set(ContractRows.offsetValue, UtcOffset.ZERO)
        set(ContractRows.timeZoneValue, TimeZone.UTC)
        set(ContractRows.integerValue, BigInteger.ONE)
        set(ContractRows.decimalValue, BigDecimal.ONE)
        set(ContractRows.byteValue, 1.toByte())
        set(ContractRows.shortValue, 1.toShort())
        set(ContractRows.intValue, 1)
        set(ContractRows.longValue, 1L)
        set(ContractRows.ulongValue, 1uL)
        set(ContractRows.floatValue, 1.0f)
        set(ContractRows.doubleValue, 1.0)
    }.execute()
}

private object ContractRows : Table("kagiroi_node_postgres_contract") {
    val id = uuid("id")
    val name = string("name")
    val active = boolean("active")
    val payload = bytes("payload")
    val nickname = string("nickname").nullable()
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val dateValue = localDate("date_value")
    val timeValue = localTime("time_value")
    val dateTimeValue = localDateTime("date_time_value")
    val offsetValue = utcOffset("offset_value")
    val timeZoneValue = timeZone("time_zone_value")
    val integerValue = bigInteger("integer_value")
    val decimalValue = bigDecimal("decimal_value")
    val byteValue = byte("byte_value")
    val shortValue = short("short_value")
    val intValue = int("int_value")
    val longValue = long("long_value")
    val ulongValue = ulong("ulong_value")
    val floatValue = float("float_value")
    val doubleValue = double("double_value")
}

private object TypedDdlRows : Table("kagiroi_node_postgres_typed_ddl") {
    val id = int("id").primaryKey()
    val stringValue = string("string_value")
    val durationValue = duration("duration_value")
    val offsetValue = utcOffset("offset_value")
    val timeZoneValue = timeZone("time_zone_value")
}

private object CommentedRows : Table("kagiroi_node_postgres_comments", comment = "Application users") {
    val id = int("id")
    val email = string("email").comment("Primary email address")
}

private val contractTableDdl =
    """
    CREATE TABLE kagiroi_node_postgres_contract (
        id UUID PRIMARY KEY,
        name TEXT NOT NULL UNIQUE,
        active BOOLEAN NOT NULL,
        payload BYTEA NOT NULL,
        nickname TEXT,
        instant_value TIMESTAMP WITH TIME ZONE NOT NULL,
        duration_value TEXT NOT NULL,
        date_value DATE NOT NULL,
        time_value TIME NOT NULL,
        date_time_value TIMESTAMP NOT NULL,
        offset_value TEXT NOT NULL,
        time_zone_value TEXT NOT NULL,
        integer_value NUMERIC(40, 0) NOT NULL,
        decimal_value NUMERIC(40, 18) NOT NULL,
        byte_value SMALLINT NOT NULL,
        short_value SMALLINT NOT NULL,
        int_value INTEGER NOT NULL,
        long_value BIGINT NOT NULL,
        ulong_value NUMERIC(20, 0) NOT NULL,
        float_value REAL NOT NULL,
        double_value DOUBLE PRECISION NOT NULL
    )
    """.trimIndent()

private const val CONTRACT_TABLE_DROP = "DROP TABLE IF EXISTS kagiroi_node_postgres_contract"
private const val CONCURRENT_QUERY_COUNT = 8
private val postgresCommonContract = SqlDatabaseContractConfiguration(
    rawSelectByIdSql = "SELECT name, payload FROM kagiroi_sql_contract WHERE id = $1",
    uniqueViolationSqlState = "23505",
)
