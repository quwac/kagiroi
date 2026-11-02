package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.avg
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.denseRank
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.lag
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.lead
import dev.quwac.kagiroi.schema.max
import dev.quwac.kagiroi.schema.min
import dev.quwac.kagiroi.schema.nthValue
import dev.quwac.kagiroi.schema.ntile
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.schema.rank
import dev.quwac.kagiroi.schema.rowNumber
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.between
import dev.quwac.kagiroi.sql.currentRow
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.preceding
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.unboundedPreceding
import dev.quwac.kagiroi.sql.unboundedFollowing
import dev.quwac.kagiroi.sql.window
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with

private object WindowOrders : Table("window_orders") {
    val id = long("id")
    val organizationId = int("organization_id")
    val status = string("status")
    val amount = int("amount")
    val score = int("score")
    val createdAt = long("created_at")
    val nullableAmount = int("nullable_amount").nullable()
}

fun validWindowExpressions(): String {
    val rowNumber: SqlExpression<Long> = rowNumber().over {
        partitionBy(WindowOrders.organizationId, WindowOrders.status)
        orderBy(WindowOrders.createdAt.desc(), WindowOrders.id.asc())
    }
    val rowNumberAlias = rowNumber.alias("row_number")
    val rank: SqlExpression<Long> = rank().over { orderBy(WindowOrders.score.desc()) }
    val denseRank: SqlExpression<Long> = denseRank().over { orderBy(WindowOrders.score.desc()) }
    val percentRank: SqlExpression<Double> = percentRank().over { orderBy(WindowOrders.score.desc()) }
    val cumeDist: SqlExpression<Double> = cumeDist().over { orderBy(WindowOrders.score.desc()) }
    val percentRankAlias = percentRank.alias("percent_rank")
    val runningTotal: SqlExpression<Long?> = sum(WindowOrders.amount).over {
        partitionBy(WindowOrders.organizationId)
        orderBy(WindowOrders.createdAt.asc())
        rows(between(unboundedPreceding, currentRow))
    }
    val movingAverage: SqlExpression<Double?> = avg(WindowOrders.amount).over {
        orderBy(WindowOrders.createdAt.asc())
        rows(between(preceding(2), currentRow))
    }
    val peerRunningTotal: SqlExpression<Long?> = sum(WindowOrders.amount).over {
        partitionBy(WindowOrders.organizationId)
        orderBy(WindowOrders.createdAt.asc())
        range(between(unboundedPreceding, currentRow))
    }
    val peerRunningTotalAlias = peerRunningTotal.alias("peer_running_total")
    val partitionCount: SqlExpression<Long> = count().over()
    val partitionMinimum: SqlExpression<Int?> = min(WindowOrders.amount).over()
    val partitionMaximum: SqlExpression<Int?> = max(WindowOrders.amount).over()
    val previousAmount: SqlExpression<Int?> = lag(WindowOrders.amount).over {
        partitionBy(WindowOrders.organizationId)
        orderBy(WindowOrders.createdAt.asc())
    }
    val nextAmount: SqlExpression<Int?> = lead(WindowOrders.amount, offset = 2).over {
        orderBy(WindowOrders.createdAt.asc())
    }
    val previousWithDefault: SqlExpression<Int?> = lag(WindowOrders.amount, default = 0).over {
        orderBy(WindowOrders.createdAt.asc())
    }
    val firstAmount: SqlExpression<Int> = firstValue(WindowOrders.amount).over {
        orderBy(WindowOrders.createdAt.asc())
    }
    val nullableFirstAmount: SqlExpression<Int?> = firstValue(WindowOrders.nullableAmount).over {
        orderBy(WindowOrders.createdAt.asc())
    }
    val lastAmount: SqlExpression<Int> = lastValue(WindowOrders.amount).over {
        orderBy(WindowOrders.createdAt.asc())
        range(between(unboundedPreceding, unboundedFollowing))
    }
    val rankingWindow = window("ranking_window") {
        partitionBy(WindowOrders.organizationId)
        orderBy(WindowOrders.score.desc())
    }
    val quartile: SqlExpression<Long> = ntile(4).over(rankingWindow)
    val peerWindow = window("peer_window") {
        partitionBy(WindowOrders.organizationId)
        orderBy(WindowOrders.amount.asc())
        groups(between(preceding(1), currentRow))
        excludeTies()
    }
    val thirdAmount: SqlExpression<Int?> = nthValue(WindowOrders.amount, 3).over(peerWindow)
    val namedWindowQuery = select(quartile, thirdAmount)
        .from(WindowOrders)
        .window(rankingWindow, peerWindow)
    val ranked = select(WindowOrders.id, rowNumberAlias, percentRankAlias, peerRunningTotalAlias)
        .from(WindowOrders)
        .asTable("ranked")
    val rankedId = ranked[WindowOrders.id]
    val rankedRowNumber = ranked[rowNumberAlias]
    val rankedPercentRank = ranked[percentRankAlias]
    val rankedPeerRunningTotal = ranked[peerRunningTotalAlias]
    val filtered = select(rankedId, rankedPeerRunningTotal).from(ranked).where(rankedRowNumber gt 1L)
    val cte = select(WindowOrders.id, rowNumberAlias, percentRankAlias, peerRunningTotalAlias)
        .from(WindowOrders)
        .asCte("ranked_cte")
    val cteId = cte[WindowOrders.id]
    val cteRowNumber = cte[rowNumberAlias]
    val ctePeerRunningTotal = cte[peerRunningTotalAlias]
    val cteFiltered = with(cte) { select(cteId, ctePeerRunningTotal).from(cte).where(cteRowNumber gt 1L) }
    val typedRow: (SqlRow) -> Pair<Long, Double> = { row -> row[rowNumberAlias] to row[percentRankAlias] }
    return "$rank$denseRank$percentRank$cumeDist$runningTotal$movingAverage$peerRunningTotal$partitionCount$partitionMinimum$partitionMaximum" +
        "$previousAmount$nextAmount$previousWithDefault$firstAmount$nullableFirstAmount$lastAmount" +
        "$quartile$thirdAmount$rankedPercentRank$namedWindowQuery$filtered$cteFiltered$typedRow"
}
