@file:Suppress("LongMethod")

package dev.quwac.kagiroi.oracle.node

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.kotlinx.datetime.localDate
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.oracle.hypotheticalCumeDist
import dev.quwac.kagiroi.oracle.hypotheticalDenseRank
import dev.quwac.kagiroi.oracle.hypotheticalPercentRank
import dev.quwac.kagiroi.oracle.hypotheticalRank
import dev.quwac.kagiroi.oracle.ignoreNulls
import dev.quwac.kagiroi.oracle.percentileCont
import dev.quwac.kagiroi.oracle.percentileDisc
import dev.quwac.kagiroi.oracle.respectNulls
import dev.quwac.kagiroi.oracle.returningInto
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.quwac.bignumber.bigInteger
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.between
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.currentRow
import dev.quwac.kagiroi.sql.deleteAllFrom
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.castDistinctCaseExpressionContract
import dev.quwac.kagiroi.sql.testing.savepointContract
import dev.quwac.kagiroi.sql.unboundedPreceding
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal suspend fun oracleIntegrationContract(database: NodeOracleDatabase) {
    try {
        dropOracleTables(database)
        database.execute(createTable(OracleContractRows, ifNotExists = true))
        database.execute(
            createIndex("kagiroi_oracle_lower_name_idx", OracleContractRows) {
                on(lower(OracleContractRows.name))
            },
        )
        assertEquals(
            1L,
            database.queryRaw(
                "SELECT COUNT(*) AS \"kagiroi_count\" FROM USER_INDEXES " +
                    "WHERE index_name = 'kagiroi_oracle_lower_name_idx'",
            ).toList().single().long("kagiroi_count"),
        )
        castDistinctCaseExpressionContract(database)
        verifyAdvancedAnalyticalSql(database)
        verifyGeneratedValues(database)
        verifySchemaComments(database)
        database.executeRaw(
            "CREATE TABLE \"kagiroi_oracle_child\" (\"id\" NUMBER(19,0) PRIMARY KEY, " +
                "\"parent_id\" NUMBER(19,0) REFERENCES \"kagiroi_oracle_contract\" (\"id\"))",
        )

        assertFailsWith<InvalidBinding> {
            database.execute(
                insertInto(OracleContractRows).values {
                    set(OracleContractRows.id, 1L)
                    set(OracleContractRows.name, "")
                    set(OracleContractRows.amount, BigDecimal.parse("1.0"))
                    set(OracleContractRows.largeInteger, BigInteger.parse("1"))
                    set(OracleContractRows.identifier, CONTRACT_UUID)
                    set(OracleContractRows.payload, byteArrayOf(1))
                    set(OracleContractRows.createdDate, CONTRACT_DATE)
                    set(OracleContractRows.createdAt, CONTRACT_LOCAL_DATE_TIME)
                    set(OracleContractRows.createdInstant, CONTRACT_INSTANT)
                    set(OracleContractRows.active, true)
                },
            )
        }

        database.execute(
            insertInto(OracleContractRows).values {
                set(OracleContractRows.id, 1L)
                set(OracleContractRows.name, "Ada")
                set(OracleContractRows.optionalName, null)
                set(OracleContractRows.amount, CONTRACT_AMOUNT)
                set(OracleContractRows.largeInteger, CONTRACT_INTEGER)
                set(OracleContractRows.identifier, CONTRACT_UUID)
                set(OracleContractRows.payload, CONTRACT_BYTES)
                set(OracleContractRows.createdDate, CONTRACT_DATE)
                set(OracleContractRows.createdAt, CONTRACT_LOCAL_DATE_TIME)
                set(OracleContractRows.createdInstant, CONTRACT_INSTANT)
                set(OracleContractRows.active, true)
            },
        )

        val row = database.select(
            OracleContractRows.id,
            OracleContractRows.name,
            OracleContractRows.optionalName,
            OracleContractRows.amount,
            OracleContractRows.largeInteger,
            OracleContractRows.identifier,
            OracleContractRows.payload,
            OracleContractRows.createdDate,
            OracleContractRows.createdAt,
            OracleContractRows.createdInstant,
            OracleContractRows.active,
        ).from(OracleContractRows).where(OracleContractRows.id eq 1L).one()
        assertEquals("Ada", row[OracleContractRows.name])
        assertNull(row[OracleContractRows.optionalName])
        assertEquals(CONTRACT_AMOUNT, row[OracleContractRows.amount])
        assertEquals(CONTRACT_INTEGER, row[OracleContractRows.largeInteger])
        assertEquals(CONTRACT_UUID, row[OracleContractRows.identifier])
        assertContentEquals(CONTRACT_BYTES, row[OracleContractRows.payload])
        assertEquals(CONTRACT_DATE, row[OracleContractRows.createdDate])
        assertEquals(CONTRACT_LOCAL_DATE_TIME, row[OracleContractRows.createdAt])
        assertEquals(CONTRACT_INSTANT, row[OracleContractRows.createdInstant])

        val updated = database.transaction {
            update(OracleContractRows) { set(OracleContractRows.name, "Grace") }
                .where(OracleContractRows.id eq 1L)
                .returningInto(OracleContractRows.id, OracleContractRows.name)
                .one()
        }
        assertEquals(1L, updated[OracleContractRows.id])
        assertEquals("Grace", updated[OracleContractRows.name])
        assertEquals(
            "Grace",
            database.select(
                OracleContractRows.name,
            ).from(OracleContractRows).where(OracleContractRows.id eq 1L).one()[OracleContractRows.name],
        )

        val returningId = 2L
        val inserted = database.insertInto(OracleContractRows).values {
            set(OracleContractRows.id, returningId)
            set(OracleContractRows.name, "Returned")
            set(OracleContractRows.optionalName, null)
            set(OracleContractRows.amount, CONTRACT_AMOUNT)
            set(OracleContractRows.largeInteger, CONTRACT_INTEGER)
            set(OracleContractRows.identifier, CONTRACT_UUID)
            set(OracleContractRows.payload, CONTRACT_BYTES)
            set(OracleContractRows.createdDate, CONTRACT_DATE)
            set(OracleContractRows.createdAt, CONTRACT_LOCAL_DATE_TIME)
            set(OracleContractRows.createdInstant, CONTRACT_INSTANT)
            set(OracleContractRows.active, true)
        }.returningInto(OracleContractRows.id, OracleContractRows.name).one()
        assertEquals(returningId, inserted[OracleContractRows.id])
        assertEquals("Returned", inserted[OracleContractRows.name])
        val deleted = database.deleteFrom(OracleContractRows).where(OracleContractRows.id eq returningId)
            .returningInto(OracleContractRows.id, OracleContractRows.name)
            .one()
        assertEquals(returningId, deleted[OracleContractRows.id])
        assertEquals("Returned", deleted[OracleContractRows.name])
        assertFailsWith<ConstraintViolation> {
            database.execute(
                insertInto(OracleContractRows).values {
                    set(OracleContractRows.id, 1L)
                    set(OracleContractRows.name, "duplicate")
                    set(OracleContractRows.amount, CONTRACT_AMOUNT)
                    set(OracleContractRows.largeInteger, CONTRACT_INTEGER)
                    set(OracleContractRows.identifier, CONTRACT_UUID)
                    set(OracleContractRows.payload, CONTRACT_BYTES)
                    set(OracleContractRows.createdDate, CONTRACT_DATE)
                    set(OracleContractRows.createdAt, CONTRACT_LOCAL_DATE_TIME)
                    set(OracleContractRows.createdInstant, CONTRACT_INSTANT)
                    set(OracleContractRows.active, false)
                },
            )
        }.also { assertEquals(ConstraintKind.UNIQUE, it.kind) }
        assertFailsWith<ConstraintViolation> {
            database.executeRaw(
                "INSERT INTO \"kagiroi_oracle_child\" (\"id\", \"parent_id\") VALUES (:p1, :p2)",
                listOf(1L, 99L),
            )
        }.also { assertEquals(ConstraintKind.FOREIGN_KEY, it.kind) }

        database.transaction {
            executeRaw(
                "INSERT INTO \"kagiroi_oracle_child\" (\"id\", \"parent_id\") VALUES (:p1, :p2)",
                listOf(2L, 1L),
            )
        }
        assertEquals(1L, rawCount(database, "kagiroi_oracle_child"))
        assertFailsWith<IllegalStateException> {
            database.transaction {
                executeRaw(
                    "INSERT INTO \"kagiroi_oracle_child\" (\"id\", \"parent_id\") VALUES (:p1, :p2)",
                    listOf(3L, 1L),
                )
                error("rollback")
            }
        }
        assertEquals(1L, rawCount(database, "kagiroi_oracle_child"))

        savepointContract(database)

        val page = database.select(OracleContractRows.id).from(OracleContractRows)
            .orderBy(OracleContractRows.id.asc()).limit(1).one()
        assertEquals(1L, page[OracleContractRows.id])
        assertEquals(
            "bound",
            database.queryRaw("SELECT :p1 AS \"value\" FROM dual", listOf("bound")).toList().single().string("value"),
        )
        coroutineScope {
            List(4) { async { rawCount(database, "kagiroi_oracle_contract") } }
                .awaitAll()
                .forEach { assertEquals(1L, it) }
        }
        database.executeRaw("DELETE FROM \"kagiroi_oracle_child\"")
        database.execute(deleteAllFrom(OracleContractRows))
        assertEquals(0, database.select(OracleContractRows.id).from(OracleContractRows).all().size)
    } finally {
        dropOracleTables(database)
        database.close()
    }
    assertFailsWith<DatabaseClosed> { database.executeRaw("SELECT 1 FROM dual") }
}

private suspend fun rawCount(database: NodeOracleDatabase, table: String): Long =
    requireNotNull(database.queryRaw("SELECT COUNT(*) AS \"count\" FROM \"$table\"").toList().single().long("count"))

private suspend fun dropOracleTables(database: NodeOracleDatabase) {
    try {
        database.execute(dropTable(OracleAdvancedAnalyticalRows, ifExists = true))
    } catch (_: Throwable) {
        // The disposable analytical table may not exist yet.
    }
    try {
        database.executeRaw("DROP TABLE \"kagiroi_oracle_child\"")
    } catch (_: Throwable) {
        // The disposable constraint table may not exist yet.
    }
    try {
        database.execute(dropTable(OracleGeneratedRows, ifExists = true))
    } catch (_: Throwable) {
        // The disposable generated-values table may not exist yet.
    }
    try {
        database.execute(dropTable(OracleContractRows, ifExists = true))
    } catch (_: Throwable) {
        // The disposable contract table may not exist yet.
    }
    try {
        database.execute(dropTable(OracleCommentedRows, ifExists = true))
    } catch (_: Throwable) {
        // The disposable comment table may not exist yet.
    }
}

private suspend fun verifyAdvancedAnalyticalSql(database: NodeOracleDatabase) {
    database.execute(createTable(OracleAdvancedAnalyticalRows))
    listOf(
        OracleAnalyticalFixture(10.0, 10.0, 10, 1),
        OracleAnalyticalFixture(20.0, 20.0, null, 2),
        OracleAnalyticalFixture(20.0, 30.0, 20, 3),
        OracleAnalyticalFixture(40.0, 40.0, null, 4),
        OracleAnalyticalFixture(50.0, 50.0, 30, 5),
    ).forEach { fixture ->
        database.insertInto(OracleAdvancedAnalyticalRows).values {
            set(OracleAdvancedAnalyticalRows.id, fixture.id)
            set(OracleAdvancedAnalyticalRows.score, fixture.score)
            set(OracleAdvancedAnalyticalRows.amount, fixture.amount)
            set(OracleAdvancedAnalyticalRows.value, fixture.value)
        }.execute()
    }

    val percentRank = percentRank().over { orderBy(OracleAdvancedAnalyticalRows.score.asc()) }.alias("percent_rank")
    val cumeDist = cumeDist().over { orderBy(OracleAdvancedAnalyticalRows.score.asc()) }.alias("cume_dist")
    val continuous = percentileCont(0.5).withinGroup(OracleAdvancedAnalyticalRows.amount.asc()).alias("continuous")
    val discrete = percentileDisc(0.5).withinGroup(OracleAdvancedAnalyticalRows.amount.asc()).alias("discrete")
    val rank = hypotheticalRank(25.0).withinGroup(OracleAdvancedAnalyticalRows.amount.asc()).alias("rank")
    val denseRank = hypotheticalDenseRank(
        25.0,
    ).withinGroup(OracleAdvancedAnalyticalRows.amount.asc()).alias("dense_rank")
    val hypotheticalPercentRank = hypotheticalPercentRank(25.0)
        .withinGroup(OracleAdvancedAnalyticalRows.amount.asc())
        .alias("hypothetical_percent_rank")
    val hypotheticalCumeDist = hypotheticalCumeDist(25.0)
        .withinGroup(OracleAdvancedAnalyticalRows.amount.asc())
        .alias("hypothetical_cume_dist")
    val lastRespectingNulls = lastValue(OracleAdvancedAnalyticalRows.value)
        .respectNulls()
        .over {
            orderBy(OracleAdvancedAnalyticalRows.id.asc())
            rows(between(unboundedPreceding, currentRow))
        }
        .alias("last_respecting_nulls")
    val lastIgnoringNulls = lastValue(OracleAdvancedAnalyticalRows.value)
        .ignoreNulls()
        .over {
            orderBy(OracleAdvancedAnalyticalRows.id.asc())
            rows(between(unboundedPreceding, currentRow))
        }
        .alias("last_ignoring_nulls")

    val rows = database.select(percentRank, cumeDist, lastRespectingNulls, lastIgnoringNulls)
        .from(OracleAdvancedAnalyticalRows)
        .orderBy(OracleAdvancedAnalyticalRows.id.asc())
        .all()
    assertEquals(listOf(0.0, 0.25, 0.25, 0.75, 1.0), rows.map { it[percentRank] })
    assertEquals(listOf(0.2, 0.6, 0.6, 0.8, 1.0), rows.map { it[cumeDist] })
    assertEquals(listOf(10, null, 20, null, 30), rows.map { it[lastRespectingNulls] })
    assertEquals(listOf(10, 10, 20, 20, 30), rows.map { it[lastIgnoringNulls] })

    val aggregate = database.select(
        continuous,
        discrete,
        rank,
        denseRank,
        hypotheticalPercentRank,
        hypotheticalCumeDist,
    ).from(OracleAdvancedAnalyticalRows).one()
    assertEquals(30.0, aggregate[continuous])
    assertEquals(30.0, aggregate[discrete])
    assertEquals(3L, aggregate[rank])
    assertEquals(3L, aggregate[denseRank])
    assertEquals(0.4, aggregate[hypotheticalPercentRank])
    assertEquals(0.5, aggregate[hypotheticalCumeDist])
}

private object OracleContractRows : Table("kagiroi_oracle_contract") {
    val id = long("id").primaryKey()
    val name = string("name")
    val optionalName = string("optional_name").nullable()
    val amount = bigDecimal("amount")
    val largeInteger = bigInteger("large_integer")
    val identifier = uuid("identifier")
    val payload = bytes("payload")
    val createdDate = localDate("created_date")
    val createdAt = localDateTime("created_at")
    val createdInstant = instant("created_instant")
    val active = boolean("active")
}

private object OracleAdvancedAnalyticalRows : Table("kagiroi_oracle_advanced_analytical") {
    val id = int("id").primaryKey()
    val score = double("score")
    val amount = double("amount")
    val value = int("value").nullable()
}

private data class OracleAnalyticalFixture(val score: Double, val amount: Double, val value: Int?, val id: Int)

private object OracleGeneratedRows : Table("kagiroi_oracle_generated_values") {
    val id = long("id").primaryKey().identity(startWith = 100L, incrementBy = 10L)
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Stored)
}

private suspend fun verifyGeneratedValues(database: NodeOracleDatabase) {
    database.execute(createTable(OracleGeneratedRows))
    repeat(3) {
        database.insertInto(OracleGeneratedRows).values {
            set(OracleGeneratedRows.price, 6)
            set(OracleGeneratedRows.quantity, 7)
        }.execute()
    }

    val rows = database.select(OracleGeneratedRows.id, OracleGeneratedRows.total)
        .from(OracleGeneratedRows)
        .orderBy(OracleGeneratedRows.id.asc())
        .all()
    assertEquals(listOf(100L, 110L, 120L), rows.map { it[OracleGeneratedRows.id] })
    assertEquals(listOf(42, 42, 42), rows.map { it[OracleGeneratedRows.total] })
}

private suspend fun verifySchemaComments(database: NodeOracleDatabase) {
    database.execute(createTable(OracleCommentedRows))

    assertEquals(
        "Application users",
        database.queryRaw(
            "SELECT comments AS \"comment\" FROM USER_TAB_COMMENTS " +
                "WHERE table_name = 'kagiroi_oracle_comments'",
        ).toList().single().string("comment"),
    )
    assertEquals(
        "Primary email address",
        database.queryRaw(
            "SELECT comments AS \"comment\" FROM USER_COL_COMMENTS " +
                "WHERE table_name = 'kagiroi_oracle_comments' AND column_name = 'email'",
        ).toList().single().string("comment"),
    )
}

private object OracleCommentedRows : Table("kagiroi_oracle_comments", comment = "Application users") {
    val id = long("id")
    val email = string("email").comment("Primary email address")
}

private val CONTRACT_UUID: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
private val CONTRACT_BYTES = byteArrayOf(0, 1, -1)
private val CONTRACT_AMOUNT: BigDecimal = BigDecimal.parse("123456789012.123456789012345678")
private val CONTRACT_INTEGER: BigInteger = BigInteger.parse("99999999999999999999999999999999999999")
private val CONTRACT_DATE: LocalDate = LocalDate.parse("2040-01-02")
private val CONTRACT_LOCAL_DATE_TIME: LocalDateTime = LocalDateTime.parse("2040-01-02T03:04:05.123456789")
private val CONTRACT_INSTANT: Instant = Instant.parse("2040-01-02T03:04:05.123456789Z")
