package dev.quwac.kagiroi.sqlserver

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
    fun rendersSqlServerCastDistinctAndSearchedCase() {
        val category = case<String> {
            whenThen(CastValues.active eq true, "yes")
            elseValue("no")
        }

        val rendered = SqlRenderer(SqlServerDialect).render(
            select(cast(CastValues.id, stringType()), category).distinct().from(CastValues),
        )

        assertEquals(
            "SELECT DISTINCT CAST([cast_values].[id] AS NVARCHAR(MAX)), " +
                "CASE WHEN [cast_values].[active] = @kb1 THEN @kb2 ELSE @kb3 END FROM [cast_values]",
            rendered.sql,
        )
        assertEquals(listOf(true, "yes", "no"), rendered.parameters.map { it.value })
    }
}

private object CastValues : Table("cast_values") {
    val id = long("id")
    val active = boolean("active")
}
