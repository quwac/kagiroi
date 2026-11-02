package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SetOperationRenderingTest {
    @Test
    fun rendersNativeSqliteSetOperators() {
        val left = select(SqliteSetLeft.id).from(SqliteSetLeft)
        val right = select(SqliteSetRight.id).from(SqliteSetRight)
        val renderer = SqlRenderer(SqliteDialect)

        assertEquals(
            "SELECT \"sqlite_set_left\".\"id\" FROM \"sqlite_set_left\" UNION SELECT \"sqlite_set_right\".\"id\" FROM \"sqlite_set_right\"",
            renderer.render(left.union(right)).sql,
        )
        assertEquals(
            "SELECT \"sqlite_set_left\".\"id\" FROM \"sqlite_set_left\" UNION ALL SELECT \"sqlite_set_right\".\"id\" FROM \"sqlite_set_right\"",
            renderer.render(left.unionAll(right)).sql,
        )
        assertEquals(
            "SELECT \"sqlite_set_left\".\"id\" FROM \"sqlite_set_left\" INTERSECT SELECT \"sqlite_set_right\".\"id\" FROM \"sqlite_set_right\"",
            renderer.render(left.intersect(right)).sql,
        )
        assertEquals(
            "SELECT \"sqlite_set_left\".\"id\" FROM \"sqlite_set_left\" EXCEPT SELECT \"sqlite_set_right\".\"id\" FROM \"sqlite_set_right\"",
            renderer.render(left.except(right)).sql,
        )
        assertEquals(
            "intersect-all",
            assertFailsWith<UnsupportedCapability> { renderer.render(left.intersectAll(right)) }.capabilityName,
        )
        assertEquals(
            "except-all",
            assertFailsWith<UnsupportedCapability> { renderer.render(left.exceptAll(right)) }.capabilityName,
        )
    }
}

private object SqliteSetLeft : Table("sqlite_set_left") {
    val id = long("id")
}
private object SqliteSetRight : Table("sqlite_set_right") {
    val id = long("id")
}
