@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package dev.quwac.kagiroi.sqlite.file

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
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.ExpectedOneRowButFoundZero
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.LockTimeoutFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.SqlDatabaseContractConfiguration
import dev.quwac.kagiroi.sql.testing.returningContract
import dev.quwac.kagiroi.sql.testing.savepointContract
import dev.quwac.kagiroi.sql.testing.sqlDatabaseContract
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sqlite.kotlinx.datetime.SqliteKotlinxDateTime
import dev.quwac.kagiroi.sqlite.quwac.bignumber.SqliteQuwacBigNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Real-file compatibility contract shared by Kotlin/JS and Kotlin/Wasm Node test entry points. */
internal suspend fun fileSqliteDriverContract(path: String) {
    val database = openDatabase(path)
    try {
        sqlDatabaseContract(database, sqliteCommonContract)
        verifyConfiguredCapabilities(database)
        verifyNativeValuesAndExtensions(database)
        verifyRawSqlAndCardinality(database)
        verifyConstraintMapping(database)
        verifyTransactionSemantics(database)
        savepointContract(database)
        returningContract(database)
        verifyOperationSerialization(database)
        verifyBusyTimeout(path)
        verifyPersistenceAndClosedState(database, path)
        verifyReadOnlyOpen(path)
        verifyFileMustExist(path)
    } finally {
        database.close()
    }
}

private fun openDatabase(path: String): FileSqliteDatabase = FileSqliteDatabase(path) {
    busyTimeout = 5.seconds
    install(SqliteKotlinxDateTime)
    install(SqliteQuwacBigNumber)
}

private fun verifyConfiguredCapabilities(database: FileSqliteDatabase) {
    assertNotNull(database.capability(SqlCapabilities.Sql))
    assertNotNull(database.capability(SqlCapabilities.Transactions))
    assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
}

private suspend fun verifyNativeValuesAndExtensions(database: FileSqliteDatabase) {
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_file_sqlite_values")
    database.executeRaw(
        """
        CREATE TABLE kagiroi_file_sqlite_values (
            id INTEGER PRIMARY KEY,
            text_value TEXT NOT NULL,
            boolean_value INTEGER NOT NULL,
            byte_value INTEGER NOT NULL,
            short_value INTEGER NOT NULL,
            int_value INTEGER NOT NULL,
            long_value INTEGER NOT NULL,
            ulong_value INTEGER NOT NULL,
            float_value REAL NOT NULL,
            double_value REAL NOT NULL,
            blob_value BLOB NOT NULL,
            uuid_value TEXT NOT NULL,
            nullable_value TEXT,
            instant_value TEXT NOT NULL,
            date_value TEXT NOT NULL,
            time_value TEXT NOT NULL,
            date_time_value TEXT NOT NULL,
            offset_value TEXT NOT NULL,
            zone_value TEXT NOT NULL,
            integer_value TEXT NOT NULL,
            decimal_value TEXT NOT NULL
        )
        """.trimIndent(),
    )
    val id = 1L
    val blob = byteArrayOf(0, 1, Byte.MAX_VALUE, -1)
    val uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
    val instant = Instant.parse("2026-10-20T01:02:03.123456789Z")
    val date = LocalDate.parse("2026-10-20")
    val time = LocalTime.parse("01:02:03.123456789")
    val dateTime = LocalDateTime.parse("2026-10-20T01:02:03.123456789")
    val offset = UtcOffset.parse("+09:00")
    val zone = TimeZone.of("Asia/Tokyo")
    val integer = BigInteger.parse("1234567890123456789012345678901234567890")
    val decimal = BigDecimal.parse("-12345678901234567890.1234567890123456789")

    database.insertInto(FileSqliteValues).values {
        set(FileSqliteValues.id, id)
        set(FileSqliteValues.textValue, "Ada")
        set(FileSqliteValues.booleanValue, true)
        set(FileSqliteValues.byteValue, Byte.MIN_VALUE)
        set(FileSqliteValues.shortValue, Short.MIN_VALUE)
        set(FileSqliteValues.intValue, Int.MIN_VALUE)
        set(FileSqliteValues.longValue, Long.MIN_VALUE)
        set(FileSqliteValues.ulongValue, Long.MAX_VALUE.toULong())
        set(FileSqliteValues.floatValue, 1.25f)
        set(FileSqliteValues.doubleValue, 1.234567890123)
        set(FileSqliteValues.blobValue, blob)
        set(FileSqliteValues.uuidValue, uuid)
        set(FileSqliteValues.nullableValue, null)
        set(FileSqliteValues.instantValue, instant)
        set(FileSqliteValues.dateValue, date)
        set(FileSqliteValues.timeValue, time)
        set(FileSqliteValues.dateTimeValue, dateTime)
        set(FileSqliteValues.offsetValue, offset)
        set(FileSqliteValues.zoneValue, zone)
        set(FileSqliteValues.integerValue, integer)
        set(FileSqliteValues.decimalValue, decimal)
    }.execute()

    val row = database.select(
        FileSqliteValues.booleanValue,
        FileSqliteValues.byteValue,
        FileSqliteValues.shortValue,
        FileSqliteValues.intValue,
        FileSqliteValues.longValue,
        FileSqliteValues.ulongValue,
        FileSqliteValues.floatValue,
        FileSqliteValues.doubleValue,
        FileSqliteValues.blobValue,
        FileSqliteValues.uuidValue,
        FileSqliteValues.nullableValue,
        FileSqliteValues.instantValue,
        FileSqliteValues.dateValue,
        FileSqliteValues.timeValue,
        FileSqliteValues.dateTimeValue,
        FileSqliteValues.offsetValue,
        FileSqliteValues.zoneValue,
        FileSqliteValues.integerValue,
        FileSqliteValues.decimalValue,
    ).from(FileSqliteValues).where(FileSqliteValues.id eq id).one()
    assertTrue(row[FileSqliteValues.booleanValue])
    assertEquals(Byte.MIN_VALUE, row[FileSqliteValues.byteValue])
    assertEquals(Short.MIN_VALUE, row[FileSqliteValues.shortValue])
    assertEquals(Int.MIN_VALUE, row[FileSqliteValues.intValue])
    assertEquals(Long.MIN_VALUE, row[FileSqliteValues.longValue])
    assertEquals(Long.MAX_VALUE.toULong(), row[FileSqliteValues.ulongValue])
    assertEquals(1.25f, row[FileSqliteValues.floatValue])
    assertEquals(1.234567890123, row[FileSqliteValues.doubleValue])
    assertContentEquals(blob, row[FileSqliteValues.blobValue])
    assertEquals(uuid, row[FileSqliteValues.uuidValue])
    assertNull(row[FileSqliteValues.nullableValue])
    assertEquals(instant, row[FileSqliteValues.instantValue])
    assertEquals(date, row[FileSqliteValues.dateValue])
    assertEquals(time, row[FileSqliteValues.timeValue])
    assertEquals(dateTime, row[FileSqliteValues.dateTimeValue])
    assertEquals(offset, row[FileSqliteValues.offsetValue])
    assertEquals(zone, row[FileSqliteValues.zoneValue])
    assertEquals(integer, row[FileSqliteValues.integerValue])
    assertEquals(decimal, row[FileSqliteValues.decimalValue])

    assertFailsWith<InvalidBinding> {
        database.insertInto(FileSqliteValues).values {
            set(FileSqliteValues.id, 2L)
            set(FileSqliteValues.textValue, "overflow")
            set(FileSqliteValues.booleanValue, false)
            set(FileSqliteValues.byteValue, 0)
            set(FileSqliteValues.shortValue, 0)
            set(FileSqliteValues.intValue, 0)
            set(FileSqliteValues.longValue, 0L)
            set(FileSqliteValues.ulongValue, ULong.MAX_VALUE)
            set(FileSqliteValues.floatValue, 0f)
            set(FileSqliteValues.doubleValue, 0.0)
            set(FileSqliteValues.blobValue, byteArrayOf())
            set(FileSqliteValues.uuidValue, uuid)
            set(FileSqliteValues.nullableValue, null)
            set(FileSqliteValues.instantValue, instant)
            set(FileSqliteValues.dateValue, date)
            set(FileSqliteValues.timeValue, time)
            set(FileSqliteValues.dateTimeValue, dateTime)
            set(FileSqliteValues.offsetValue, offset)
            set(FileSqliteValues.zoneValue, zone)
            set(FileSqliteValues.integerValue, BigInteger.ZERO)
            set(FileSqliteValues.decimalValue, BigDecimal.ZERO)
        }.execute()
    }
}

private suspend fun verifyRawSqlAndCardinality(database: FileSqliteDatabase) {
    val dangerous = "Robert'); DROP TABLE kagiroi_file_sqlite_values; --"
    val result = database.executeRaw(
        "UPDATE kagiroi_file_sqlite_values SET text_value = ? WHERE id = ?",
        listOf(dangerous, 1L),
    )
    assertEquals(1L, result.affectedRows)
    val raw = database.queryRaw(
        "SELECT text_value, blob_value, long_value FROM kagiroi_file_sqlite_values WHERE id = ?",
        listOf(1L),
    ).toList().single()
    assertEquals(dangerous, raw.string("text_value"))
    assertContentEquals(byteArrayOf(0, 1, Byte.MAX_VALUE, -1), raw.bytes("blob_value"))
    assertEquals(Long.MIN_VALUE, raw.long("long_value"))
    assertFailsWith<ExpectedOneRowButFoundZero> {
        database.select(FileSqliteValues.id).from(FileSqliteValues).where(FileSqliteValues.id eq -1L).one()
    }
    assertNull(
        database.select(FileSqliteValues.id).from(FileSqliteValues).where(FileSqliteValues.id eq -1L).oneOrNull(),
    )
}

private suspend fun verifyConstraintMapping(database: FileSqliteDatabase) {
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_file_sqlite_child")
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_file_sqlite_parent")
    database.executeRaw("PRAGMA foreign_keys = ON")
    database.executeRaw("CREATE TABLE kagiroi_file_sqlite_parent (id INTEGER PRIMARY KEY)")
    database.executeRaw(
        """
        CREATE TABLE kagiroi_file_sqlite_child (
            id INTEGER PRIMARY KEY,
            parent_id INTEGER NOT NULL REFERENCES kagiroi_file_sqlite_parent(id),
            required_value TEXT NOT NULL,
            positive_value INTEGER NOT NULL CHECK (positive_value > 0),
            unique_value TEXT UNIQUE
        )
        """.trimIndent(),
    )
    database.executeRaw("INSERT INTO kagiroi_file_sqlite_parent (id) VALUES (?)", listOf(1L))
    database.executeRaw(
        "INSERT INTO kagiroi_file_sqlite_child (id, parent_id, required_value, positive_value, unique_value) VALUES (?, ?, ?, ?, ?)",
        listOf(1L, 1L, "value", 1L, "unique"),
    )
    assertConstraint(database, listOf(2L, 1L, "value", 1L, "unique"), ConstraintKind.UNIQUE, "SQLITE_CONSTRAINT_UNIQUE")
    assertConstraint(
        database,
        listOf(3L, 2L, "value", 1L, "foreign"),
        ConstraintKind.FOREIGN_KEY,
        "SQLITE_CONSTRAINT_FOREIGNKEY",
    )
    assertConstraint(
        database,
        listOf(4L, 1L, null, 1L, "not-null"),
        ConstraintKind.NOT_NULL,
        "SQLITE_CONSTRAINT_NOTNULL",
    )
    assertConstraint(database, listOf(5L, 1L, "value", 0L, "check"), ConstraintKind.CHECK, "SQLITE_CONSTRAINT_CHECK")
}

private suspend fun assertConstraint(
    database: FileSqliteDatabase,
    values: List<Any?>,
    kind: ConstraintKind,
    vendorCode: String,
) {
    val failure = assertFailsWith<ConstraintViolation> {
        database.executeRaw(
            "INSERT INTO kagiroi_file_sqlite_child (id, parent_id, required_value, positive_value, unique_value) VALUES (?, ?, ?, ?, ?)",
            values,
        )
    }
    assertEquals(kind, failure.kind)
    assertEquals(vendorCode, failure.metadata.vendorCode)
}

private suspend fun verifyTransactionSemantics(database: FileSqliteDatabase) = coroutineScope {
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_file_sqlite_transactions")
    database.executeRaw("CREATE TABLE kagiroi_file_sqlite_transactions (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
    database.transaction {
        executeRaw("INSERT INTO kagiroi_file_sqlite_transactions (id, value) VALUES (?, ?)", listOf(1L, "commit"))
    }
    assertFailsWith<IllegalStateException> {
        database.transaction {
            executeRaw(
                "INSERT INTO kagiroi_file_sqlite_transactions (id, value) VALUES (?, ?)",
                listOf(2L, "rollback"),
            )
            error("rollback")
        }
    }
    assertNull(
        database.queryRaw(
            "SELECT value FROM kagiroi_file_sqlite_transactions WHERE id = ?",
            listOf(2L),
        ).toList().singleOrNull(),
    )

    val entered = CompletableDeferred<Unit>()
    val cancelled = launch {
        database.transaction {
            executeRaw("INSERT INTO kagiroi_file_sqlite_transactions (id, value) VALUES (?, ?)", listOf(3L, "cancel"))
            entered.complete(Unit)
            awaitCancellation()
        }
    }
    entered.await()
    cancelled.cancelAndJoin()
    assertNull(
        database.queryRaw(
            "SELECT value FROM kagiroi_file_sqlite_transactions WHERE id = ?",
            listOf(3L),
        ).toList().singleOrNull(),
    )
}

private suspend fun verifyOperationSerialization(database: FileSqliteDatabase) = coroutineScope {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val transaction = async {
        database.transaction {
            entered.complete(Unit)
            release.await()
            executeRaw("INSERT INTO kagiroi_file_sqlite_transactions (id, value) VALUES (?, ?)", listOf(4L, "serial"))
        }
    }
    entered.await()
    val attempted = CompletableDeferred<Unit>()
    val outsideQuery = async {
        attempted.complete(Unit)
        database.queryRaw("SELECT count(*) AS value FROM kagiroi_file_sqlite_transactions").toList()
    }
    attempted.await()
    yield()
    assertFalse(outsideQuery.isCompleted)
    release.complete(Unit)
    transaction.await()
    outsideQuery.await()
}

private suspend fun verifyBusyTimeout(path: String) {
    val holder = openDatabase(path)
    val waiting = FileSqliteDatabase(path) {
        busyTimeout = 10.milliseconds
        install(SqliteKotlinxDateTime)
        install(SqliteQuwacBigNumber)
    }
    try {
        holder.executeRaw("BEGIN EXCLUSIVE")
        val failure = assertFailsWith<LockTimeoutFailure> {
            waiting.executeRaw(
                "INSERT INTO kagiroi_file_sqlite_transactions (id, value) VALUES (?, ?)",
                listOf(5L, "busy"),
            )
        }
        assertEquals("SQLITE_BUSY", failure.metadata.vendorCode)
    } finally {
        try {
            holder.executeRaw("ROLLBACK")
        } finally {
            holder.close()
            waiting.close()
        }
    }
}

private suspend fun verifyPersistenceAndClosedState(database: FileSqliteDatabase, path: String) = coroutineScope {
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_file_sqlite_persistence")
    database.executeRaw("CREATE TABLE kagiroi_file_sqlite_persistence (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
    database.executeRaw(
        "INSERT INTO kagiroi_file_sqlite_persistence (id, value) VALUES (?, ?)",
        listOf(1L, "persisted"),
    )
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val transaction = async {
        database.transaction {
            entered.complete(Unit)
            release.await()
            executeRaw(
                "INSERT INTO kagiroi_file_sqlite_persistence (id, value) VALUES (?, ?)",
                listOf(2L, "committed-before-close"),
            )
        }
    }
    entered.await()
    val close = async { database.close() }
    yield()
    assertFalse(close.isCompleted)
    release.complete(Unit)
    transaction.await()
    close.await()
    database.close()
    assertFailsWith<DatabaseClosed> { database.executeRaw("SELECT 1") }

    val reopened = openDatabase(path)
    try {
        assertEquals(
            "persisted",
            reopened.queryRaw("SELECT value FROM kagiroi_file_sqlite_persistence WHERE id = ?", listOf(1L))
                .toList()
                .single()
                .string("value"),
        )
        assertEquals(
            "committed-before-close",
            reopened.queryRaw("SELECT value FROM kagiroi_file_sqlite_persistence WHERE id = ?", listOf(2L))
                .toList()
                .single()
                .string("value"),
        )
    } finally {
        reopened.close()
    }
}

private suspend fun verifyReadOnlyOpen(path: String) {
    val readOnly = FileSqliteDatabase(path) {
        readOnly = true
        fileMustExist = true
        install(SqliteKotlinxDateTime)
        install(SqliteQuwacBigNumber)
    }
    try {
        assertEquals(
            "persisted",
            readOnly.queryRaw("SELECT value FROM kagiroi_file_sqlite_persistence WHERE id = ?", listOf(1L))
                .toList()
                .single()
                .string("value"),
        )
        val failure = assertFailsWith<ExecutionFailure> {
            readOnly.executeRaw(
                "INSERT INTO kagiroi_file_sqlite_persistence (id, value) VALUES (?, ?)",
                listOf(2L, "no"),
            )
        }
        assertEquals("SQLITE_READONLY", failure.metadata.vendorCode)
    } finally {
        readOnly.close()
    }
}

private fun verifyFileMustExist(path: String) {
    assertFailsWith<ConnectionFailure> {
        FileSqliteDatabase("$path.missing") {
            fileMustExist = true
        }
    }
}

private object FileSqliteValues : Table("kagiroi_file_sqlite_values") {
    val id = long("id").primaryKey()
    val textValue = string("text_value")
    val booleanValue = boolean("boolean_value")
    val byteValue = byte("byte_value")
    val shortValue = short("short_value")
    val intValue = int("int_value")
    val longValue = long("long_value")
    val ulongValue = ulong("ulong_value")
    val floatValue = float("float_value")
    val doubleValue = double("double_value")
    val blobValue = bytes("blob_value")
    val uuidValue = uuid("uuid_value")
    val nullableValue = string("nullable_value").nullable()
    val instantValue = instant("instant_value")
    val dateValue = localDate("date_value")
    val timeValue = localTime("time_value")
    val dateTimeValue = localDateTime("date_time_value")
    val offsetValue = utcOffset("offset_value")
    val zoneValue = timeZone("zone_value")
    val integerValue = bigInteger("integer_value")
    val decimalValue = bigDecimal("decimal_value")
}

private val sqliteCommonContract = SqlDatabaseContractConfiguration(
    rawSelectByIdSql = "SELECT name, payload FROM kagiroi_sql_contract WHERE id = ?",
    uniqueViolationSqlState = null,
    uniqueViolationVendorCode = "SQLITE_CONSTRAINT_UNIQUE",
)
