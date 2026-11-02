package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import kotlin.test.Test
import kotlin.test.assertEquals

class SetOperationRenderingTest {
    @Test
    fun rendersOracleMinusForTheGenericExceptOperation() {
        val left = select(OracleSetLeft.id).from(OracleSetLeft)
        val right = select(OracleSetRight.id).from(OracleSetRight)
        val renderer = SqlRenderer(OracleDialect)

        assertEquals(
            "SELECT \"oracle_set_left\".\"id\" FROM \"oracle_set_left\" UNION SELECT \"oracle_set_right\".\"id\" FROM \"oracle_set_right\"",
            renderer.render(left.union(right)).sql,
        )
        assertEquals(
            "SELECT \"oracle_set_left\".\"id\" FROM \"oracle_set_left\" UNION ALL SELECT \"oracle_set_right\".\"id\" FROM \"oracle_set_right\"",
            renderer.render(left.unionAll(right)).sql,
        )
        assertEquals(
            "SELECT \"oracle_set_left\".\"id\" FROM \"oracle_set_left\" INTERSECT SELECT \"oracle_set_right\".\"id\" FROM \"oracle_set_right\"",
            renderer.render(left.intersect(right)).sql,
        )
        assertEquals(
            "SELECT \"oracle_set_left\".\"id\" FROM \"oracle_set_left\" INTERSECT ALL SELECT \"oracle_set_right\".\"id\" FROM \"oracle_set_right\"",
            renderer.render(left.intersectAll(right)).sql,
        )
        assertEquals(
            "SELECT \"oracle_set_left\".\"id\" FROM \"oracle_set_left\" MINUS SELECT \"oracle_set_right\".\"id\" FROM \"oracle_set_right\"",
            renderer.render(left.except(right)).sql,
        )
        assertEquals(
            "SELECT \"oracle_set_left\".\"id\" FROM \"oracle_set_left\" MINUS ALL SELECT \"oracle_set_right\".\"id\" FROM \"oracle_set_right\"",
            renderer.render(left.exceptAll(right)).sql,
        )
    }
}

private object OracleSetLeft : Table("oracle_set_left") {
    val id = long("id")
}
private object OracleSetRight : Table("oracle_set_right") {
    val id = long("id")
}
