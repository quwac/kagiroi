package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.lag
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.lead
import dev.quwac.kagiroi.schema.nthValue
import dev.quwac.kagiroi.schema.ntile
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.between
import dev.quwac.kagiroi.sql.currentRow
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.unboundedFollowing
import dev.quwac.kagiroi.sql.unboundedPreceding
import dev.quwac.kagiroi.sql.window
import kotlin.test.Test
import kotlin.test.assertContains

class WindowFunctionRenderingTest {
    @Test
    fun rendersNavigationAndValueFunctions() {
        val rendered = SqlRenderer(PostgresDialect).render(
            select(
                lag(WindowRows.amount, default = 0).over { orderBy(WindowRows.id.asc()) },
                lead(WindowRows.amount, offset = 2).over { orderBy(WindowRows.id.asc()) },
                firstValue(WindowRows.amount).over { orderBy(WindowRows.id.asc()) },
                lastValue(WindowRows.amount).over {
                    orderBy(WindowRows.id.asc())
                    rows(between(unboundedPreceding, unboundedFollowing))
                },
                sum(WindowRows.amount).over {
                    orderBy(WindowRows.id.asc())
                    range(between(unboundedPreceding, currentRow))
                },
                lastValue(WindowRows.amount).over {
                    orderBy(WindowRows.id.asc())
                    range(between(unboundedPreceding, unboundedFollowing))
                },
            ).from(WindowRows),
        )

        assertContains(
            rendered.sql,
            "LAG(\"window_rows\".\"amount\", 1, $1) OVER (ORDER BY \"window_rows\".\"id\" ASC)",
        )
        assertContains(rendered.sql, "LEAD(\"window_rows\".\"amount\", 2) OVER (ORDER BY \"window_rows\".\"id\" ASC)")
        assertContains(
            rendered.sql,
            "FIRST_VALUE(\"window_rows\".\"amount\") OVER (ORDER BY \"window_rows\".\"id\" ASC)",
        )
        assertContains(
            rendered.sql,
            "LAST_VALUE(\"window_rows\".\"amount\") OVER (ORDER BY \"window_rows\".\"id\" ASC ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
        )
        assertContains(
            rendered.sql,
            "SUM(\"window_rows\".\"amount\") OVER (ORDER BY \"window_rows\".\"id\" ASC RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
        )
        assertContains(
            rendered.sql,
            "LAST_VALUE(\"window_rows\".\"amount\") OVER (ORDER BY \"window_rows\".\"id\" ASC RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
        )
    }

    @Test
    fun rendersNtileNthValueGroupsExclusionAndNamedWindow() {
        val ranking = window("ranking") {
            partitionBy(WindowRows.amount)
            orderBy(WindowRows.id.asc())
        }
        val grouped = window("grouped") {
            partitionBy(WindowRows.amount)
            orderBy(WindowRows.id.asc())
            groups(between(unboundedPreceding, currentRow))
            excludeTies()
        }
        val rendered = SqlRenderer(PostgresDialect).render(
            select(
                ntile(4).over(ranking),
                nthValue(WindowRows.amount, 3).over(grouped),
            ).from(WindowRows).window(ranking, grouped),
        )

        assertContains(rendered.sql, "NTILE(4) OVER \"ranking\"")
        assertContains(rendered.sql, "NTH_VALUE(\"window_rows\".\"amount\", 3) OVER \"grouped\"")
        assertContains(
            rendered.sql,
            "WINDOW \"ranking\" AS (PARTITION BY \"window_rows\".\"amount\" ORDER BY " +
                "\"window_rows\".\"id\" ASC), \"grouped\" AS (PARTITION BY \"window_rows\".\"amount\" ORDER BY " +
                "\"window_rows\".\"id\" ASC GROUPS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE TIES)",
        )
    }
}

private object WindowRows : Table("window_rows") {
    val id = int("id")
    val amount = int("amount")
}
