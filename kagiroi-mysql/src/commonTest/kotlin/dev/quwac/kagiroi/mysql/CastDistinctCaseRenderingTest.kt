package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.case
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.elseValue
import dev.quwac.kagiroi.schema.stringType
import dev.quwac.kagiroi.schema.whenThen
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.distinct
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertEquals

class CastDistinctCaseRenderingTest {
    @Test
    fun rendersMySqlCastDistinctAndSearchedCase() {
        val category = case<String> {
            whenThen(CastValues.active eq true, "yes")
            elseValue("no")
        }

        val rendered = SqlRenderer(MySqlDialect).render(
            select(cast(CastValues.id, stringType()), category).distinct().from(CastValues),
        )

        assertEquals(
            "SELECT DISTINCT CAST(`cast_values`.`id` AS CHAR), " +
                "CASE WHEN `cast_values`.`active` = ? THEN ? ELSE ? END FROM `cast_values`",
            rendered.sql,
        )
        assertEquals(listOf(true, "yes", "no"), rendered.parameters.map { it.value })
    }
}

private object CastValues : Table("cast_values") {
    val id = long("id")
    val active = boolean("active")
}
