package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.nullsFirst
import dev.quwac.kagiroi.sql.nullsLast
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PGliteSetOperationIntegrationTest {
    @Test
    fun executesNativePostgresSetOperationsAndOuterPagination() = runTest {
        val database = PGliteDatabase.inMemory()
        try {
            database.execute(createTable(PGliteSetLeft))
            database.execute(createTable(PGliteSetRight))
            listOf("A", "B", "C").forEach { value ->
                database.insertInto(PGliteSetLeft).values { set(PGliteSetLeft.value, value) }.execute()
            }
            listOf("B", "C", "D").forEach { value ->
                database.insertInto(PGliteSetRight).values { set(PGliteSetRight.value, value) }.execute()
            }

            val left = database.select(PGliteSetLeft.value).from(PGliteSetLeft)
            val right = database.select(PGliteSetRight.value).from(PGliteSetRight)
            assertEquals(
                listOf("A", "B", "C", "D"),
                left.union(right).orderBy(PGliteSetLeft.value.asc()).all().map { it[PGliteSetLeft.value] },
            )
            assertEquals(
                listOf("A", "B", "B", "C", "C", "D"),
                left.unionAll(right).orderBy(PGliteSetLeft.value.asc()).all().map { it[PGliteSetLeft.value] },
            )
            assertEquals(
                listOf("B", "C"),
                left.intersect(right).orderBy(PGliteSetLeft.value.asc()).all().map { it[PGliteSetLeft.value] },
            )
            assertEquals(listOf("A"), left.except(right).all().map { it[PGliteSetLeft.value] })
            assertEquals(
                listOf("B", "B", "C", "C"),
                left.unionAll(right).orderBy(PGliteSetLeft.value.asc()).limit(4).offset(1).all()
                    .map { it[PGliteSetLeft.value] },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun delegatesAllSetOperationBagSemanticsToPGlite() = runTest {
        val database = PGliteDatabase.inMemory()
        try {
            createAllSetOperationTables(database)
            insertAllSetOperationFixtures(database)
            assertEquals(
                listOf(null, null, 1, 1, 1, 2, 3),
                database.select(PGliteBagLeft.value).from(PGliteBagLeft)
                    .orderBy(PGliteBagLeft.value.asc().nullsFirst())
                    .all()
                    .map { it[PGliteBagLeft.value] },
            )
            assertEquals(
                listOf(1, 1, 1, 2, 3, null, null),
                database.select(PGliteBagLeft.value).from(PGliteBagLeft)
                    .orderBy(PGliteBagLeft.value.asc().nullsLast())
                    .all()
                    .map { it[PGliteBagLeft.value] },
            )
            assertNumberSetOperationSemantics(database)
            assertStringSetOperationSemantics(database)
            assertPairSetOperationSemantics(database)
            assertSetOperationDerivedRelations(database)
        } finally {
            database.close()
        }
    }
}

private suspend fun createAllSetOperationTables(database: PGliteDatabase) {
    listOf(
        PGliteBagLeft,
        PGliteBagRight,
        PGliteStringLeft,
        PGliteStringRight,
        PGlitePairLeft,
        PGlitePairRight,
    ).forEach { database.execute(createTable(it)) }
}

private suspend fun insertAllSetOperationFixtures(database: PGliteDatabase) {
    listOf(1, 1, 1, 2, 3, null, null).forEach { value ->
        database.insertInto(PGliteBagLeft).values { set(PGliteBagLeft.value, value) }.execute()
    }
    listOf(1, 1, 2, 2, 4, null).forEach { value ->
        database.insertInto(PGliteBagRight).values { set(PGliteBagRight.value, value) }.execute()
    }
    listOf("alpha", "alpha", "alpha", "beta", "gamma").forEach { value ->
        database.insertInto(PGliteStringLeft).values { set(PGliteStringLeft.value, value) }.execute()
    }
    listOf("alpha", "alpha", "beta", "beta", "delta").forEach { value ->
        database.insertInto(PGliteStringRight).values { set(PGliteStringRight.value, value) }.execute()
    }
    listOf(1 to "same", 1 to "same", 1 to "left-only").forEach { (id, label) ->
        database.insertInto(PGlitePairLeft).values {
            set(PGlitePairLeft.id, id)
            set(PGlitePairLeft.label, label)
        }.execute()
    }
    listOf(1 to "same", 1 to "same", 1 to "same", 2 to "right-only").forEach { (id, label) ->
        database.insertInto(PGlitePairRight).values {
            set(PGlitePairRight.id, id)
            set(PGlitePairRight.label, label)
        }.execute()
    }
}

private suspend fun assertNumberSetOperationSemantics(database: PGliteDatabase) {
    val left = database.select(PGliteBagLeft.value).from(PGliteBagLeft)
    val right = database.select(PGliteBagRight.value).from(PGliteBagRight)
    assertEquals(mapOf<Int?, Int>(1 to 2, 2 to 1, null to 1), left.intersectAll(right).values(PGliteBagLeft.value))
    assertEquals(mapOf<Int?, Int>(1 to 1, 3 to 1, null to 1), left.exceptAll(right).values(PGliteBagLeft.value))
    assertEquals(setOf<Int?>(1, 2, null), left.intersect(right).all().map { it[PGliteBagLeft.value] }.toSet())
    assertEquals(setOf(3), left.except(right).all().map { it[PGliteBagLeft.value] }.toSet())
}

private suspend fun assertStringSetOperationSemantics(database: PGliteDatabase) {
    val left = database.select(PGliteStringLeft.value).from(PGliteStringLeft)
    val right = database.select(PGliteStringRight.value).from(PGliteStringRight)
    val common = left.intersectAll(right)
    assertEquals(mapOf("alpha" to 2, "beta" to 1), common.values(PGliteStringLeft.value))
    assertEquals(mapOf("alpha" to 1, "gamma" to 1), left.exceptAll(right).values(PGliteStringLeft.value))
    assertEquals(
        listOf("alpha", "beta"),
        common.orderBy(PGliteStringLeft.value.asc()).limit(2).offset(1).all().map { it[PGliteStringLeft.value] },
    )
    assertEquals(
        mapOf("alpha" to 2, "delta" to 1),
        left.unionAll(right).intersectAll(right).exceptAll(
            database.select(PGliteStringRight.value).from(PGliteStringRight).where(PGliteStringRight.value eq "beta"),
        ).values(PGliteStringLeft.value),
    )
    assertEquals(
        mapOf("alpha" to 2),
        database.select(PGliteStringLeft.value).from(PGliteStringLeft).where(PGliteStringLeft.value eq "alpha")
            .intersectAll(
                database.select(PGliteStringRight.value).from(PGliteStringRight)
                    .where(PGliteStringRight.value eq "alpha"),
            ).values(PGliteStringLeft.value),
    )
}

private suspend fun assertPairSetOperationSemantics(database: PGliteDatabase) {
    val left = database.select(PGlitePairLeft.id, PGlitePairLeft.label).from(PGlitePairLeft)
    val right = database.select(PGlitePairRight.id, PGlitePairRight.label).from(PGlitePairRight)
    assertEquals(
        mapOf((1 to "same") to 2),
        left.intersectAll(right).all().map { it[PGlitePairLeft.id] to it[PGlitePairLeft.label] }.multiplicities(),
    )
    assertEquals(
        mapOf((1 to "left-only") to 1),
        left.exceptAll(right).all().map { it[PGlitePairLeft.id] to it[PGlitePairLeft.label] }.multiplicities(),
    )
}

private suspend fun assertSetOperationDerivedRelations(database: PGliteDatabase) {
    val expression = select(PGliteStringLeft.value).from(PGliteStringLeft)
        .intersectAll(select(PGliteStringRight.value).from(PGliteStringRight))
    val table = expression.asTable("common_strings")
    val tableValue = table[PGliteStringLeft.value]
    assertEquals(
        listOf("alpha", "alpha", "beta"),
        database.select(tableValue).from(table).orderBy(tableValue.asc()).all().map { it[tableValue] },
    )
    val cte = expression.asCte("common_strings")
    val cteValue = cte[PGliteStringLeft.value]
    assertEquals(
        listOf("alpha", "alpha", "beta"),
        with(cte) { database.select(cteValue).from(cte).orderBy(cteValue.asc()) }.all().map { it[cteValue] },
    )
}

private suspend fun <T> dev.quwac.kagiroi.sql.ExecutableSetOperationQuery<T, *>.values(
    column: dev.quwac.kagiroi.schema.Column<T>,
): Map<T, Int> = all().map { it[column] }.multiplicities()

private object PGliteSetLeft : Table("pglite_set_left") {
    val value = string("value")
}

private object PGliteSetRight : Table("pglite_set_right") {
    val value = string("value")
}

private object PGliteBagLeft : Table("pglite_bag_left") {
    val value = int("value").nullable()
}

private object PGliteBagRight : Table("pglite_bag_right") {
    val value = int("value").nullable()
}

private object PGliteStringLeft : Table("pglite_string_left") {
    val value = string("value")
}

private object PGliteStringRight : Table("pglite_string_right") {
    val value = string("value")
}

private object PGlitePairLeft : Table("pglite_pair_left") {
    val id = int("id")
    val label = string("label")
}

private object PGlitePairRight : Table("pglite_pair_right") {
    val id = int("id")
    val label = string("label")
}

private fun <T> List<T>.multiplicities(): Map<T, Int> = groupingBy { it }.eachCount()
