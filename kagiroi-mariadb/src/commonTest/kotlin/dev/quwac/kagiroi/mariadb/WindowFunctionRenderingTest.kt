package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.lag
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.lead
import dev.quwac.kagiroi.schema.nthValue
import dev.quwac.kagiroi.schema.ntile
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowFunctionRenderingTest {
    @Test
    fun rendersNavigationAndValueFunctionsWithoutDefault() {
        val rendered = SqlRenderer(MariaDbDialect).render(
            select(
                lag(WindowRows.amount).over { orderBy(WindowRows.id.asc()) },
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

        assertContains(rendered.sql, "LAG(`window_rows`.`amount`) OVER (ORDER BY `window_rows`.`id` ASC)")
        assertContains(rendered.sql, "LEAD(`window_rows`.`amount`, 2) OVER (ORDER BY `window_rows`.`id` ASC)")
        assertContains(rendered.sql, "FIRST_VALUE(`window_rows`.`amount`) OVER (ORDER BY `window_rows`.`id` ASC)")
        assertContains(
            rendered.sql,
            "LAST_VALUE(`window_rows`.`amount`) OVER (ORDER BY `window_rows`.`id` ASC ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
        )
        assertContains(
            rendered.sql,
            "SUM(`window_rows`.`amount`) OVER (ORDER BY `window_rows`.`id` ASC RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
        )
        assertContains(
            rendered.sql,
            "LAST_VALUE(`window_rows`.`amount`) OVER (ORDER BY `window_rows`.`id` ASC RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
        )
    }

    @Test
    fun rejectsNavigationDefaultsThatMariaDbDoesNotSupport() {
        val failure = assertFailsWith<UnsupportedCapability> {
            SqlRenderer(MariaDbDialect).render(
                select(lag(WindowRows.amount, default = 0).over { orderBy(WindowRows.id.asc()) }).from(WindowRows),
            )
        }

        assertEquals("window-navigation-default", failure.capabilityName)
    }

    @Test
    fun rendersNtileAndNthValueButRejectsGroupsExclusionAndNamedWindows() {
        val rendered = SqlRenderer(MariaDbDialect).render(
            select(
                ntile(4).over { orderBy(WindowRows.id.asc()) },
                nthValue(WindowRows.amount, 2).over { orderBy(WindowRows.id.asc()) },
            ).from(WindowRows),
        ).sql
        assertContains(rendered, "NTILE(4) OVER (ORDER BY `window_rows`.`id` ASC)")
        assertContains(rendered, "NTH_VALUE(`window_rows`.`amount`, 2) OVER (ORDER BY `window_rows`.`id` ASC)")

        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(MariaDbDialect).render(
                select(
                    sum(WindowRows.amount).over {
                        orderBy(WindowRows.id.asc())
                        groups(between(unboundedPreceding, currentRow))
                    },
                ).from(WindowRows),
            )
        }
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(MariaDbDialect).render(
                select(
                    sum(WindowRows.amount).over {
                        orderBy(WindowRows.id.asc())
                        rows(unboundedPreceding)
                        excludeTies()
                    },
                ).from(WindowRows),
            )
        }
        val named = window("named") { orderBy(WindowRows.id.asc()) }
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(
                MariaDbDialect,
            ).render(select(sum(WindowRows.amount).over(named)).from(WindowRows).window(named))
        }
    }
}

private object WindowRows : Table("window_rows") {
    val id = int("id")
    val amount = int("amount")
}
