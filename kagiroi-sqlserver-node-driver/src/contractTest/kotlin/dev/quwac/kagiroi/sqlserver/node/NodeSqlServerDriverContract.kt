@file:Suppress("LongMethod", "MagicNumber")

package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.bignumber.BigDecimal
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.OutputCapability
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.between
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.currentRow
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.castDistinctCaseExpressionContract
import dev.quwac.kagiroi.sql.testing.savepointContract
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.unboundedPreceding
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sqlserver.SqlServerTypes
import dev.quwac.kagiroi.sqlserver.deleted
import dev.quwac.kagiroi.sqlserver.ignoreNulls
import dev.quwac.kagiroi.sqlserver.inserted
import dev.quwac.kagiroi.sqlserver.output
import dev.quwac.kagiroi.sqlserver.quwac.bignumber.SqlServerQuwacBigNumber
import dev.quwac.kagiroi.sqlserver.respectNulls
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

internal suspend fun nodeSqlServerDriverContract(database: NodeSqlServerDatabase) {
    try {
        database.reset()
        database.execute(createTable(Rows))
        database.execute(
            createIndex("kb_active_id_idx", Rows) {
                on(Rows.id.desc())
                include(Rows.active)
                where(Rows.active eq true)
            },
        )
        assertEquals(
            1L,
            database.queryRaw(
                "SELECT COUNT(*) AS [kagiroi_count] FROM sys.indexes " +
                    "WHERE object_id = OBJECT_ID(N'[kb_contract]') AND name = N'kb_active_id_idx'",
            ).toList().single().long("kagiroi_count"),
        )
        castDistinctCaseExpressionContract(database)
        verifyAdvancedAnalyticalSql(database)
        verifyGeneratedValues(database)
        verifySchemaComments(database)
        database.executeRaw("CREATE TABLE [kb_parent] ([id] INT NOT NULL PRIMARY KEY)")
        database.executeRaw(
            "CREATE TABLE [kb_child] ([id] INT NOT NULL PRIMARY KEY, [parent_id] INT NOT NULL " +
                "REFERENCES [kb_parent]([id]), [required_value] NVARCHAR(32) NOT NULL)",
        )
        database.executeRaw("INSERT INTO [kb_parent] ([id]) VALUES (1)")

        val first = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        val second = Uuid.parse("123e4567-e89b-12d3-a456-426614174001")
        val decimal = BigDecimal.parse("1234567890.123456789")
        val payload = byteArrayOf(0, 1, Byte.MAX_VALUE, -1)
        database.insertRow(first, "東京 Ada", true, Long.MIN_VALUE, decimal, payload, null)
        assertTrue(database.capability(SqlCapabilities.Output) is OutputCapability)

        val row = database.select(
            Rows.id,
            Rows.name,
            Rows.active,
            Rows.longValue,
            Rows.decimalValue,
            Rows.payload,
            Rows.nickname,
        )
            .from(Rows).where(Rows.id eq first).one()
        assertEquals(first, row[Rows.id])
        assertEquals("東京 Ada", row[Rows.name])
        assertTrue(row[Rows.active])
        assertEquals(Long.MIN_VALUE, row[Rows.longValue])
        assertEquals(0, decimal.compareTo(row[Rows.decimalValue]))
        assertContentEquals(payload, row[Rows.payload])
        assertNull(row[Rows.nickname])

        val afterNickname = inserted(Rows.nickname)
        val beforeNickname = deleted(Rows.nickname)
        val updateOutput = database.transaction {
            update(Rows) { set(Rows.nickname, "更新") }
                .where(Rows.id eq first)
                .output(afterNickname, beforeNickname)
                .one()
        }
        assertEquals("更新", updateOutput[afterNickname])
        assertNull(updateOutput[beforeNickname])
        assertEquals("更新", database.select(Rows.nickname).from(Rows).where(Rows.id eq first).one()[Rows.nickname])

        val outputId = Uuid.parse("123e4567-e89b-12d3-a456-426614174004")
        val insertedId = inserted(Rows.id)
        val insertedName = inserted(Rows.name)
        val insertOutput = database.insertInto(Rows).values {
            set(Rows.id, outputId)
            set(Rows.name, "output")
            set(Rows.active, false)
            set(Rows.longValue, 2)
            set(Rows.decimalValue, BigDecimal.ZERO)
            set(Rows.payload, byteArrayOf())
            set(Rows.nickname, null)
        }.output(insertedId, insertedName).one()
        assertEquals(outputId, insertOutput[insertedId])
        assertEquals("output", insertOutput[insertedName])

        val deletedId = deleted(Rows.id)
        val deletedName = deleted(Rows.name)
        val deleteOutput = database.deleteFrom(Rows).where(Rows.id eq outputId)
            .output(deletedId, deletedName)
            .one()
        assertEquals(outputId, deleteOutput[deletedId])
        assertEquals("output", deleteOutput[deletedName])

        database.transaction { insertRow(second, "committed", false, 1, BigDecimal.ONE, byteArrayOf(), null) }
        assertFailsWith<IllegalStateException> {
            database.transaction {
                insertRow(
                    Uuid.parse("123e4567-e89b-12d3-a456-426614174002"),
                    "rolled-back",
                    false,
                    1,
                    BigDecimal.ONE,
                    byteArrayOf(),
                    null,
                )
                error("rollback")
            }
        }
        assertNull(database.select(Rows.id).from(Rows).where(Rows.name eq "rolled-back").oneOrNull())

        savepointContract(database)

        val unique = assertFailsWith<ConstraintViolation> {
            database.insertRow(
                Uuid.parse("123e4567-e89b-12d3-a456-426614174003"),
                "東京 Ada",
                false,
                0,
                BigDecimal.ZERO,
                byteArrayOf(),
                null,
            )
        }
        assertEquals("2627", unique.metadata.vendorCode)
        val foreignKey = assertFailsWith<ConstraintViolation> {
            database.executeRaw(
                "INSERT INTO [kb_child] ([id], [parent_id], [required_value]) VALUES (@kb1, @kb2, @kb3)",
                listOf(1, 999, "value"),
            )
        }
        assertEquals("547", foreignKey.metadata.vendorCode)
        val notNull = assertFailsWith<ConstraintViolation> {
            database.executeRaw(
                "INSERT INTO [kb_child] ([id], [parent_id], [required_value]) VALUES (@kb1, @kb2, @kb3)",
                listOf(2, 1, null),
            )
        }
        assertEquals("515", notNull.metadata.vendorCode)
        assertFailsWith<ResultConversionFailure> {
            database.queryRaw(
                "SELECT CAST(12345678901234567890.123456789 AS DECIMAL(30, 9)) AS [value]",
            ).toList()
        }
        val exactRaw = database.queryRaw(
            "SELECT CONVERT(NVARCHAR(40), CAST(12345678901234567890.123456789 AS DECIMAL(30, 9))) AS [value]",
        ).toList().single()
        assertEquals("12345678901234567890.123456789", exactRaw.string("value"))

        val top = database.select(Rows.name).from(Rows).limit(1).one()[Rows.name]
        assertTrue(top == "東京 Ada" || top == "committed")
        val page = database.select(
            Rows.name,
        ).from(Rows).orderBy(Rows.name.asc()).limit(1).offset(1).stream().toList()
        assertEquals(listOf("東京 Ada"), page.map { it[Rows.name] })
        coroutineScope {
            val results = (1..4).map { value ->
                async { database.queryRaw("SELECT @kb1 AS [value]", listOf(value)).toList().single().long("value") }
            }.awaitAll()
            assertEquals(listOf(1L, 2L, 3L, 4L), results)
        }
        assertEquals(1, database.deleteFrom(Rows).where(Rows.id eq second).execute().affectedRows)
    } finally {
        try {
            database.reset()
        } finally {
            database.close()
        }
    }
}

private suspend fun NodeSqlServerDatabase.insertRow(
    id: Uuid,
    name: String,
    active: Boolean,
    longValue: Long,
    decimal: BigDecimal,
    payload: ByteArray,
    nickname: String?,
) {
    insertInto(Rows).values {
        set(Rows.id, id)
        set(Rows.name, name)
        set(Rows.active, active)
        set(Rows.longValue, longValue)
        set(Rows.decimalValue, decimal)
        set(Rows.payload, payload)
        set(Rows.nickname, nickname)
    }.execute()
}

private suspend fun dev.quwac.kagiroi.sql.SqlExecution.insertRow(
    id: Uuid,
    name: String,
    active: Boolean,
    longValue: Long,
    decimal: BigDecimal,
    payload: ByteArray,
    nickname: String?,
) {
    insertInto(Rows).values {
        set(Rows.id, id)
        set(Rows.name, name)
        set(Rows.active, active)
        set(Rows.longValue, longValue)
        set(Rows.decimalValue, decimal)
        set(Rows.payload, payload)
        set(Rows.nickname, nickname)
    }.execute()
}

private suspend fun NodeSqlServerDatabase.reset() {
    executeRaw(
        "IF OBJECT_ID(N'[kb_generated_values]', N'U') IS NOT NULL DROP TABLE [kb_generated_values]; " +
            "IF OBJECT_ID(N'[kb_advanced_analytical]', N'U') IS NOT NULL DROP TABLE [kb_advanced_analytical]; " +
            "IF OBJECT_ID(N'[kb_child]', N'U') IS NOT NULL DROP TABLE [kb_child]; " +
            "IF OBJECT_ID(N'[kb_parent]', N'U') IS NOT NULL DROP TABLE [kb_parent]; " +
            "IF OBJECT_ID(N'[kb_contract]', N'U') IS NOT NULL DROP TABLE [kb_contract]; " +
            "IF OBJECT_ID(N'[kb_comments]', N'U') IS NOT NULL DROP TABLE [kb_comments]",
    )
}

private suspend fun verifyAdvancedAnalyticalSql(database: NodeSqlServerDatabase) {
    database.execute(createTable(AdvancedAnalyticalRows))
    listOf(
        Triple(10, 10, 1),
        Triple(20, null, 2),
        Triple(20, 20, 3),
        Triple(40, null, 4),
        Triple(50, 30, 5),
    ).forEach { (score, value, id) ->
        database.insertInto(AdvancedAnalyticalRows).values {
            set(AdvancedAnalyticalRows.id, id)
            set(AdvancedAnalyticalRows.score, score)
            set(AdvancedAnalyticalRows.value, value)
        }.execute()
    }

    val percentRank = percentRank().over { orderBy(AdvancedAnalyticalRows.score.asc()) }.alias("percent_rank")
    val cumeDist = cumeDist().over { orderBy(AdvancedAnalyticalRows.score.asc()) }.alias("cume_dist")
    val lastRespectingNulls = lastValue(AdvancedAnalyticalRows.value)
        .respectNulls()
        .over {
            orderBy(AdvancedAnalyticalRows.id.asc())
            rows(between(unboundedPreceding, currentRow))
        }
        .alias("last_respecting_nulls")
    val lastIgnoringNulls = lastValue(AdvancedAnalyticalRows.value)
        .ignoreNulls()
        .over {
            orderBy(AdvancedAnalyticalRows.id.asc())
            rows(between(unboundedPreceding, currentRow))
        }
        .alias("last_ignoring_nulls")

    val rows = database.select(percentRank, cumeDist, lastRespectingNulls, lastIgnoringNulls)
        .from(AdvancedAnalyticalRows)
        .orderBy(AdvancedAnalyticalRows.id.asc())
        .all()
    assertEquals(listOf(0.0, 0.25, 0.25, 0.75, 1.0), rows.map { it[percentRank] })
    assertEquals(listOf(0.2, 0.6, 0.6, 0.8, 1.0), rows.map { it[cumeDist] })
    assertEquals(listOf(10, null, 20, null, 30), rows.map { it[lastRespectingNulls] })
    assertEquals(listOf(10, 10, 20, 20, 30), rows.map { it[lastIgnoringNulls] })
}

private suspend fun verifyGeneratedValues(database: NodeSqlServerDatabase) {
    database.execute(createTable(GeneratedValueRows))
    repeat(3) {
        database.insertInto(GeneratedValueRows).values {
            set(GeneratedValueRows.price, 6)
            set(GeneratedValueRows.quantity, 7)
        }.execute()
    }

    val rows = database.select(GeneratedValueRows.id, GeneratedValueRows.total)
        .from(GeneratedValueRows)
        .orderBy(GeneratedValueRows.id.asc())
        .all()
    assertEquals(listOf(100L, 110L, 120L), rows.map { it[GeneratedValueRows.id] })
    assertEquals(listOf(42, 42, 42), rows.map { it[GeneratedValueRows.total] })
}

private suspend fun verifySchemaComments(database: NodeSqlServerDatabase) {
    database.execute(createTable(CommentedRows))

    assertEquals(
        "Application users",
        database.queryRaw(
            "SELECT CONVERT(NVARCHAR(MAX), property.value) AS [comment] " +
                "FROM sys.extended_properties AS property " +
                "JOIN sys.tables AS object ON object.object_id = property.major_id " +
                "WHERE property.name = N'MS_Description' AND object.name = N'kb_comments' " +
                "AND property.minor_id = 0",
        ).toList().single().string("comment"),
    )
    assertEquals(
        "Primary email address",
        database.queryRaw(
            "SELECT CONVERT(NVARCHAR(MAX), property.value) AS [comment] " +
                "FROM sys.extended_properties AS property " +
                "JOIN sys.columns AS column_value ON column_value.object_id = property.major_id " +
                "AND column_value.column_id = property.minor_id " +
                "WHERE property.name = N'MS_Description' AND column_value.object_id = " +
                "OBJECT_ID(N'[kb_comments]') AND column_value.name = N'email'",
        ).toList().single().string("comment"),
    )
}

private object Rows : Table("kb_contract") {
    val id = uuid("id").primaryKey()
    val name = column("name", SqlServerTypes.nVarchar(128)).unique()
    val active = boolean("active")
    val longValue = long("long_value")
    val decimalValue = column("decimal_value", SqlServerQuwacBigNumber.decimal(28, 9))
    val payload = bytes("payload")
    val nickname = string("nickname").nullable()
}

private object AdvancedAnalyticalRows : Table("kb_advanced_analytical") {
    val id = int("id").primaryKey()
    val score = int("score")
    val value = int("value").nullable()
}

private object CommentedRows : Table("kb_comments", comment = "Application users") {
    val id = long("id")
    val email = string("email").comment("Primary email address")
}

private object GeneratedValueRows : Table("kb_generated_values") {
    val id = long("id").primaryKey().identity(startWith = 100L, incrementBy = 10L)
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Stored)
}
