package dev.quwac.kagiroi.sql.testing

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.abs
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.avg
import dev.quwac.kagiroi.schema.case
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.ceil
import dev.quwac.kagiroi.schema.concat
import dev.quwac.kagiroi.schema.coalesce
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.countDistinct
import dev.quwac.kagiroi.schema.elseValue
import dev.quwac.kagiroi.schema.denseRank
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.floor
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.lag
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.lead
import dev.quwac.kagiroi.schema.like
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.ltrim
import dev.quwac.kagiroi.schema.max
import dev.quwac.kagiroi.schema.min
import dev.quwac.kagiroi.schema.nullIf
import dev.quwac.kagiroi.schema.nthValue
import dev.quwac.kagiroi.schema.ntile
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.power
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.rank
import dev.quwac.kagiroi.schema.replace
import dev.quwac.kagiroi.schema.round
import dev.quwac.kagiroi.schema.rowNumber
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.rtrim
import dev.quwac.kagiroi.schema.sqrt
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.schema.stringType
import dev.quwac.kagiroi.schema.trim
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.schema.upper
import dev.quwac.kagiroi.schema.whenThen
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.ExpectedOneRowButFoundMultiple
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.currentRow
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.distinct
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.exists
import dev.quwac.kagiroi.sql.dropIndex
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.fullOuterJoinUsing
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.having
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.leftJoinUsing
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.notExists
import dev.quwac.kagiroi.sql.notInSubquery
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.between
import dev.quwac.kagiroi.sql.preceding
import dev.quwac.kagiroi.sql.rightJoin
import dev.quwac.kagiroi.sql.rightJoinUsing
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.unboundedPreceding
import dev.quwac.kagiroi.sql.unboundedFollowing
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.window
import dev.quwac.kagiroi.sql.with
import kotlinx.coroutines.flow.toList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal data class SqlDatabaseContractConfiguration(
    val rawSelectByIdSql: String,
    val uniqueViolationSqlState: String?,
    val uniqueViolationVendorCode: String? = null,
    val supportsWindowNavigationDefault: Boolean = false,
    val supportsAdvancedWindowFeatures: Boolean = false,
)

/** Driver-independent behavioral contract for the shared SQL database surface. */
internal suspend fun sqlDatabaseContract(
    database: SqlDatabase,
    configuration: SqlDatabaseContractConfiguration,
    progress: (String) -> Unit = {},
) {
    try {
        progress("common-reset-table")
        database.execute(dropTable(ContractRows, ifExists = true))
        progress("common-create-table")
        database.execute(createTable(ContractRows))
        progress("common-typed-ddl")
        verifyTypedDdl(database)
        progress("common-crud")
        verifyCrudAndMappings(database)
        progress("common-transactions")
        verifyTransactions(database)
        progress("common-constraints-and-queries")
        verifyConstraintsAndQueries(database, configuration)
        progress("common-functions")
        functionExpressionContract(database)
        progress("common-function-defaults")
        defaultExpressionContract(database)
        progress("common-window-functions")
        windowExpressionContract(
            database,
            configuration.supportsWindowNavigationDefault,
            configuration.supportsAdvancedWindowFeatures,
        )
        progress("common-subqueries")
        subqueryExpressionContract(database)
        progress("common-ctes")
        cteExpressionContract(database)
        progress("common-recursive-ctes")
        recursiveCteExpressionContract(database)
    } finally {
        progress("common-drop-table")
        database.execute(dropTable(ContractRows, ifExists = true))
        database.execute(dropTable(FunctionRows, ifExists = true))
        database.execute(dropTable(FunctionDefaultRows, ifExists = true))
    }
}

private suspend fun verifyTypedDdl(database: SqlDatabase) {
    database.execute(dropTable(ContractDdlRows, ifExists = true))
    try {
        database.execute(createTable(ContractDdlBaseRows))
        database.execute(alterTable(ContractDdlRows) { addColumn(ContractDdlRows.note) })
        database.execute(
            createIndex("kagiroi_sql_ddl_name_idx", ContractDdlRows, unique = true) {
                on(ContractDdlRows.indexValue, ContractDdlRows.id)
            },
        )
        database.insertInto(ContractDdlRows).values {
            set(ContractDdlRows.id, 1)
            set(ContractDdlRows.name, "typed-ddl")
            set(ContractDdlRows.indexValue, 1)
            set(ContractDdlRows.note, null)
        }.execute()
        assertEquals(
            "typed-ddl",
            database.select(ContractDdlRows.name).from(ContractDdlRows).one()[ContractDdlRows.name],
        )
        database.execute(dropIndex("kagiroi_sql_ddl_name_idx", ContractDdlRows))
    } finally {
        database.execute(dropTable(ContractDdlRows, ifExists = true))
    }
}

private suspend fun verifyCrudAndMappings(database: SqlDatabase) {
    val insert = database.insertInto(ContractRows).values {
        set(ContractRows.id, FIRST_ID)
        set(ContractRows.name, "Ada")
        set(ContractRows.uniqueValue, FIRST_ID)
        set(ContractRows.active, true)
        set(ContractRows.payload, PAYLOAD)
        set(ContractRows.nickname, null)
    }.execute()
    assertEquals(1, insert.affectedRows)

    val row = database.select(
        ContractRows.id,
        ContractRows.name,
        ContractRows.active,
        ContractRows.payload,
        ContractRows.nickname,
    ).from(ContractRows).where(ContractRows.id eq FIRST_ID).one()
    assertEquals(FIRST_ID, row[ContractRows.id])
    assertEquals("Ada", row[ContractRows.name])
    assertTrue(row[ContractRows.active])
    assertContentEquals(PAYLOAD, row[ContractRows.payload])
    assertNull(row[ContractRows.nickname])

    val update = database.update(ContractRows) { set(ContractRows.nickname, "updated") }
        .where(ContractRows.id eq FIRST_ID)
        .execute()
    assertEquals(1, update.affectedRows)
    assertEquals(
        "updated",
        database.select(ContractRows.nickname).from(ContractRows).where(ContractRows.id eq FIRST_ID)
            .one()[ContractRows.nickname],
    )
}

private suspend fun verifyTransactions(database: SqlDatabase) {
    database.transaction {
        insertRow(SECOND_ID, "committed")
    }
    assertFailsWith<IllegalStateException> {
        database.transaction {
            insertRow(ROLLED_BACK_ID, "rolled-back")
            error("rollback")
        }
    }
    assertNull(
        database.select(ContractRows.id).from(ContractRows)
            .where(ContractRows.id eq ROLLED_BACK_ID)
            .oneOrNull(),
    )
}

private suspend fun verifyConstraintsAndQueries(
    database: SqlDatabase,
    configuration: SqlDatabaseContractConfiguration,
) {
    val violation = assertFailsWith<ConstraintViolation> {
        database.insertInto(ContractRows).values {
            set(ContractRows.id, DUPLICATE_ID)
            set(ContractRows.name, "Ada")
            set(ContractRows.uniqueValue, FIRST_ID)
            set(ContractRows.active, false)
            set(ContractRows.payload, byteArrayOf())
            set(ContractRows.nickname, null)
        }.execute()
    }
    assertEquals(ConstraintKind.UNIQUE, violation.kind)
    assertEquals(configuration.uniqueViolationSqlState, violation.metadata.sqlState)
    configuration.uniqueViolationVendorCode?.let { expected ->
        assertEquals(expected, violation.metadata.vendorCode)
    }

    assertEquals(2, database.select(ContractRows.id).from(ContractRows).stream().toList().size)
    assertFailsWith<ExpectedOneRowButFoundMultiple> {
        database.select(ContractRows.id).from(ContractRows).one()
    }
    val raw = database.queryRaw(configuration.rawSelectByIdSql, listOf(FIRST_ID)).toList().single()
    assertEquals("Ada", raw.string("name"))
    assertContentEquals(PAYLOAD, raw.bytes("payload"))

    val delete = database.deleteFrom(ContractRows).where(ContractRows.id eq SECOND_ID).execute()
    assertEquals(1, delete.affectedRows)
}

/** Runtime contract for portable scalar, aggregate, alias, and expression-composition support. */
internal suspend fun functionExpressionContract(database: SqlDatabase, includePower: Boolean = true) {
    database.execute(dropTable(FunctionRows, ifExists = true))
    try {
        database.execute(createTable(FunctionRows))
        database.insertInto(FunctionRows).values {
            set(FunctionRows.id, 1)
            set(FunctionRows.name, "  Ada  ")
            set(FunctionRows.nickname, null)
            set(FunctionRows.amount, 10)
            set(FunctionRows.ratio, 1.0)
        }.execute()
        database.insertInto(FunctionRows).values {
            set(FunctionRows.id, 2)
            set(FunctionRows.name, "Bob")
            set(FunctionRows.nickname, "Bobby")
            set(FunctionRows.amount, -20)
            set(FunctionRows.ratio, 3.0)
        }.execute()
        database.insertInto(FunctionRows).values {
            set(FunctionRows.id, 3)
            set(FunctionRows.name, "Cara")
            set(FunctionRows.nickname, null)
            set(FunctionRows.amount, 30)
            set(FunctionRows.ratio, 6.0)
        }.execute()

        val lowered = lower(trim(FunctionRows.name)).alias("lowered_name")
        val uppered = upper(trim(FunctionRows.name)).alias("uppered_name")
        val nameLength = length(trim(FunctionRows.name)).alias("name_length")
        val fallback = coalesce(FunctionRows.nickname, literal("anonymous")).alias("display_name")
        val magnitude = abs(FunctionRows.amount).alias("amount_magnitude")
        val scalar = database.select(lowered, uppered, nameLength, fallback, magnitude)
            .from(FunctionRows)
            .where(lower(trim(FunctionRows.name)) eq "ada")
            .orderBy(lower(FunctionRows.name).asc())
            .one()
        assertEquals("ada", scalar[lowered])
        assertEquals("ADA", scalar[uppered])
        assertEquals(3L, scalar[nameLength])
        assertEquals("anonymous", scalar[fallback])
        assertEquals(10, scalar[magnitude])

        val leftTrimmed = ltrim(FunctionRows.name).alias("left_trimmed")
        val rightTrimmed = rtrim(FunctionRows.name).alias("right_trimmed")
        val prefix = substring(FunctionRows.name, start = 3, length = 2).alias("prefix")
        val replaced = replace(FunctionRows.name, old = "Ada", new = "Ada Lovelace").alias("replaced")
        val greeting = concat(trim(FunctionRows.name), literal("!")).alias("greeting")
        val missingName = nullIf(FunctionRows.name, "missing").alias("missing_name")
        val anonymous = coalesce(nullIf(FunctionRows.name, "  Ada  "), literal("anonymous")).alias("anonymous")
        val rounded = round(FunctionRows.ratio, digits = 1).alias("rounded")
        val floored = floor(FunctionRows.ratio).alias("floored")
        val ceiling = ceil(FunctionRows.ratio).alias("ceiling")
        val root = sqrt(FunctionRows.amount).alias("root")
        val strings = database.select(
            leftTrimmed,
            rightTrimmed,
            prefix,
            replaced,
            greeting,
            missingName,
            anonymous,
        ).from(FunctionRows)
            .where(FunctionRows.name like "%Ada%")
            .one()
        assertEquals("Ada  ", strings[leftTrimmed])
        assertEquals("  Ada", strings[rightTrimmed])
        assertEquals("Ad", strings[prefix])
        assertEquals("  Ada Lovelace  ", strings[replaced])
        assertEquals("Ada!", strings[greeting])
        assertEquals("  Ada  ", strings[missingName])
        assertEquals("anonymous", strings[anonymous])

        val numbers = database.select(
            rounded,
            floored,
            ceiling,
            root,
        ).from(FunctionRows)
            .where(FunctionRows.name like "%Ada%")
            .one()
        assertEquals(1.0, numbers[rounded])
        assertEquals(1.0, numbers[floored])
        assertEquals(1.0, numbers[ceiling])
        assertEquals(kotlin.math.sqrt(10.0), requireNotNull(numbers[root]), 0.000_001)
        if (includePower) {
            val squared = power(FunctionRows.ratio, 2).alias("squared")
            val powered = database.select(squared).from(FunctionRows)
                .where(FunctionRows.name like "%Ada%")
                .one()
            assertEquals(1.0, powered[squared])
        }

        val rowCount = count().alias("row_count")
        val namedCount = count(FunctionRows.nickname).alias("named_count")
        val total = sum(FunctionRows.amount).alias("total_amount")
        val average = avg(FunctionRows.amount).alias("average_amount")
        val averageRatio = avg(FunctionRows.ratio).alias("average_ratio")
        val smallest = min(FunctionRows.amount).alias("smallest_amount")
        val largest = max(FunctionRows.amount).alias("largest_amount")
        val aggregate = database.select(rowCount, namedCount, total, average, averageRatio, smallest, largest)
            .from(FunctionRows)
            .one()
        assertEquals(3L, aggregate[rowCount])
        assertEquals(1L, aggregate[namedCount])
        assertEquals(20L, aggregate[total])
        // Integer AVG is rounded differently by the supported databases. Its Kotlin type is
        // still the portable Double? logical result; use an approximate input to exercise the
        // non-integral result contract shared by every dialect.
        assertNotNull(aggregate[average])
        assertEquals(10.0 / 3.0, requireNotNull(aggregate[averageRatio]), 0.000_001)
        assertEquals(-20, aggregate[smallest])
        assertEquals(30, aggregate[largest])

        val emptyCount = count().alias("empty_count")
        val emptyTotal = sum(FunctionRows.amount).alias("empty_total")
        val emptyAverage = avg(FunctionRows.amount).alias("empty_average")
        val emptyMinimum = min(FunctionRows.amount).alias("empty_minimum")
        val emptyMaximum = max(FunctionRows.amount).alias("empty_maximum")
        val empty = database.select(emptyCount, emptyTotal, emptyAverage, emptyMinimum, emptyMaximum)
            .from(FunctionRows)
            .where(FunctionRows.id lt 0)
            .one()
        assertEquals(0L, empty[emptyCount])
        assertNull(empty[emptyTotal])
        assertNull(empty[emptyAverage])
        assertNull(empty[emptyMinimum])
        assertNull(empty[emptyMaximum])

        groupedQueryContract(database)

        val nullableGroupCount = count().alias("nullable_group_count")
        val nullableGroups = database.select(FunctionRows.nickname, nullableGroupCount)
            .from(FunctionRows)
            .groupBy(FunctionRows.nickname)
            .all()
        assertEquals(2L, nullableGroups.single { row -> row[FunctionRows.nickname] == null }[nullableGroupCount])
        castDistinctCaseExpressionContract(database)
    } finally {
        database.execute(dropTable(FunctionRows, ifExists = true))
    }
}

/** Runtime contract for a typed scalar expression evaluated by a column DEFAULT. */
internal suspend fun defaultExpressionContract(database: SqlDatabase) {
    database.execute(dropTable(FunctionDefaultRows, ifExists = true))
    try {
        database.execute(createTable(FunctionDefaultRows))
        database.insertInto(FunctionDefaultRows).values {
            set(FunctionDefaultRows.id, 1)
        }.execute()
        assertEquals(
            "default",
            database.select(FunctionDefaultRows.normalized).from(FunctionDefaultRows).one()[
                FunctionDefaultRows.normalized,
            ],
        )
    } finally {
        database.execute(dropTable(FunctionDefaultRows, ifExists = true))
    }
}

/** Runtime contract for typed CAST, SELECT DISTINCT, searched CASE, aggregates, and windows. */
internal suspend fun castDistinctCaseExpressionContract(database: SqlDatabase) {
    try {
        database.execute(createTable(CastDistinctCaseRows))
        insertCastDistinctCaseRow(database, 1, 1, amount = 5)
        insertCastDistinctCaseRow(database, 2, 1, amount = 20)
        insertCastDistinctCaseRow(database, 3, 2, amount = 30)

        val idText = cast(CastDistinctCaseRows.id, stringType()).alias("id_text")
        val category = case<String> {
            whenThen(CastDistinctCaseRows.amount gt 10, "large")
            elseValue("small")
        }.alias("category")
        val selected = database.select(idText, category).from(CastDistinctCaseRows)
            .where(category.expression eq "large")
            .orderBy(CastDistinctCaseRows.id.asc())
            .all()
        assertEquals(listOf("2", "3"), selected.map { row -> row[idText] })
        assertEquals(listOf("large", "large"), selected.map { row -> row[category] })

        assertEquals(
            listOf(1, 2),
            database.select(CastDistinctCaseRows.organizationId).distinct().from(CastDistinctCaseRows)
                .orderBy(CastDistinctCaseRows.organizationId.asc()).all()
                .map { row -> row[CastDistinctCaseRows.organizationId] },
        )

        val distinctOrganizations = countDistinct(CastDistinctCaseRows.organizationId).alias("distinct_organizations")
        val largeCount = sum(
            case<Long> {
                whenThen(CastDistinctCaseRows.amount gt 10, 1L)
                elseValue(0L)
            },
        ).alias("large_count")
        val aggregate = database.select(distinctOrganizations, largeCount).from(CastDistinctCaseRows).one()
        assertEquals(2L, aggregate[distinctOrganizations])
        assertEquals(2L, aggregate[largeCount])

        val runningLargeCount = sum(
            case<Long> {
                whenThen(CastDistinctCaseRows.amount gt 10, 1L)
                elseValue(0L)
            },
        ).over {
            orderBy(CastDistinctCaseRows.id.asc())
        }.alias("running_large_count")
        assertEquals(
            listOf(0L, 1L, 2L),
            database.select(runningLargeCount).from(CastDistinctCaseRows)
                .orderBy(CastDistinctCaseRows.id.asc()).all()
                .map { row -> row[runningLargeCount] },
        )
    } finally {
        database.execute(dropTable(CastDistinctCaseRows))
    }
}

private suspend fun insertCastDistinctCaseRow(
    database: SqlDatabase,
    id: Int,
    organizationId: Int,
    amount: Int,
) {
    database.insertInto(CastDistinctCaseRows).values {
        set(CastDistinctCaseRows.id, id)
        set(CastDistinctCaseRows.organizationId, organizationId)
        set(CastDistinctCaseRows.amount, amount)
    }.execute()
}

/** Runtime contract for portable inline window functions and typed derived/CTE filtering. */
internal suspend fun windowExpressionContract(
    database: SqlDatabase,
    supportsNavigationDefault: Boolean = false,
    supportsAdvancedWindowFeatures: Boolean = false,
) {
    database.execute(dropTable(WindowRows, ifExists = true))
    try {
        database.execute(createTable(WindowRows))
        insertWindowRow(database, 1, 1, 10)
        insertWindowRow(database, 2, 1, 10)
        insertWindowRow(database, 3, 1, 5)
        insertWindowRow(database, 4, 2, 10)
        insertWindowRow(database, 5, 2, 7)

        val rowNumber = rowNumber().over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
        }.alias("row_number")
        val rank = rank().over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.amount.desc())
        }.alias("rank")
        val denseRank = denseRank().over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.amount.desc())
        }.alias("dense_rank")
        val percentRank = percentRank().over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.amount.desc())
        }.alias("percent_rank")
        val cumeDist = cumeDist().over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.amount.desc())
        }.alias("cume_dist")
        val globalCount = count().over().alias("global_count")
        val partitionCount = count().over {
            partitionBy(WindowRows.organizationId)
        }.alias("partition_count")
        val partitionTotal = sum(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
        }.alias("partition_total")
        val partitionAverage = avg(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
        }.alias("partition_average")
        val partitionMinimum = min(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
        }.alias("partition_minimum")
        val partitionMaximum = max(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
        }.alias("partition_maximum")
        val runningTotal = sum(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            rows(between(unboundedPreceding, currentRow))
        }.alias("running_total")
        val movingTotal = sum(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            rows(between(preceding(1), currentRow))
        }.alias("moving_total")
        val rowsPeerTotal = sum(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.amount.asc())
            rows(between(unboundedPreceding, currentRow))
        }.alias("rows_peer_total")
        val rangePeerTotal = sum(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.amount.asc())
            range(between(unboundedPreceding, currentRow))
        }.alias("range_peer_total")
        val rangeCount = count().over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            range(between(unboundedPreceding, currentRow))
        }.alias("range_count")
        val rangeTotal = sum(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            range(between(unboundedPreceding, currentRow))
        }.alias("range_total")
        val rangeAverage = avg(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            range(between(unboundedPreceding, currentRow))
        }.alias("range_average")
        val rangeMinimum = min(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            range(between(unboundedPreceding, currentRow))
        }.alias("range_minimum")
        val rangeMaximum = max(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            range(between(unboundedPreceding, currentRow))
        }.alias("range_maximum")
        val previousAmount = lag(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
        }.alias("previous_amount")
        val nextAmount = lead(WindowRows.amount, offset = 2).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
        }.alias("next_amount")
        val firstAmount = firstValue(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
        }.alias("first_amount")
        val currentFrameLastAmount = lastValue(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
        }.alias("current_frame_last_amount")
        val partitionLastAmount = lastValue(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            rows(between(unboundedPreceding, unboundedFollowing))
        }.alias("partition_last_amount")
        val rangeFirstAmount = firstValue(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            range(between(unboundedPreceding, currentRow))
        }.alias("range_first_amount")
        val rangeLastAmount = lastValue(WindowRows.amount).over {
            partitionBy(WindowRows.organizationId)
            orderBy(WindowRows.id.asc())
            range(between(unboundedPreceding, unboundedFollowing))
        }.alias("range_last_amount")

        val rows = database.select(
            WindowRows.id,
            WindowRows.organizationId,
            WindowRows.amount,
            rowNumber,
            rank,
            denseRank,
            percentRank,
            cumeDist,
            globalCount,
            partitionCount,
            partitionTotal,
            partitionAverage,
            partitionMinimum,
            partitionMaximum,
            runningTotal,
            movingTotal,
            rowsPeerTotal,
            rangePeerTotal,
            rangeCount,
            rangeTotal,
            rangeAverage,
            rangeMinimum,
            rangeMaximum,
            previousAmount,
            nextAmount,
            firstAmount,
            currentFrameLastAmount,
            partitionLastAmount,
            rangeFirstAmount,
            rangeLastAmount,
        ).from(WindowRows)
            .orderBy(WindowRows.organizationId.asc(), WindowRows.id.asc())
            .all()

        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { row -> row[WindowRows.id] })
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L), rows.map { row -> row[rowNumber] })
        assertEquals(listOf(1L, 1L, 3L, 1L, 2L), rows.map { row -> row[rank] })
        assertEquals(listOf(1L, 1L, 2L, 1L, 2L), rows.map { row -> row[denseRank] })
        assertEquals(listOf(0.0, 0.0, 1.0, 0.0, 1.0), rows.map { row -> row[percentRank] })
        val expectedCumeDists = listOf(2.0 / 3.0, 2.0 / 3.0, 1.0, 0.5, 1.0)
        rows.map { row -> row[cumeDist] }.forEachIndexed { index, value ->
            assertEquals(expectedCumeDists[index], value, 0.000_000_1)
        }
        assertEquals(listOf(5L, 5L, 5L, 5L, 5L), rows.map { row -> row[globalCount] })
        assertEquals(listOf(3L, 3L, 3L, 2L, 2L), rows.map { row -> row[partitionCount] })
        assertEquals(listOf(25L, 25L, 25L, 17L, 17L), rows.map { row -> row[partitionTotal] })
        val expectedPartitionAverages = listOf(25.0 / 3.0, 25.0 / 3.0, 25.0 / 3.0, 8.5, 8.5)
        rows.map { row -> requireNotNull(row[partitionAverage]) }
            .forEachIndexed { index, value ->
                assertEquals(expectedPartitionAverages[index], value, 0.000_1)
            }
        assertEquals(listOf(5, 5, 5, 7, 7), rows.map { row -> row[partitionMinimum] })
        assertEquals(listOf(10, 10, 10, 10, 10), rows.map { row -> row[partitionMaximum] })
        assertEquals(listOf(10L, 20L, 25L, 10L, 17L), rows.map { row -> row[runningTotal] })
        assertEquals(listOf(10L, 20L, 15L, 10L, 17L), rows.map { row -> row[movingTotal] })
        val firstPartitionTens = rows.filter { row ->
            row[WindowRows.organizationId] == 1 && row[WindowRows.amount] == 10
        }
        assertEquals(setOf(15L, 25L), firstPartitionTens.map { row -> row[rowsPeerTotal] }.toSet())
        assertEquals(listOf(25L, 25L), firstPartitionTens.map { row -> row[rangePeerTotal] })
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L), rows.map { row -> row[rangeCount] })
        assertEquals(listOf(10L, 20L, 25L, 10L, 17L), rows.map { row -> row[rangeTotal] })
        val expectedRangeAverages = listOf(10.0, 10.0, 25.0 / 3.0, 10.0, 8.5)
        rows.map { row -> requireNotNull(row[rangeAverage]) }
            .forEachIndexed { index, value ->
                assertEquals(expectedRangeAverages[index], value, 0.000_1)
            }
        assertEquals(listOf(10, 10, 5, 10, 7), rows.map { row -> row[rangeMinimum] })
        assertEquals(listOf(10, 10, 10, 10, 10), rows.map { row -> row[rangeMaximum] })
        assertEquals(listOf(null, 10, 10, null, 10), rows.map { row -> row[previousAmount] })
        assertEquals(listOf(5, null, null, null, null), rows.map { row -> row[nextAmount] })
        assertEquals(listOf(10, 10, 10, 10, 10), rows.map { row -> row[firstAmount] })
        assertEquals(listOf(10, 10, 5, 10, 7), rows.map { row -> row[currentFrameLastAmount] })
        assertEquals(listOf(5, 5, 5, 7, 7), rows.map { row -> row[partitionLastAmount] })
        assertEquals(listOf(10, 10, 10, 10, 10), rows.map { row -> row[rangeFirstAmount] })
        assertEquals(listOf(5, 5, 5, 7, 7), rows.map { row -> row[rangeLastAmount] })

        if (supportsNavigationDefault) {
            val previousWithDefault = lag(WindowRows.amount, default = 0).over {
                partitionBy(WindowRows.organizationId)
                orderBy(WindowRows.id.asc())
            }.alias("previous_with_default")
            val defaultRows = database.select(previousWithDefault)
                .from(WindowRows)
                .orderBy(WindowRows.organizationId.asc(), WindowRows.id.asc())
                .all()
            assertEquals(listOf(0, 10, 10, 0, 10), defaultRows.map { row -> row[previousWithDefault] })
        }

        if (supportsAdvancedWindowFeatures) {
            advancedWindowExpressionContract(database)
        }

        val ranked = select(WindowRows.id, rowNumber).from(WindowRows).asTable("ranked_window_rows")
        val rankedId = ranked[WindowRows.id]
        val rankedRowNumber = ranked[rowNumber]
        assertEquals(
            listOf(2, 3, 5),
            database.select(rankedId).from(ranked).where(rankedRowNumber gt 1L)
                .orderBy(rankedId.asc()).all().map { row -> row[rankedId] },
        )

        val rankedCte = select(WindowRows.id, rowNumber).from(WindowRows).asCte("ranked_window_rows")
        val cteId = rankedCte[WindowRows.id]
        val cteRowNumber = rankedCte[rowNumber]
        val cteQuery = with(rankedCte) {
            database.select(cteId).from(rankedCte).where(cteRowNumber gt 1L)
                .orderBy(cteId.asc())
        }
        assertEquals(
            listOf(2, 3, 5),
            cteQuery.all().map { row -> row[cteId] },
        )

        val total = sum(WindowRows.amount).alias("total")
        val groupedRank = rank().over { orderBy(sum(WindowRows.amount).desc()) }.alias("group_rank")
        val grouped = database.select(WindowRows.organizationId, total, groupedRank)
            .from(WindowRows)
            .groupBy(WindowRows.organizationId)
            .orderBy(WindowRows.organizationId.asc())
            .all()
        assertEquals(listOf(1, 2), grouped.map { row -> row[WindowRows.organizationId] })
        assertEquals(listOf(25L, 17L), grouped.map { row -> row[total] })
        assertEquals(listOf(1L, 2L), grouped.map { row -> row[groupedRank] })
    } finally {
        database.execute(dropTable(WindowRows, ifExists = true))
    }
}

/** Native-only contract for `NTILE`, `NTH_VALUE`, `GROUPS`, frame exclusions, and named windows. */
private suspend fun advancedWindowExpressionContract(database: SqlDatabase) {
    val tiles = ntile(2).over {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.id.asc())
    }.alias("tile")
    val second = nthValue(WindowRows.amount, 2).over {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.id.asc())
        rows(between(unboundedPreceding, unboundedFollowing))
    }.alias("second")
    val groupTotal = sum(WindowRows.amount).over {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.amount.asc())
        groups(between(preceding(1), currentRow))
    }.alias("group_total")
    val rowsTotal = sum(WindowRows.amount).over {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.amount.asc(), WindowRows.id.asc())
        rows(between(preceding(1), currentRow))
    }.alias("rows_total")
    val excludingCurrent = sum(WindowRows.amount).over {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.amount.asc())
        groups(between(unboundedPreceding, unboundedFollowing))
        excludeCurrentRow()
    }.alias("excluding_current")
    val excludingGroup = sum(WindowRows.amount).over {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.amount.asc())
        groups(between(unboundedPreceding, unboundedFollowing))
        excludeGroup()
    }.alias("excluding_group")
    val excludingTies = sum(WindowRows.amount).over {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.amount.asc())
        groups(between(unboundedPreceding, unboundedFollowing))
        excludeTies()
    }.alias("excluding_ties")
    val named = window("window_contract") {
        partitionBy(WindowRows.organizationId)
        orderBy(WindowRows.id.asc())
        rows(between(unboundedPreceding, unboundedFollowing))
    }
    val namedSum = sum(WindowRows.amount).over(named).alias("named_sum")
    val namedAverage = avg(WindowRows.amount).over(named).alias("named_average")

    val rows = database.select(
        WindowRows.id,
        tiles,
        second,
        groupTotal,
        rowsTotal,
        excludingCurrent,
        excludingGroup,
        excludingTies,
        namedSum,
        namedAverage,
    ).from(WindowRows)
        .window(named)
        .orderBy(WindowRows.id.asc())
        .all()

    assertEquals(listOf(1L, 1L, 2L, 1L, 2L), rows.map { row -> row[tiles] })
    assertEquals(listOf(10, 10, 10, 7, 7), rows.map { row -> row[second] })
    assertEquals(listOf(25L, 25L, 5L, 17L, 7L), rows.map { row -> row[groupTotal] })
    assertEquals(listOf(15L, 20L, 5L, 17L, 7L), rows.map { row -> row[rowsTotal] })
    assertEquals(listOf(15L, 15L, 20L, 7L, 10L), rows.map { row -> row[excludingCurrent] })
    assertEquals(listOf(5L, 5L, 20L, 7L, 10L), rows.map { row -> row[excludingGroup] })
    assertEquals(listOf(15L, 15L, 25L, 17L, 17L), rows.map { row -> row[excludingTies] })
    assertEquals(listOf(25L, 25L, 25L, 17L, 17L), rows.map { row -> row[namedSum] })
    val expectedNamedAverages = listOf(25.0 / 3.0, 25.0 / 3.0, 25.0 / 3.0, 8.5, 8.5)
    rows.map { row -> requireNotNull(row[namedAverage]) }.forEachIndexed { index, value ->
        assertEquals(expectedNamedAverages[index], value, 0.000_1)
    }
}

private suspend fun insertWindowRow(database: SqlDatabase, id: Int, organizationId: Int, amount: Int) {
    database.insertInto(WindowRows).values {
        set(WindowRows.id, id)
        set(WindowRows.organizationId, organizationId)
        set(WindowRows.amount, amount)
    }.execute()
}

/** Runtime contract for portable GROUP BY and HAVING over the normal typed SELECT tree. */
private suspend fun groupedQueryContract(database: SqlDatabase) {
    database.execute(dropTable(GroupingRows, ifExists = true))
    database.execute(dropTable(GroupingLimits, ifExists = true))
    try {
        database.execute(createTable(GroupingRows))
        database.execute(createTable(GroupingLimits))
        insertGroupingRow(database, "A", 10, "Ada@Example.test", active = true)
        insertGroupingRow(database, "A", 20, "ada@example.test", active = true)
        insertGroupingRow(database, "B", 5, "Bob@Example.test", active = true)
        insertGroupingRow(database, "disabled", 100, "Disabled@Example.test", active = false)
        database.insertInto(GroupingLimits).values { set(GroupingLimits.amount, 5) }.execute()

        val groupCount = count().alias("group_count")
        val total = sum(GroupingRows.amount).alias("total")
        val average = avg(GroupingRows.amount).alias("average")
        val minimum = min(GroupingRows.amount).alias("minimum")
        val maximum = max(GroupingRows.amount).alias("maximum")
        val summaries = database.select(GroupingRows.organizationId, groupCount, total, average, minimum, maximum)
            .from(GroupingRows)
            .where(GroupingRows.active eq true)
            .groupBy(GroupingRows.organizationId)
            .orderBy(GroupingRows.organizationId.asc())
            .all()
        assertEquals(listOf("A", "B"), summaries.map { row -> row[GroupingRows.organizationId] })
        assertEquals(listOf(2L, 1L), summaries.map { row -> row[groupCount] })
        assertEquals(listOf(30L, 5L), summaries.map { row -> row[total] })
        assertEquals(listOf(15.0, 5.0), summaries.map { row -> row[average] })
        assertEquals(listOf(10, 5), summaries.map { row -> row[minimum] })
        assertEquals(listOf(20, 5), summaries.map { row -> row[maximum] })

        val filtered = database.select(GroupingRows.organizationId, groupCount, total)
            .from(GroupingRows)
            .where(GroupingRows.active eq true)
            .groupBy(GroupingRows.organizationId)
            .having(total gt 10L)
            .orderBy(total.desc())
            .one()
        assertEquals("A", filtered[GroupingRows.organizationId])
        assertEquals(2L, filtered[groupCount])
        assertEquals(30L, filtered[total])

        val normalized = lower(GroupingRows.email).alias("normalized")
        val normalizedCount = count().alias("normalized_count")
        val normalizedRows = database.select(normalized, normalizedCount)
            .from(GroupingRows)
            .where(GroupingRows.active eq true)
            .groupBy(normalized)
            .orderBy(normalized.asc())
            .all()
        assertEquals(listOf("ada@example.test", "bob@example.test"), normalizedRows.map { row -> row[normalized] })
        assertEquals(listOf(2L, 1L), normalizedRows.map { row -> row[normalizedCount] })

        val source = select(GroupingRows.organizationId, GroupingRows.amount)
            .from(GroupingRows)
            .where(GroupingRows.active eq true)
            .asTable("grouping_source")
        val sourceOrganizationId = source[GroupingRows.organizationId]
        val sourceTotal = sum(source[GroupingRows.amount]).alias("source_total")
        val derivedRows = database.select(sourceOrganizationId, sourceTotal)
            .from(source)
            .groupBy(sourceOrganizationId)
            .orderBy(sourceOrganizationId.asc())
            .all()
        assertEquals(listOf("A", "B"), derivedRows.map { row -> row[sourceOrganizationId] })
        assertEquals(listOf(30L, 5L), derivedRows.map { row -> row[sourceTotal] })

        val lowerBound = select(avg(GroupingLimits.amount)).from(GroupingLimits).scalar()
        val groupedAverage = avg(GroupingRows.amount).alias("grouped_average")
        val scalarHaving = database.select(GroupingRows.organizationId, groupedAverage)
            .from(GroupingRows)
            .where(GroupingRows.active eq true)
            .groupBy(GroupingRows.organizationId)
            .having(groupedAverage gt lowerBound)
            .one()
        assertEquals("A", scalarHaving[GroupingRows.organizationId])
        assertEquals(15.0, scalarHaving[groupedAverage])

        val allCount = count().alias("all_count")
        val aggregateOnly = database.select(allCount)
            .from(GroupingRows)
            .having(count() gt 2L)
            .one()
        assertEquals(4L, aggregateOnly[allCount])
    } finally {
        database.execute(dropTable(GroupingRows, ifExists = true))
        database.execute(dropTable(GroupingLimits, ifExists = true))
    }
}

private suspend fun insertGroupingRow(
    database: SqlDatabase,
    organizationId: String,
    amount: Int,
    email: String,
    active: Boolean,
) {
    database.insertInto(GroupingRows).values {
        set(GroupingRows.organizationId, organizationId)
        set(GroupingRows.amount, amount)
        set(GroupingRows.email, email)
        set(GroupingRows.active, active)
    }.execute()
}

/** Runtime contract for scalar, membership, existence, correlation, and derived-table queries. */
internal suspend fun subqueryExpressionContract(database: SqlDatabase) {
    database.execute(dropTable(SubqueryContractRows, ifExists = true))
    database.execute(dropTable(SubqueryContractOwners, ifExists = true))
    try {
        database.execute(createTable(SubqueryContractOwners))
        database.execute(createTable(SubqueryContractRows))
        database.insertInto(SubqueryContractOwners).values {
            set(SubqueryContractOwners.id, 1)
            set(SubqueryContractOwners.name, "Ada")
            set(SubqueryContractOwners.active, true)
        }.execute()
        database.insertInto(SubqueryContractOwners).values {
            set(SubqueryContractOwners.id, 2)
            set(SubqueryContractOwners.name, "Babbage")
            set(SubqueryContractOwners.active, true)
        }.execute()
        database.insertInto(SubqueryContractOwners).values {
            set(SubqueryContractOwners.id, 3)
            set(SubqueryContractOwners.name, "Cara")
            set(SubqueryContractOwners.active, false)
        }.execute()
        insertSubqueryRow(database, 10, 1, 10)
        insertSubqueryRow(database, 11, 1, 20)
        insertSubqueryRow(database, 12, 2, 30)

        val latestAmount = select(SubqueryContractRows.amount)
            .from(SubqueryContractRows)
            .where(SubqueryContractRows.ownerId eq SubqueryContractOwners.id)
            .orderBy(SubqueryContractRows.amount.desc())
            .limit(1)
            .scalar()
            .alias("latest_amount")
        val scalarRows = database.select(SubqueryContractOwners.id, latestAmount)
            .from(SubqueryContractOwners)
            .orderBy(SubqueryContractOwners.id.asc())
            .all()
        assertEquals(listOf(20, 30, null), scalarRows.map { row -> row[latestAmount] })

        val eligibleOwnerIds = select(SubqueryContractRows.ownerId)
            .from(SubqueryContractRows)
            .where(SubqueryContractRows.amount gt 15)
        assertEquals(
            listOf(1, 2),
            database.select(SubqueryContractOwners.id)
                .from(SubqueryContractOwners)
                .where(SubqueryContractOwners.id.inSubquery(eligibleOwnerIds))
                .orderBy(SubqueryContractOwners.id.asc())
                .all()
                .map { row -> row[SubqueryContractOwners.id] },
        )

        val excludedOwnerIds = select(SubqueryContractRows.ownerId)
            .from(SubqueryContractRows)
            .where(SubqueryContractRows.amount lt 15)
        assertEquals(
            listOf(2, 3),
            database.select(SubqueryContractOwners.id)
                .from(SubqueryContractOwners)
                .where(SubqueryContractOwners.id.notInSubquery(excludedOwnerIds))
                .orderBy(SubqueryContractOwners.id.asc())
                .all()
                .map { row -> row[SubqueryContractOwners.id] },
        )

        val hasLargeOrder = select(SubqueryContractRows.id)
            .from(SubqueryContractRows)
            .where(
                (SubqueryContractRows.ownerId eq SubqueryContractOwners.id) and
                    (SubqueryContractRows.amount gt 15),
            )
        assertEquals(
            listOf(1, 2),
            database.select(SubqueryContractOwners.id)
                .from(SubqueryContractOwners)
                .where(exists(hasLargeOrder))
                .orderBy(SubqueryContractOwners.id.asc())
                .all()
                .map { row -> row[SubqueryContractOwners.id] },
        )
        assertEquals(
            listOf(3),
            database.select(SubqueryContractOwners.id)
                .from(SubqueryContractOwners)
                .where(notExists(hasLargeOrder))
                .all()
                .map { row -> row[SubqueryContractOwners.id] },
        )

        val activeOwners = select(SubqueryContractOwners.id, SubqueryContractOwners.name)
            .from(SubqueryContractOwners)
            .where(SubqueryContractOwners.active eq true)
            .asTable("active_owners")
        val activeOwnerId = activeOwners[SubqueryContractOwners.id]
        val activeOwnerName = activeOwners[SubqueryContractOwners.name]
        assertEquals(
            1,
            database.select(activeOwnerId)
                .from(activeOwners)
                .where(activeOwnerName eq "Ada")
                .one()[activeOwnerId],
        )
    } finally {
        database.execute(dropTable(SubqueryContractRows, ifExists = true))
        database.execute(dropTable(SubqueryContractOwners, ifExists = true))
    }
}

/** Runtime contract for non-recursive SELECT CTEs and their shared query-result projections. */
internal suspend fun cteExpressionContract(database: SqlDatabase) {
    database.execute(dropTable(CteContractRows, ifExists = true))
    database.execute(dropTable(CteContractOwners, ifExists = true))
    try {
        database.execute(createTable(CteContractOwners))
        database.execute(createTable(CteContractRows))
        insertCteOwner(database, 1, "Ada", active = true)
        insertCteOwner(database, 2, "Babbage", active = true)
        insertCteOwner(database, 3, "Cara", active = false)
        insertCteRow(database, 10, 1, 10)
        insertCteRow(database, 11, 1, 20)
        insertCteRow(database, 12, 2, 30)

        val activeOwners = select(CteContractOwners.id, CteContractOwners.name)
            .from(CteContractOwners)
            .where(CteContractOwners.active eq true)
            .asCte("active_owners")
        val activeOwnerId = activeOwners[CteContractOwners.id]
        val activeOwnerName = activeOwners[CteContractOwners.name]
        val namedOwners = select(activeOwnerId, activeOwnerName)
            .from(activeOwners)
            .where(activeOwnerId gt 0)
            .asCte("named_owners")
        val namedOwnerId = namedOwners[activeOwnerId]
        val namedOwnerName = namedOwners[activeOwnerName]

        val activeRows = with(activeOwners, namedOwners) {
            database.select(namedOwnerId, namedOwnerName)
                .from(namedOwners)
                .where(namedOwnerName eq "Ada")
                .orderBy(namedOwnerId.asc())
                .limit(1)
        }.all()
        assertEquals(1, activeRows.single()[namedOwnerId])
        assertEquals("Ada", activeRows.single()[namedOwnerName])

        val ownersWithOrders = select(CteContractOwners.id)
            .from(CteContractOwners)
            .where(
                exists(
                    select(CteContractRows.id)
                        .from(CteContractRows)
                        .where(CteContractRows.ownerId eq CteContractOwners.id),
                ),
            )
            .asCte("owners_with_orders")
        val ownerWithOrderId = ownersWithOrders[CteContractOwners.id]
        val orderedOwnerIds = with(ownersWithOrders) {
            database.select(ownerWithOrderId)
                .from(ownersWithOrders)
                .orderBy(ownerWithOrderId.asc())
        }.all().map { row -> row[ownerWithOrderId] }
        assertEquals(listOf(1, 2), orderedOwnerIds)

        val total = sum(CteContractRows.amount).alias("total")
        val totals = select(CteContractRows.ownerId, total)
            .from(CteContractRows)
            .groupBy(CteContractRows.ownerId)
            .having(total gt 15L)
            .asCte("owner_totals")
        val totalOwnerId = totals[CteContractRows.ownerId]
        val totalAmount = totals[total]
        val groupedRows = with(totals) {
            database.select(totalOwnerId, totalAmount)
                .from(totals)
                .where(totalAmount gt 25L)
                .orderBy(totalOwnerId.asc())
                .limit(1)
        }.all()
        assertEquals(1, groupedRows.single()[totalOwnerId])
        assertEquals(30L, groupedRows.single()[totalAmount])
    } finally {
        database.execute(dropTable(CteContractRows, ifExists = true))
        database.execute(dropTable(CteContractOwners, ifExists = true))
    }
}

/** Runtime contract for direct `UNION ALL` recursive CTEs and their typed result relations. */
internal suspend fun recursiveCteExpressionContract(database: SqlDatabase) {
    database.execute(dropTable(RecursiveCteContractNodes, ifExists = true))
    try {
        database.execute(createTable(RecursiveCteContractNodes))
        listOf(1 to 0, 2 to 1, 3 to 1, 4 to 2).forEach { (id, parentId) ->
            database.insertInto(RecursiveCteContractNodes).values {
                set(RecursiveCteContractNodes.id, id)
                set(RecursiveCteContractNodes.parentId, parentId)
            }.execute()
        }

        val n = literal(1).alias("n")
        val numbers = recursiveCte(name = "numbers", anchor = select(n)) { self ->
            val current = self[n]
            select(current + 1).from(self).where(current lt 10)
        }
        val number = numbers[n]
        assertEquals(
            (1..10).toList(),
            with(numbers) {
                database.select(number).from(numbers).orderBy(number.asc())
            }.all().map { row -> row[number] },
        )

        val depth = literal(0).alias("depth")
        val tree = recursiveCte(
            name = "tree",
            anchor = select(RecursiveCteContractNodes.id, RecursiveCteContractNodes.parentId, depth)
                .from(RecursiveCteContractNodes)
                .where(RecursiveCteContractNodes.id eq 1),
        ) { self ->
            val parentId = self[RecursiveCteContractNodes.id]
            val currentDepth = self[depth]
            select(RecursiveCteContractNodes.id, RecursiveCteContractNodes.parentId, currentDepth + 1)
                .from(RecursiveCteContractNodes)
                .innerJoin(self, on = RecursiveCteContractNodes.parentId eq parentId)
                .where(currentDepth lt 2)
        }
        val treeId = tree[RecursiveCteContractNodes.id]
        val treeParentId = tree[RecursiveCteContractNodes.parentId]
        val treeDepth = tree[depth]
        val treeRows = with(tree) {
            database.select(treeId, treeParentId, treeDepth)
                .from(tree)
                .orderBy(treeDepth.asc(), treeId.asc())
        }.all()
        assertEquals(
            listOf(Triple(1, 0, 0), Triple(2, 1, 1), Triple(3, 1, 1), Triple(4, 2, 2)),
            treeRows.map { row -> Triple(row[treeId], row[treeParentId], row[treeDepth]) },
        )
    } finally {
        database.execute(dropTable(RecursiveCteContractNodes, ifExists = true))
    }
}

private suspend fun insertCteOwner(database: SqlDatabase, id: Int, name: String, active: Boolean) {
    database.insertInto(CteContractOwners).values {
        set(CteContractOwners.id, id)
        set(CteContractOwners.name, name)
        set(CteContractOwners.active, active)
    }.execute()
}

private suspend fun insertCteRow(database: SqlDatabase, id: Int, ownerId: Int, amount: Int) {
    database.insertInto(CteContractRows).values {
        set(CteContractRows.id, id)
        set(CteContractRows.ownerId, ownerId)
        set(CteContractRows.amount, amount)
    }.execute()
}

private suspend fun insertSubqueryRow(database: SqlDatabase, id: Int, ownerId: Int, amount: Int) {
    database.insertInto(SubqueryContractRows).values {
        set(SubqueryContractRows.id, id)
        set(SubqueryContractRows.ownerId, ownerId)
        set(SubqueryContractRows.amount, amount)
    }.execute()
}

/** Runtime contract for CROSS and outer JOIN engines, including typed row decoding. */
internal suspend fun joinExpressionContract(database: SqlDatabase) {
    database.execute(dropTable(JoinContractUsers, ifExists = true))
    database.execute(dropTable(JoinContractOrganizations, ifExists = true))
    database.execute(dropTable(UsingJoinContractUsers, ifExists = true))
    database.execute(dropTable(UsingJoinContractOrganizations, ifExists = true))
    try {
        database.execute(createTable(JoinContractUsers))
        database.execute(createTable(JoinContractOrganizations))
        database.insertInto(JoinContractOrganizations).values {
            set(JoinContractOrganizations.id, 10)
            set(JoinContractOrganizations.name, "Ada Labs")
        }.execute()
        database.insertInto(JoinContractOrganizations).values {
            set(JoinContractOrganizations.id, 20)
            set(JoinContractOrganizations.name, "Unmatched")
        }.execute()
        database.insertInto(JoinContractUsers).values {
            set(JoinContractUsers.id, 1)
            set(JoinContractUsers.organizationId, 10)
            set(JoinContractUsers.managerId, null)
            set(JoinContractUsers.name, "Ada")
        }.execute()
        database.insertInto(JoinContractUsers).values {
            set(JoinContractUsers.id, 2)
            set(JoinContractUsers.organizationId, null)
            set(JoinContractUsers.managerId, 1)
            set(JoinContractUsers.name, "Grace")
        }.execute()

        val userId = JoinContractUsers[JoinContractUsers.id]
        val organizationName = JoinContractOrganizations.name.optional
        val leftRows = database.select(userId, organizationName)
            .from(JoinContractUsers)
            .leftJoin(
                JoinContractOrganizations,
                on = JoinContractUsers.organizationId eq JoinContractOrganizations.id,
            )
            .orderBy(userId.asc())
            .all()
        val firstUserId: Int = leftRows[0][userId]
        val firstOrganizationName: String? = leftRows[0][organizationName]
        val secondOrganizationName: String? = leftRows[1][JoinContractOrganizations.name.optional]
        assertEquals(1, firstUserId)
        assertEquals("Ada Labs", firstOrganizationName)
        assertNull(secondOrganizationName)

        val outerUserId = JoinContractUsers.id.optional
        val organizationId = JoinContractOrganizations[JoinContractOrganizations.id]
        val rightRows = database.select(outerUserId, organizationId)
            .from(JoinContractUsers)
            .rightJoin(
                JoinContractOrganizations,
                on = JoinContractUsers.organizationId eq JoinContractOrganizations.id,
            )
            .all()
        assertTrue(rightRows.any { row -> row[outerUserId] == null && row[organizationId] == 20 })

        val fullUserId = JoinContractUsers.id.optional
        val fullOrganizationId = JoinContractOrganizations.id.optional
        val fullRows = database.select(fullUserId, fullOrganizationId)
            .from(JoinContractUsers)
            .fullOuterJoin(
                JoinContractOrganizations,
                on = JoinContractUsers.organizationId eq JoinContractOrganizations.id,
            )
            .all()
        assertEquals(3, fullRows.size)

        val employee = JoinContractUsers.alias("employee")
        val manager = JoinContractUsers.alias("manager")
        val employeeName = employee[JoinContractUsers.name]
        val managerName = manager[JoinContractUsers.name].optional
        val selfJoinRows = database.select(employeeName, managerName)
            .from(employee)
            .leftJoin(manager, on = employee[JoinContractUsers.managerId] eq manager[JoinContractUsers.id])
            .orderBy(employee[JoinContractUsers.id].asc())
            .all()
        assertEquals("Ada", selfJoinRows[0][employeeName])
        assertNull(selfJoinRows[0][managerName])
        assertEquals("Grace", selfJoinRows[1][employeeName])
        assertEquals("Ada", selfJoinRows[1][managerName])

        val crossUserId = JoinContractUsers[JoinContractUsers.id]
        val crossOrganizationId = JoinContractOrganizations[JoinContractOrganizations.id]
        val crossRows = database.select(crossUserId, crossOrganizationId)
            .from(JoinContractUsers)
            .crossJoin(JoinContractOrganizations)
            .all()
        assertEquals(4, crossRows.size)

        joinUsingExpressionContract(database)
    } finally {
        database.execute(dropTable(JoinContractUsers, ifExists = true))
        database.execute(dropTable(JoinContractOrganizations, ifExists = true))
        database.execute(dropTable(UsingJoinContractUsers, ifExists = true))
        database.execute(dropTable(UsingJoinContractOrganizations, ifExists = true))
    }
}

/** Runtime contract for native USING joins, including explicit outer-side nullable projections. */
private suspend fun joinUsingExpressionContract(database: SqlDatabase) {
    database.execute(createTable(UsingJoinContractUsers))
    database.execute(createTable(UsingJoinContractOrganizations))
    database.insertInto(UsingJoinContractOrganizations).values {
        set(UsingJoinContractOrganizations.tenantId, 1)
        set(UsingJoinContractOrganizations.organizationId, 10)
        set(UsingJoinContractOrganizations.name, "Ada Labs")
    }.execute()
    database.insertInto(UsingJoinContractOrganizations).values {
        set(UsingJoinContractOrganizations.tenantId, 3)
        set(UsingJoinContractOrganizations.organizationId, 30)
        set(UsingJoinContractOrganizations.name, "Unmatched organization")
    }.execute()
    database.insertInto(UsingJoinContractUsers).values {
        set(UsingJoinContractUsers.id, 1)
        set(UsingJoinContractUsers.tenantId, 1)
        set(UsingJoinContractUsers.organizationId, 10)
        set(UsingJoinContractUsers.name, "Ada")
    }.execute()
    database.insertInto(UsingJoinContractUsers).values {
        set(UsingJoinContractUsers.id, 2)
        set(UsingJoinContractUsers.tenantId, 1)
        set(UsingJoinContractUsers.organizationId, 20)
        set(UsingJoinContractUsers.name, "Grace")
    }.execute()
    database.insertInto(UsingJoinContractUsers).values {
        set(UsingJoinContractUsers.id, 3)
        set(UsingJoinContractUsers.tenantId, 2)
        set(UsingJoinContractUsers.organizationId, 10)
        set(UsingJoinContractUsers.name, "Lin")
    }.execute()

    val userId = UsingJoinContractUsers[UsingJoinContractUsers.id]
    val organizationName = UsingJoinContractOrganizations.name
    val usingRows = database.select(userId, organizationName)
        .from(UsingJoinContractUsers)
        .innerJoinUsing(UsingJoinContractOrganizations) {
            using(UsingJoinContractUsers.tenantId, UsingJoinContractOrganizations.tenantId)
            using(UsingJoinContractUsers.organizationId, UsingJoinContractOrganizations.organizationId)
        }
        .all()
    val onRows = database.select(userId, organizationName)
        .from(UsingJoinContractUsers)
        .innerJoin(
            UsingJoinContractOrganizations,
            on = (UsingJoinContractUsers.tenantId eq UsingJoinContractOrganizations.tenantId) and
                (UsingJoinContractUsers.organizationId eq UsingJoinContractOrganizations.organizationId),
        )
        .all()
    assertEquals(listOf(1 to "Ada Labs"), usingRows.map { row -> row[userId] to row[organizationName] })
    assertEquals(
        onRows.map { row -> row[userId] to row[organizationName] }.toSet(),
        usingRows.map { row -> row[userId] to row[organizationName] }.toSet(),
    )

    val optionalOrganizationName = UsingJoinContractOrganizations.name.optional
    val leftRows = database.select(userId, optionalOrganizationName)
        .from(UsingJoinContractUsers)
        .leftJoinUsing(UsingJoinContractOrganizations) {
            using(UsingJoinContractUsers.tenantId, UsingJoinContractOrganizations.tenantId)
            using(UsingJoinContractUsers.organizationId, UsingJoinContractOrganizations.organizationId)
        }
        .orderBy(userId.asc())
        .all()
    assertEquals(
        listOf(1 to "Ada Labs", 2 to null, 3 to null),
        leftRows.map { row -> row[userId] to row[optionalOrganizationName] },
    )

    val optionalUserId = UsingJoinContractUsers.id.optional
    val rightRows = database.select(optionalUserId, organizationName)
        .from(UsingJoinContractUsers)
        .rightJoinUsing(UsingJoinContractOrganizations) {
            using(UsingJoinContractUsers.tenantId, UsingJoinContractOrganizations.tenantId)
            using(UsingJoinContractUsers.organizationId, UsingJoinContractOrganizations.organizationId)
        }
        .all()
    assertTrue(rightRows.any { row -> row[optionalUserId] == null && row[organizationName] == "Unmatched organization" })

    val optionalFullUserId = UsingJoinContractUsers.id.optional
    val optionalFullOrganizationName = UsingJoinContractOrganizations.name.optional
    val fullRows = database.select(optionalFullUserId, optionalFullOrganizationName)
        .from(UsingJoinContractUsers)
        .fullOuterJoinUsing(UsingJoinContractOrganizations) {
            using(UsingJoinContractUsers.tenantId, UsingJoinContractOrganizations.tenantId)
            using(UsingJoinContractUsers.organizationId, UsingJoinContractOrganizations.organizationId)
        }
        .all()
    assertEquals(4, fullRows.size)

    val firstUser = UsingJoinContractUsers.alias("first_using_user")
    val secondUser = UsingJoinContractUsers.alias("second_using_user")
    val firstUserId = firstUser[UsingJoinContractUsers.id]
    val secondUserId = secondUser[UsingJoinContractUsers.id]
    val selfJoinRows = database.select(firstUserId, secondUserId)
        .from(firstUser)
        .innerJoinUsing(secondUser) {
            using(
                firstUser[UsingJoinContractUsers.tenantId],
                secondUser[UsingJoinContractUsers.tenantId],
            )
            using(
                firstUser[UsingJoinContractUsers.organizationId],
                secondUser[UsingJoinContractUsers.organizationId],
            )
        }
        .orderBy(firstUserId.asc())
        .all()
    assertEquals(
        listOf(1 to 1, 2 to 2, 3 to 3),
        selfJoinRows.map { row -> row[firstUserId] to row[secondUserId] },
    )

    val organizations = select(
        UsingJoinContractOrganizations.tenantId,
        UsingJoinContractOrganizations.organizationId,
        UsingJoinContractOrganizations.name,
    ).from(UsingJoinContractOrganizations).asTable("using_join_organizations_source")
    val derivedRows = database.select(userId, organizations[UsingJoinContractOrganizations.name])
        .from(UsingJoinContractUsers)
        .innerJoinUsing(organizations) {
            using(UsingJoinContractUsers.tenantId, organizations[UsingJoinContractOrganizations.tenantId])
            using(UsingJoinContractUsers.organizationId, organizations[UsingJoinContractOrganizations.organizationId])
        }
        .all()
    assertEquals(1, derivedRows.size)

    val organizationCte = select(
        UsingJoinContractOrganizations.tenantId,
        UsingJoinContractOrganizations.organizationId,
        UsingJoinContractOrganizations.name,
    ).from(UsingJoinContractOrganizations).asCte("using_join_organizations_cte")
    val cteQuery = with(organizationCte) {
        database.select(userId, organizationCte[UsingJoinContractOrganizations.name])
            .from(UsingJoinContractUsers)
            .innerJoinUsing(organizationCte) {
                using(UsingJoinContractUsers.tenantId, organizationCte[UsingJoinContractOrganizations.tenantId])
                using(UsingJoinContractUsers.organizationId, organizationCte[UsingJoinContractOrganizations.organizationId])
            }
    }
    val cteRows = cteQuery.all()
    assertEquals(1, cteRows.size)
}

private suspend fun dev.quwac.kagiroi.sql.SqlExecution.insertRow(id: Int, name: String) {
    insertInto(ContractRows).values {
        set(ContractRows.id, id)
        set(ContractRows.name, name)
        set(ContractRows.uniqueValue, id)
        set(ContractRows.active, false)
        set(ContractRows.payload, byteArrayOf())
        set(ContractRows.nickname, null)
    }.execute()
}

private object ContractRows : Table("kagiroi_sql_contract") {
    val id = int("id").primaryKey()
    val name = string("name")
    val uniqueValue = int("unique_value").unique()
    val active = boolean("active")
    val payload = bytes("payload")
    val nickname = string("nickname").nullable()
}

private object JoinContractUsers : Table("kagiroi_join_contract_users") {
    val id = int("id").primaryKey()
    val organizationId = int("organization_id").nullable()
    val managerId = int("manager_id").nullable()
    val name = string("name")
}

private object JoinContractOrganizations : Table("kagiroi_join_contract_organizations") {
    val id = int("id").primaryKey()
    val name = string("name")
}

private object UsingJoinContractUsers : Table("kagiroi_using_join_contract_users") {
    val id = int("id").primaryKey()
    val tenantId = int("tenant_id")
    val organizationId = int("organization_id").nullable()
    val name = string("name")
}

private object UsingJoinContractOrganizations : Table("kagiroi_using_join_contract_organizations") {
    val tenantId = int("tenant_id")
    val organizationId = int("organization_id")
    val name = string("name")
}

private object ContractDdlBaseRows : Table("kagiroi_sql_ddl_contract") {
    val id = int("id").primaryKey()
    val name = string("name")
    val indexValue = int("index_value")
}

private object ContractDdlRows : Table("kagiroi_sql_ddl_contract") {
    val id = int("id").primaryKey()
    val name = string("name")
    val indexValue = int("index_value")
    val note = string("note").nullable()
}

private object FunctionRows : Table("kagiroi_sql_function_contract") {
    val id = int("id").primaryKey()
    val name = string("name")
    val nickname = string("nickname").nullable()
    val amount = int("amount")
    val ratio = double("ratio")

    init {
        checkConstraint("name_not_empty") { length(name) gt 0 }
    }
}

private object FunctionDefaultRows : Table("kagiroi_sql_function_default_contract") {
    val id = int("id").primaryKey()
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

private object CastDistinctCaseRows : Table("kagiroi_sql_cast_distinct_case_contract") {
    val id = int("id").primaryKey()
    val organizationId = int("organization_id")
    val amount = int("amount")
}

private object WindowRows : Table("kagiroi_sql_window_contract") {
    val id = int("id").primaryKey()
    val organizationId = int("organization_id")
    val amount = int("amount")
}

private object GroupingRows : Table("kagiroi_sql_grouping_contract") {
    val organizationId = string("organization_id")
    val amount = int("amount")
    val email = string("email")
    val active = boolean("active")
}

private object GroupingLimits : Table("kagiroi_sql_grouping_limits") {
    val amount = int("amount")
}

private object SubqueryContractOwners : Table("kagiroi_subquery_contract_owners") {
    val id = int("id").primaryKey()
    val name = string("name")
    val active = boolean("active")
}

private object SubqueryContractRows : Table("kagiroi_subquery_contract_rows") {
    val id = int("id").primaryKey()
    val ownerId = int("owner_id")
    val amount = int("amount")
}

private object CteContractOwners : Table("kagiroi_cte_contract_owners") {
    val id = int("id").primaryKey()
    val name = string("name")
    val active = boolean("active")
}

private object CteContractRows : Table("kagiroi_cte_contract_rows") {
    val id = int("id").primaryKey()
    val ownerId = int("owner_id")
    val amount = int("amount")
}

private object RecursiveCteContractNodes : Table("kagiroi_recursive_cte_contract_nodes") {
    val id = int("id").primaryKey()
    val parentId = int("parent_id")
}

private const val FIRST_ID = 1
private const val SECOND_ID = 2
private const val ROLLED_BACK_ID = 3
private const val DUPLICATE_ID = 4
private val PAYLOAD = byteArrayOf(0, 1, Byte.MAX_VALUE, -1)
