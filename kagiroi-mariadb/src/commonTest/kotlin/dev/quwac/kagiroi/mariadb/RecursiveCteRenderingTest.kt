package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecursiveCteRenderingTest {
    @Test
    fun rendersWithRecursiveAndAnAnchorColumnList() {
        val n = literal(1).alias("n")
        val numbers = recursiveCte("numbers", select(n)) { self ->
            val current = self[n]
            select(current + 1).from(self).where(current lt 10)
        }
        val number = numbers[n]
        val rendered = SqlRenderer(MariaDbDialect).render(with(numbers) { select(number).from(numbers) })

        assertTrue(rendered.sql.startsWith("WITH RECURSIVE `numbers`(`n`) AS (SELECT"))
        assertTrue(rendered.sql.contains("CAST(? AS SIGNED) AS `n`"))
        assertTrue(rendered.sql.contains("UNION ALL SELECT"))
        assertEquals(listOf<Any>(1, 1, 10), rendered.parameters.map { it.value })
    }
}
