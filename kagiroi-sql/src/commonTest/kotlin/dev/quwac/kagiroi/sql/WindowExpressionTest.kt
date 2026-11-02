package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.avg
import dev.quwac.kagiroi.schema.coalesce
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.denseRank
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.isNotNull
import dev.quwac.kagiroi.schema.lag
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.lead
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.max
import dev.quwac.kagiroi.schema.min
import dev.quwac.kagiroi.schema.nthValue
import dev.quwac.kagiroi.schema.ntile
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.schema.rank
import dev.quwac.kagiroi.schema.rowNumber
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.schema.transform
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowExpressionTest {
    @Test
    fun rendersDistributionWindowsAsNonNullDoublesAndRequiresOrderWithoutFrames() {
        val percentRank = percentRank()
            .over {
                partitionBy(WindowOrders.organizationId)
                orderBy(WindowOrders.amount.asc())
            }
            .alias("percent_rank")
        val cumeDist = cumeDist()
            .over { orderBy(WindowOrders.amount.asc()) }
            .alias("cume_dist")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(percentRank, cumeDist).from(WindowOrders),
        )

        assertEquals(
            "SELECT PERCENT_RANK() OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
                "ORDER BY \"window_orders\".\"amount\" ASC) AS \"percent_rank\", " +
                "CUME_DIST() OVER (ORDER BY \"window_orders\".\"amount\" ASC) AS \"cume_dist\" " +
                "FROM \"window_orders\"",
            rendered.sql,
        )
        assertEquals(0.5, sqlRow(listOf(percentRank), listOf<Any?>(0.5))[percentRank])
        assertFailsWith<IllegalArgumentException> { percentRank().over() }
        assertFailsWith<IllegalArgumentException> {
            cumeDist().over {
                orderBy(WindowOrders.amount.asc())
                rows(unboundedPreceding)
            }
        }
    }

    @Test
    fun acceptsScalarFunctionsInWindowSpecifications() {
        val rowNumber = rowNumber().over {
            partitionBy(lower(WindowOrders.status))
            orderBy(WindowOrders.id.asc())
        }.alias("normalized_partition_rank")

        assertEquals(
            "SELECT ROW_NUMBER() OVER (PARTITION BY LOWER(\"window_orders\".\"status\") " +
                "ORDER BY \"window_orders\".\"id\" ASC) AS \"normalized_partition_rank\" FROM \"window_orders\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(rowNumber).from(WindowOrders)).sql,
        )
    }

    @Test
    fun rendersRankingPartitionOrderingAndTypedAlias() {
        val rowNumber = rowNumber()
            .over {
                partitionBy(WindowOrders.organizationId, WindowOrders.status)
                orderBy(WindowOrders.createdAt.desc(), WindowOrders.id.asc())
            }.alias("row_number")
        val rank = rank().over { orderBy(WindowOrders.score.desc()) }.alias("rank")
        val denseRank = denseRank().over { orderBy(WindowOrders.score.desc()) }.alias("dense_rank")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(WindowOrders.id, rowNumber, rank, denseRank)
                .from(WindowOrders)
                .orderBy(rowNumber.desc()),
        )

        assertEquals(
            "SELECT \"window_orders\".\"id\", ROW_NUMBER() OVER (PARTITION BY \"window_orders\".\"organization_id\", " +
                "\"window_orders\".\"status\" ORDER BY \"window_orders\".\"created_at\" DESC, " +
                "\"window_orders\".\"id\" ASC) AS \"row_number\", RANK() OVER (ORDER BY " +
                "\"window_orders\".\"score\" DESC) AS \"rank\", DENSE_RANK() OVER (ORDER BY " +
                "\"window_orders\".\"score\" DESC) AS \"dense_rank\" FROM \"window_orders\" " +
                "ORDER BY ROW_NUMBER() OVER (PARTITION BY \"window_orders\".\"organization_id\", " +
                "\"window_orders\".\"status\" ORDER BY \"window_orders\".\"created_at\" DESC, " +
                "\"window_orders\".\"id\" ASC) DESC",
            rendered.sql,
        )
        val row = sqlRow(listOf(rowNumber), listOf<Any?>(1L))
        assertEquals(1L, row[rowNumber])
    }

    @Test
    fun rendersAggregateWindowsEmptyOverAndRowsFrames() {
        val allRows = count().over().alias("all_rows")
        val partitionTotal = sum(WindowOrders.amount).over {
            partitionBy(WindowOrders.organizationId)
        }.alias("partition_total")
        val runningTotal = sum(WindowOrders.amount).over {
            partitionBy(WindowOrders.organizationId)
            orderBy(WindowOrders.createdAt.asc())
            rows(between(unboundedPreceding, currentRow))
        }.alias("running_total")
        val movingAverage = avg(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
            rows(between(preceding(5), following(1)))
        }.alias("moving_average")
        val smallest = min(WindowOrders.amount).over().alias("smallest")
        val largest = max(WindowOrders.amount).over().alias("largest")
        val fallback = coalesce(sum(WindowOrders.amount).over(), literal(0L)).alias("fallback_total")
        val constantPartition = count().over { partitionBy(literal(1)) }.alias("constant_partition")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(allRows, partitionTotal, runningTotal, movingAverage, smallest, largest, fallback, constantPartition)
                .from(WindowOrders),
        )

        assertEquals(
            "SELECT COUNT(*) OVER () AS \"all_rows\", SUM(\"window_orders\".\"amount\") OVER " +
                "(PARTITION BY \"window_orders\".\"organization_id\") AS \"partition_total\", " +
                "SUM(\"window_orders\".\"amount\") OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
                "ORDER BY \"window_orders\".\"created_at\" ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) " +
                "AS \"running_total\", AVG(\"window_orders\".\"amount\") OVER (ORDER BY " +
                "\"window_orders\".\"created_at\" ASC ROWS BETWEEN 5 PRECEDING AND 1 FOLLOWING) " +
                "AS \"moving_average\", MIN(\"window_orders\".\"amount\") OVER () AS \"smallest\", " +
                "MAX(\"window_orders\".\"amount\") OVER () AS \"largest\", " +
                "COALESCE(SUM(\"window_orders\".\"amount\") OVER (), ?) AS \"fallback_total\", " +
                "COUNT(*) OVER (PARTITION BY ?) AS \"constant_partition\" " +
                "FROM \"window_orders\"",
            rendered.sql,
        )
        assertEquals(2, rendered.parameters.size)
        assertEquals(0L, rendered.parameters[0].value)
        assertEquals(1, rendered.parameters[1].value)
    }

    @Test
    fun rendersLogicalRangeFramesForAggregateAndValueWindows() {
        val rangeCount = count().over {
            orderBy(WindowOrders.createdAt.asc())
            range(unboundedPreceding)
        }
        val rangeTotal = sum(WindowOrders.amount).over {
            partitionBy(WindowOrders.organizationId)
            orderBy(WindowOrders.createdAt.asc())
            range(between(unboundedPreceding, currentRow))
        }
        val rangeAverage = avg(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
            range(currentRow)
        }
        val rangeMinimum = min(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
            range(between(currentRow, unboundedFollowing))
        }
        val rangeMaximum = max(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
            range(between(unboundedPreceding, unboundedFollowing))
        }
        val rangeFirst = firstValue(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
            range(between(unboundedPreceding, currentRow))
        }
        val rangeLast = lastValue(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
            range(between(unboundedPreceding, unboundedFollowing))
        }

        listOf(rangeCount, rangeTotal, rangeAverage, rangeMinimum, rangeMaximum, rangeFirst, rangeLast)
            .forEach { expression -> assertEquals(WindowFrameUnit.Range, expression.specification.frame?.unit) }

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(rangeCount, rangeTotal, rangeAverage, rangeMinimum, rangeMaximum, rangeFirst, rangeLast)
                .from(WindowOrders),
        ).sql

        assertRangeSql(rendered)
    }

    @Test
    fun rendersTypedNavigationAndValueWindows() {
        val previousExpression: dev.quwac.kagiroi.schema.SqlExpression<Int?> = lag(WindowOrders.amount)
            .over {
                partitionBy(WindowOrders.organizationId)
                orderBy(WindowOrders.createdAt.asc())
            }
        val previousAmount = previousExpression.alias("previous_amount")
        val nextAmount = lead(WindowOrders.amount, offset = 2).over {
            orderBy(WindowOrders.createdAt.asc())
        }.alias("next_amount")
        val previousWithDefault = lag(WindowOrders.amount, default = 0).over {
            orderBy(WindowOrders.createdAt.asc())
        }.alias("previous_with_default")
        val firstExpression: dev.quwac.kagiroi.schema.SqlExpression<Int> = firstValue(WindowOrders.amount)
            .over {
                partitionBy(WindowOrders.organizationId)
                orderBy(WindowOrders.createdAt.asc())
            }
        val firstAmount = firstExpression.alias("first_amount")
        val lastAmount = lastValue(WindowOrders.amount).over {
            partitionBy(WindowOrders.organizationId)
            orderBy(WindowOrders.createdAt.asc())
            rows(between(unboundedPreceding, unboundedFollowing))
        }.alias("last_amount")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(previousAmount, nextAmount, previousWithDefault, firstAmount, lastAmount).from(WindowOrders),
        )

        assertEquals(
            "SELECT LAG(\"window_orders\".\"amount\") OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
                "ORDER BY \"window_orders\".\"created_at\" ASC) AS \"previous_amount\", " +
                "LEAD(\"window_orders\".\"amount\", 2) OVER (ORDER BY \"window_orders\".\"created_at\" ASC) " +
                "AS \"next_amount\", LAG(\"window_orders\".\"amount\", 1, ?) OVER (ORDER BY " +
                "\"window_orders\".\"created_at\" ASC) AS \"previous_with_default\", " +
                "FIRST_VALUE(\"window_orders\".\"amount\") OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
                "ORDER BY \"window_orders\".\"created_at\" ASC) AS \"first_amount\", " +
                "LAST_VALUE(\"window_orders\".\"amount\") OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
                "ORDER BY \"window_orders\".\"created_at\" ASC ROWS BETWEEN UNBOUNDED PRECEDING AND " +
                "UNBOUNDED FOLLOWING) AS \"last_amount\" FROM \"window_orders\"",
            rendered.sql,
        )
        assertEquals(listOf(0), rendered.parameters.map { it.value })
        val row = sqlRow(listOf(previousAmount, firstAmount), listOf<Any?>(null, 10))
        assertEquals(null, row[previousAmount])
        assertEquals(10, row[firstAmount])
    }

    @Test
    fun rendersNtileAndNullableNthValueWithFrameSemantics() {
        val quartile: dev.quwac.kagiroi.schema.SqlExpression<Long> = ntile(4).over {
            partitionBy(WindowOrders.organizationId)
            orderBy(WindowOrders.score.desc())
        }
        val third: dev.quwac.kagiroi.schema.SqlExpression<Int?> = nthValue(WindowOrders.amount, 3).over {
            orderBy(WindowOrders.createdAt.asc())
            groups(between(preceding(1), currentRow))
        }
        val mapped: dev.quwac.kagiroi.schema.SqlExpression<WindowAccountId?> =
            nthValue(MappedWindowOrders.accountId, 1).over { orderBy(MappedWindowOrders.id.asc()) }

        val renderer = SqlRenderer(DoubleQuotedQuestionMarkDialect)
        val rendered = renderer.render(select(quartile.alias("quartile"), third.alias("third")).from(WindowOrders))

        assertContains(
            rendered.sql,
            "NTILE(4) OVER (PARTITION BY \"window_orders\".\"organization_id\" ORDER BY \"window_orders\".\"score\" DESC)",
        )
        assertContains(
            rendered.sql,
            "NTH_VALUE(\"window_orders\".\"amount\", 3) OVER (ORDER BY \"window_orders\".\"created_at\" ASC " +
                "GROUPS BETWEEN 1 PRECEDING AND CURRENT ROW)",
        )
        assertContains(
            renderer.render(select(mapped.alias("mapped")).from(MappedWindowOrders)).sql,
            "NTH_VALUE(\"mapped_window_orders\".\"account_id\", 1) OVER (ORDER BY \"mapped_window_orders\".\"id\" ASC)",
        )
    }

    @Test
    fun rendersGroupsAndEveryFrameExclusion() {
        val current = sum(WindowOrders.amount).over {
            orderBy(WindowOrders.score.asc())
            groups(between(unboundedPreceding, currentRow))
            excludeCurrentRow()
        }
        val group = sum(WindowOrders.amount).over {
            orderBy(WindowOrders.score.asc())
            rows(between(unboundedPreceding, currentRow))
            excludeGroup()
        }
        val ties = nthValue(WindowOrders.amount, 1).over {
            orderBy(WindowOrders.score.asc())
            range(between(unboundedPreceding, currentRow))
            excludeTies()
        }
        val noOthers = sum(WindowOrders.amount).over {
            orderBy(WindowOrders.score.asc())
            rows(unboundedPreceding)
            excludeNoOthers()
        }

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(current, group, ties, noOthers).from(WindowOrders),
        ).sql

        assertContains(rendered, "GROUPS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE CURRENT ROW")
        assertContains(rendered, "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE GROUP")
        assertContains(rendered, "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE TIES")
        assertContains(rendered, "ROWS UNBOUNDED PRECEDING EXCLUDE NO OTHERS")
    }

    @Test
    fun sharesOneSpecificationBetweenInlineAndNamedWindows() {
        val organizationWindow = window("organization_window") {
            partitionBy(WindowOrders.organizationId)
            orderBy(WindowOrders.createdAt.asc())
            groups(between(unboundedPreceding, currentRow))
            excludeTies()
        }
        val namedSum = sum(WindowOrders.amount).over(organizationWindow).alias("sum_amount")
        val namedAverage = avg(WindowOrders.amount).over(organizationWindow).alias("average_amount")
        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(namedSum, namedAverage)
                .from(WindowOrders)
                .window(organizationWindow)
                .orderBy(WindowOrders.id.asc()),
        ).sql

        assertEquals(
            "SELECT SUM(\"window_orders\".\"amount\") OVER \"organization_window\" AS \"sum_amount\", " +
                "AVG(\"window_orders\".\"amount\") OVER \"organization_window\" AS \"average_amount\" " +
                "FROM \"window_orders\" WINDOW \"organization_window\" AS (PARTITION BY " +
                "\"window_orders\".\"organization_id\" ORDER BY \"window_orders\".\"created_at\" ASC " +
                "GROUPS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE TIES) " +
                "ORDER BY \"window_orders\".\"id\" ASC",
            rendered,
        )
    }

    @Test
    fun rendersNamedWindowParametersAtTheWindowClausePosition() {
        val parameterized = window("parameterized") {
            partitionBy(literal(99))
            orderBy(WindowOrders.id.asc())
        }
        val total = sum(WindowOrders.amount).over(parameterized)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(total)
                .from(WindowOrders)
                .where(WindowOrders.id gt 5L)
                .window(parameterized),
        )

        assertEquals(
            "SELECT SUM(\"window_orders\".\"amount\") OVER \"parameterized\" FROM \"window_orders\" " +
                "WHERE \"window_orders\".\"id\" > ? WINDOW \"parameterized\" AS (PARTITION BY ? " +
                "ORDER BY \"window_orders\".\"id\" ASC)",
            rendered.sql,
        )
        assertEquals(listOf<Any>(5L, 99), rendered.parameters.map { it.value })
    }

    @Test
    fun preservesValueWindowFrameSemanticsWithoutInjection() {
        val defaultFrame = lastValue(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
        }
        val wholePartition = lastValue(WindowOrders.amount).over {
            orderBy(WindowOrders.createdAt.asc())
            rows(between(unboundedPreceding, unboundedFollowing))
        }

        val renderer = SqlRenderer(DoubleQuotedQuestionMarkDialect)
        assertEquals(
            "SELECT LAST_VALUE(\"window_orders\".\"amount\") OVER (ORDER BY \"window_orders\".\"created_at\" ASC) " +
                "FROM \"window_orders\"",
            renderer.render(select(defaultFrame).from(WindowOrders)).sql,
        )
        assertEquals(
            "SELECT LAST_VALUE(\"window_orders\".\"amount\") OVER (ORDER BY \"window_orders\".\"created_at\" ASC " +
                "ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM \"window_orders\"",
            renderer.render(select(wholePartition).from(WindowOrders)).sql,
        )
    }

    @Test
    fun preservesTransformedValueMappingsForNavigationDefaultsAndResults() {
        val previous: dev.quwac.kagiroi.schema.SqlExpression<WindowAccountId?> = lag(MappedWindowOrders.accountId)
            .over { orderBy(MappedWindowOrders.id.asc()) }
        val previousAlias = previous.alias("previous_account_id")
        val fallback = WindowAccountId("fallback")
        val previousWithDefault = lag(MappedWindowOrders.accountId, default = fallback)
            .over { orderBy(MappedWindowOrders.id.asc()) }
            .alias("previous_with_default")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(previousAlias, previousWithDefault).from(MappedWindowOrders),
        )

        assertEquals(
            "SELECT LAG(\"mapped_window_orders\".\"account_id\") OVER (ORDER BY \"mapped_window_orders\".\"id\" ASC) " +
                "AS \"previous_account_id\", LAG(\"mapped_window_orders\".\"account_id\", 1, ?) OVER (ORDER BY " +
                "\"mapped_window_orders\".\"id\" ASC) AS \"previous_with_default\" FROM \"mapped_window_orders\"",
            rendered.sql,
        )
        assertEquals("fallback", rendered.parameters.single().value)
        assertEquals(
            WindowAccountId("account-1"),
            sqlRow(listOf(previousAlias), listOf<Any?>("account-1"))[previousAlias],
        )
    }

    @Test
    fun preservesEnumMappingsForNavigationDefaultsAndResults() {
        val previous: dev.quwac.kagiroi.schema.SqlExpression<WindowStatus?> = lag(EnumWindowOrders.status)
            .over { orderBy(EnumWindowOrders.id.asc()) }
        val previousAlias = previous.alias("previous_status")
        val previousWithDefault = lag(EnumWindowOrders.status, default = WindowStatus.Pending)
            .over { orderBy(EnumWindowOrders.id.asc()) }
            .alias("previous_with_default")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(previousAlias, previousWithDefault).from(EnumWindowOrders),
        )

        assertEquals(
            "SELECT LAG(\"enum_window_orders\".\"status\") OVER (ORDER BY \"enum_window_orders\".\"id\" ASC) " +
                "AS \"previous_status\", LAG(\"enum_window_orders\".\"status\", 1, ?) OVER (ORDER BY " +
                "\"enum_window_orders\".\"id\" ASC) AS \"previous_with_default\" FROM \"enum_window_orders\"",
            rendered.sql,
        )
        assertEquals(10, rendered.parameters.single().value)
        assertEquals(
            WindowStatus.Shipped,
            sqlRow(listOf(previousAlias), listOf<Any?>(20))[previousAlias],
        )
    }

    @Test
    fun supportsNavigationOverGroupedAggregateResults() {
        val total = sum(WindowOrders.amount).alias("total")
        val previousTotal = lag(sum(WindowOrders.amount)).over {
            orderBy(sum(WindowOrders.amount).desc())
        }.alias("previous_total")

        assertEquals(
            "SELECT \"window_orders\".\"organization_id\", SUM(\"window_orders\".\"amount\") AS \"total\", " +
                "LAG(SUM(\"window_orders\".\"amount\")) OVER (ORDER BY SUM(\"window_orders\".\"amount\") DESC) " +
                "AS \"previous_total\" FROM \"window_orders\" GROUP BY \"window_orders\".\"organization_id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(WindowOrders.organizationId, total, previousTotal)
                    .from(WindowOrders)
                    .groupBy(WindowOrders.organizationId),
            ).sql,
        )
    }

    @Test
    fun supportsDerivedTablesCtesAndGroupedWindowOrder() {
        val rowNumber = rowNumber().over {
            partitionBy(WindowOrders.organizationId)
            orderBy(WindowOrders.createdAt.desc())
        }.alias("rn")
        val ranked = select(WindowOrders.id, rowNumber).from(WindowOrders).asTable("ranked")
        val rankedId = ranked[WindowOrders.id]
        val rankedRowNumber = ranked[rowNumber]
        val total = sum(WindowOrders.amount).alias("total")
        val groupedRank = rank().over { orderBy(sum(WindowOrders.amount).desc()) }.alias("group_rank")

        assertEquals(
            "SELECT \"ranked\".\"id\" FROM (SELECT \"window_orders\".\"id\", ROW_NUMBER() OVER " +
                "(PARTITION BY \"window_orders\".\"organization_id\" ORDER BY \"window_orders\".\"created_at\" DESC) " +
                "AS \"rn\" FROM \"window_orders\") AS \"ranked\" WHERE \"ranked\".\"rn\" > ?",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(rankedId).from(ranked).where(rankedRowNumber gt 1L),
            ).sql,
        )
        assertEquals(
            "SELECT \"window_orders\".\"organization_id\", SUM(\"window_orders\".\"amount\") AS \"total\", " +
                "RANK() OVER (ORDER BY SUM(\"window_orders\".\"amount\") DESC) AS \"group_rank\" " +
                "FROM \"window_orders\" GROUP BY \"window_orders\".\"organization_id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(WindowOrders.organizationId, total, groupedRank)
                    .from(WindowOrders)
                    .groupBy(WindowOrders.organizationId),
            ).sql,
        )

        val cte = select(WindowOrders.id, rowNumber).from(WindowOrders).asCte("ranked")
        val cteId = cte[WindowOrders.id]
        val cteRowNumber = cte[rowNumber]
        assertEquals(
            "WITH \"ranked\" AS (SELECT \"window_orders\".\"id\", ROW_NUMBER() OVER " +
                "(PARTITION BY \"window_orders\".\"organization_id\" ORDER BY \"window_orders\".\"created_at\" DESC) " +
                "AS \"rn\" FROM \"window_orders\") SELECT \"ranked\".\"id\" FROM \"ranked\" " +
                "WHERE \"ranked\".\"rn\" > ?",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                with(cte) { select(cteId).from(cte).where(cteRowNumber gt 1L) },
            ).sql,
        )
    }

    @Test
    fun derivesAndFiltersNavigationWindowResults() {
        val previousAmount = lag(WindowOrders.amount)
            .over {
                partitionBy(WindowOrders.organizationId)
                orderBy(WindowOrders.createdAt.asc())
            }.alias("previous_amount")
        val history = select(WindowOrders.id, previousAmount).from(WindowOrders).asTable("history")
        val historyId = history[WindowOrders.id]
        val historyPreviousAmount = history[previousAmount]

        assertEquals(
            "SELECT \"history\".\"id\" FROM (SELECT \"window_orders\".\"id\", " +
                "LAG(\"window_orders\".\"amount\") OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
                "ORDER BY \"window_orders\".\"created_at\" ASC) AS \"previous_amount\" FROM \"window_orders\") " +
                "AS \"history\" WHERE \"history\".\"previous_amount\" IS NOT NULL",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(historyId).from(history).where(historyPreviousAmount.isNotNull()),
            ).sql,
        )

        val historyCte = select(WindowOrders.id, previousAmount).from(WindowOrders).asCte("history")
        val cteId = historyCte[WindowOrders.id]
        val ctePreviousAmount = historyCte[previousAmount]
        assertEquals(
            "WITH \"history\" AS (SELECT \"window_orders\".\"id\", " +
                "LAG(\"window_orders\".\"amount\") OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
                "ORDER BY \"window_orders\".\"created_at\" ASC) AS \"previous_amount\" FROM \"window_orders\") " +
                "SELECT \"history\".\"id\" FROM \"history\" WHERE \"history\".\"previous_amount\" IS NOT NULL",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                with(historyCte) { select(cteId).from(historyCte).where(ctePreviousAmount.isNotNull()) },
            ).sql,
        )
    }

    @Test
    fun rejectsInvalidRangeFrames() {
        assertFailsWith<IllegalArgumentException> { count().over { range(unboundedFollowing) } }
        assertFailsWith<IllegalArgumentException> {
            count().over {
                orderBy(WindowOrders.id.asc())
                range(between(preceding(1), currentRow))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            count().over {
                orderBy(WindowOrders.id.asc())
                range(between(unboundedPreceding, following(1)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            count().over { range(between(unboundedPreceding, currentRow)) }
        }
        assertFailsWith<IllegalArgumentException> {
            count().over {
                orderBy(WindowOrders.id.asc())
                rows(unboundedPreceding)
                range(unboundedPreceding)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            denseRank().over {
                orderBy(WindowOrders.score.desc())
                range(between(unboundedPreceding, currentRow))
            }
        }
    }

    @Test
    fun rejectsInvalidFramesAndInvalidWindowContexts() {
        assertFailsWith<IllegalArgumentException> { preceding(0) }
        assertFailsWith<IllegalArgumentException> { following(-1) }
        assertFailsWith<IllegalArgumentException> { between(unboundedFollowing, currentRow) }
        assertFailsWith<IllegalArgumentException> { between(currentRow, unboundedPreceding) }
        assertFailsWith<IllegalArgumentException> { count().over { rows(unboundedFollowing) } }
        assertFailsWith<IllegalArgumentException> { rowNumber().over { } }
        assertFailsWith<IllegalArgumentException> {
            rank().over {
                orderBy(WindowOrders.score.desc())
                rows(between(unboundedPreceding, currentRow))
            }
        }

        val rowNumber = rowNumber().over { orderBy(WindowOrders.id.asc()) }
        val base = select(WindowOrders.id).from(WindowOrders)
        assertFailsWith<IllegalArgumentException> { base.where(rowNumber gt 1L) }
        assertFailsWith<IllegalArgumentException> { base.having(rowNumber gt 1L) }
        assertFailsWith<IllegalArgumentException> { base.groupBy(rowNumber) }
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(rowNumber()).from(WindowOrders))
        }
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(
                    rowNumber().over {
                        partitionBy(UnrelatedWindowOrders.id)
                        orderBy(WindowOrders.id.asc())
                    },
                ).from(WindowOrders),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("window_check") {
                val id = long("id")

                init {
                    checkConstraint {
                        rowNumber().over { orderBy(id.asc()) } gt 1L
                    }
                }
            }
        }
    }

    @Test
    fun rejectsInvalidNewWindowFeatureShapesAndNamedWindowScopes() {
        assertFailsWith<IllegalArgumentException> { ntile(0) }
        assertFailsWith<IllegalArgumentException> { ntile(-1) }
        assertFailsWith<IllegalArgumentException> { nthValue(WindowOrders.amount, 0) }
        assertFailsWith<IllegalArgumentException> { nthValue(WindowOrders.amount, -1) }
        assertFailsWith<IllegalArgumentException> { ntile(4).over { } }
        assertFailsWith<IllegalArgumentException> { nthValue(WindowOrders.amount, 1).over { } }
        assertFailsWith<IllegalArgumentException> {
            sum(WindowOrders.amount).over { groups(between(unboundedPreceding, currentRow)) }
        }
        assertFailsWith<IllegalArgumentException> {
            sum(WindowOrders.amount).over { excludeCurrentRow() }
        }
        assertFailsWith<IllegalArgumentException> {
            sum(WindowOrders.amount).over {
                rows(unboundedPreceding)
                excludeCurrentRow()
                excludeTies()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ntile(4).over {
                orderBy(WindowOrders.id.asc())
                rows(unboundedPreceding)
            }
        }

        val framed = window("framed") {
            orderBy(WindowOrders.id.asc())
            rows(unboundedPreceding)
        }
        assertFailsWith<IllegalArgumentException> { rank().over(framed) }

        val named = window("named") { orderBy(WindowOrders.id.asc()) }
        val namedRank = rank().over(named)
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(namedRank).from(WindowOrders))
        }
        assertFailsWith<IllegalArgumentException> {
            select(namedRank).from(WindowOrders).window(named, named)
        }
        assertFailsWith<IllegalArgumentException> {
            val unrelated = window("unrelated") { orderBy(UnrelatedWindowOrders.id.asc()) }
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(sum(WindowOrders.amount).over(unrelated)).from(WindowOrders).window(unrelated),
            )
        }
    }

    @Test
    fun rejectsInvalidNavigationWindowContexts() {
        assertFailsWith<IllegalArgumentException> { lag(WindowOrders.amount, offset = 0) }
        assertFailsWith<IllegalArgumentException> { lead(WindowOrders.amount, offset = -1) }
        assertFailsWith<IllegalArgumentException> { lag(WindowOrders.amount).over { } }
        assertFailsWith<IllegalArgumentException> {
            lead(WindowOrders.amount).over {
                orderBy(WindowOrders.id.asc())
                rows(between(unboundedPreceding, currentRow))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            lag(WindowOrders.amount).over {
                orderBy(WindowOrders.id.asc())
                range(between(unboundedPreceding, currentRow))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("window_navigation_check") {
                val id = int("id")

                init {
                    checkConstraint {
                        lag(id).over { orderBy(id.asc()) } gt 1
                    }
                }
            }
        }
    }
}

private fun assertRangeSql(rendered: String) {
    listOf(
        "COUNT(*) OVER (ORDER BY \"window_orders\".\"created_at\" ASC RANGE UNBOUNDED PRECEDING)",
        "SUM(\"window_orders\".\"amount\") OVER (PARTITION BY \"window_orders\".\"organization_id\" " +
            "ORDER BY \"window_orders\".\"created_at\" ASC RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
        "AVG(\"window_orders\".\"amount\") OVER (ORDER BY \"window_orders\".\"created_at\" ASC RANGE CURRENT ROW)",
        "MIN(\"window_orders\".\"amount\") OVER (ORDER BY \"window_orders\".\"created_at\" ASC " +
            "RANGE BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING)",
        "MAX(\"window_orders\".\"amount\") OVER (ORDER BY \"window_orders\".\"created_at\" ASC " +
            "RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
        "FIRST_VALUE(\"window_orders\".\"amount\") OVER (ORDER BY \"window_orders\".\"created_at\" ASC " +
            "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
        "LAST_VALUE(\"window_orders\".\"amount\") OVER (ORDER BY \"window_orders\".\"created_at\" ASC " +
            "RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
    ).forEach { expected -> assertContains(rendered, expected) }
}

private object WindowOrders : Table("window_orders") {
    val id = long("id")
    val organizationId = int("organization_id")
    val status = string("status")
    val amount = int("amount")
    val score = int("score")
    val createdAt = long("created_at")
}

private object UnrelatedWindowOrders : Table("unrelated_window_orders") {
    val id = long("id")
}

private data class WindowAccountId(val value: String)

private object MappedWindowOrders : Table("mapped_window_orders") {
    val id = int("id")
    val accountId = string("account_id").transform(
        fromStorage = ::WindowAccountId,
        toStorage = WindowAccountId::value,
    )
}

private enum class WindowStatus {
    Pending,
    Shipped,
}

private object EnumWindowOrders : Table("enum_window_orders") {
    val id = int("id")
    val status = int("status").asEnum(WindowStatus.entries) {
        WindowStatus.Pending mapsTo 10
        WindowStatus.Shipped mapsTo 20
    }
}
