package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.nullsLast
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import kotlin.test.Test
import kotlin.test.assertEquals

class SetOperationRenderingTest {
    @Test
    fun rendersNativePostgresSetOperators() {
        val left = select(PostgresSetLeft.id).from(PostgresSetLeft)
        val right = select(PostgresSetRight.id).from(PostgresSetRight)
        val renderer = SqlRenderer(PostgresDialect)

        assertEquals(
            "SELECT \"postgres_set_left\".\"id\" FROM \"postgres_set_left\" UNION SELECT \"postgres_set_right\".\"id\" FROM \"postgres_set_right\"",
            renderer.render(left.union(right)).sql,
        )
        assertEquals(
            "SELECT \"postgres_set_left\".\"id\" FROM \"postgres_set_left\" UNION ALL SELECT \"postgres_set_right\".\"id\" FROM \"postgres_set_right\"",
            renderer.render(left.unionAll(right)).sql,
        )
        assertEquals(
            "SELECT \"postgres_set_left\".\"id\" FROM \"postgres_set_left\" INTERSECT SELECT \"postgres_set_right\".\"id\" FROM \"postgres_set_right\"",
            renderer.render(left.intersect(right)).sql,
        )
        assertEquals(
            "SELECT \"postgres_set_left\".\"id\" FROM \"postgres_set_left\" INTERSECT ALL SELECT \"postgres_set_right\".\"id\" FROM \"postgres_set_right\"",
            renderer.render(left.intersectAll(right)).sql,
        )
        assertEquals(
            "SELECT \"postgres_set_left\".\"id\" FROM \"postgres_set_left\" EXCEPT SELECT \"postgres_set_right\".\"id\" FROM \"postgres_set_right\"",
            renderer.render(left.except(right)).sql,
        )
        assertEquals(
            "SELECT \"postgres_set_left\".\"id\" FROM \"postgres_set_left\" EXCEPT ALL SELECT \"postgres_set_right\".\"id\" FROM \"postgres_set_right\"",
            renderer.render(left.exceptAll(right)).sql,
        )
        assertEquals(
            "SELECT \"postgres_set_left\".\"id\" FROM \"postgres_set_left\" UNION SELECT " +
                "\"postgres_set_right\".\"id\" FROM \"postgres_set_right\" " +
                "ORDER BY \"id\" ASC NULLS LAST",
            renderer.render(left.union(right).orderBy(PostgresSetLeft.id.asc().nullsLast())).sql,
        )
    }
}

private object PostgresSetLeft : Table("postgres_set_left") {
    val id = long("id")
}
private object PostgresSetRight : Table("postgres_set_right") {
    val id = long("id")
}
