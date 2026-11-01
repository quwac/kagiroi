package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.nullable
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DefaultExpressionContextTest {
    @Test
    fun rejectsScalarSubqueriesBeforeDdlRendering() {
        assertFailsWith<IllegalArgumentException> {
            object : Table("subquery_default_rows") {
                val value = string("value").nullable().default(select(literal("value")).scalar())
            }
        }
    }
}
