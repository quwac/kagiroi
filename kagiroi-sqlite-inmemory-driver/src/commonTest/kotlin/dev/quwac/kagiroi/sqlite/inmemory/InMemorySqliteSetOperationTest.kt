package dev.quwac.kagiroi.sqlite.inmemory

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.sql.QueryExpression
import dev.quwac.kagiroi.sql.SingleProjection
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.exists
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemorySqliteSetOperationTest {
    @Test
    fun executesSetOperationsAndComposedQueryExpressions() = runTest {
        val database = InMemorySqliteDatabase.inMemory()
        try {
            database.installSetRows()
            database.assertNativeSetOperations()
            assertComposedSetQueryExpressions(database)
        } finally {
            database.dispose()
        }
    }

    @Test
    fun filtersASourceLessUnionAllDerivedTable() = runTest {
        val n = literal(1).alias("n")
        var numbers: QueryExpression<Int, SingleProjection> = select(n)
        for (value in 2..10) {
            numbers = numbers.unionAll(select(literal(value)))
        }
        val table = numbers.asTable("t")
        val tableN = table[n]
        val database = InMemorySqliteDatabase.inMemory()
        try {
            assertEquals(
                listOf(5),
                database.select(tableN).from(table).where(tableN eq 5).all().map { it[tableN] },
            )
        } finally {
            database.dispose()
        }
    }
}

private suspend fun InMemorySqliteDatabase.installSetRows() {
    execute(createTable(SetExecutionLeft))
    execute(createTable(SetExecutionRight))
    execute(createTable(SetExecutionProbe))
    listOf("A", "B", "C").forEach { value ->
        insertInto(SetExecutionLeft)
            .values { set(SetExecutionLeft.value, value) }
            .execute()
    }
    listOf("B", "C", "D").forEach { value ->
        insertInto(SetExecutionRight)
            .values { set(SetExecutionRight.value, value) }
            .execute()
    }
    insertInto(SetExecutionProbe)
        .values { set(SetExecutionProbe.value, "B") }
        .execute()
}

private suspend fun InMemorySqliteDatabase.assertNativeSetOperations() {
    val left = select(SetExecutionLeft.value).from(SetExecutionLeft)
    val right = select(SetExecutionRight.value).from(SetExecutionRight)
    assertEquals(
        listOf("A", "B", "C", "D"),
        left.union(right)
            .orderBy(SetExecutionLeft.value.asc())
            .all()
            .map { it[SetExecutionLeft.value] },
    )
    assertEquals(
        listOf("A", "B", "B", "C", "C", "D"),
        left.unionAll(right)
            .orderBy(SetExecutionLeft.value.asc())
            .all()
            .map { it[SetExecutionLeft.value] },
    )
    assertEquals(
        listOf("B", "C"),
        left.intersect(right)
            .orderBy(SetExecutionLeft.value.asc())
            .all()
            .map { it[SetExecutionLeft.value] },
    )
    assertEquals(
        listOf("A"),
        left.except(right).all().map { it[SetExecutionLeft.value] },
    )
}

private suspend fun assertComposedSetQueryExpressions(database: InMemorySqliteDatabase) {
    val combined = select(SetExecutionLeft.value)
        .from(SetExecutionLeft)
        .unionAll(select(SetExecutionRight.value).from(SetExecutionRight))
    assertSetDerivedTable(database, combined)
    assertSetCte(database, combined)
    assertSetSubqueries(database, combined)
}

private suspend fun assertSetDerivedTable(
    database: InMemorySqliteDatabase,
    combined: QueryExpression<String, SingleProjection>,
) {
    val combinedTable = combined.asTable("combined_values")
    val combinedValue = combinedTable[SetExecutionLeft.value]
    assertEquals(
        listOf("A", "B"),
        database.select(combinedValue)
            .from(combinedTable)
            .orderBy(combinedValue.asc())
            .limit(2)
            .all()
            .map { it[combinedValue] },
    )
}

private suspend fun assertSetCte(
    database: InMemorySqliteDatabase,
    combined: QueryExpression<String, SingleProjection>,
) {
    val combinedCte = combined.asCte("combined_values")
    val cteValue = combinedCte[SetExecutionLeft.value]
    val cteRows = with(combinedCte) {
        database.select(cteValue)
            .from(combinedCte)
            .orderBy(cteValue.asc())
            .limit(2)
    }.all()
    assertEquals(listOf("A", "B"), cteRows.map { it[cteValue] })
}

private suspend fun assertSetSubqueries(
    database: InMemorySqliteDatabase,
    combined: QueryExpression<String, SingleProjection>,
) {
    val scalar = select(SetExecutionLeft.value)
        .from(SetExecutionLeft)
        .where(SetExecutionLeft.value eq "A")
        .union(
            select(SetExecutionRight.value)
                .from(SetExecutionRight)
                .where(SetExecutionRight.value eq "A"),
        ).scalar()
    val scalarValue = scalar.alias("scalar_value")
    assertEquals(
        "A",
        database.select(scalarValue)
            .from(SetExecutionProbe)
            .where(SetExecutionProbe.value eq "B")
            .one()[scalarValue],
    )
    assertEquals(
        listOf("B"),
        database.select(SetExecutionProbe.value)
            .from(SetExecutionProbe)
            .where(SetExecutionProbe.value.inSubquery(combined))
            .all()
            .map { it[SetExecutionProbe.value] },
    )
    assertEquals(
        listOf("B"),
        database.select(SetExecutionProbe.value)
            .from(SetExecutionProbe)
            .where(exists(combined))
            .all()
            .map { it[SetExecutionProbe.value] },
    )
}

private object SetExecutionLeft : Table("set_execution_left") {
    val value = string("value")
}

private object SetExecutionRight : Table("set_execution_right") {
    val value = string("value")
}

private object SetExecutionProbe : Table("set_execution_probe") {
    val value = string("value")
}
