package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.HypotheticalSetAggregate
import dev.quwac.kagiroi.sql.HypotheticalSetAggregateKind
import dev.quwac.kagiroi.sql.PercentileContAggregate
import dev.quwac.kagiroi.sql.PercentileDiscAggregate
import dev.quwac.kagiroi.sql.SingleValueHypotheticalSetAggregate
import dev.quwac.kagiroi.sql.hypotheticalDistributionResult
import dev.quwac.kagiroi.sql.hypotheticalRankResult
import dev.quwac.kagiroi.sql.hypotheticalSetAggregate
import dev.quwac.kagiroi.sql.percentileContAggregate
import dev.quwac.kagiroi.sql.percentileDiscAggregate

/** Oracle native `PERCENTILE_CONT(fraction) WITHIN GROUP (ORDER BY value)` aggregate. */
fun percentileCont(percentile: Double): PercentileContAggregate = percentileContAggregate(percentile)

/** Oracle native `PERCENTILE_DISC(fraction) WITHIN GROUP (ORDER BY value)` aggregate. */
fun percentileDisc(percentile: Double): PercentileDiscAggregate = percentileDiscAggregate(percentile)

/** Oracle native hypothetical `RANK(value) WITHIN GROUP (ORDER BY value)` aggregate. */
fun <T : Any> hypotheticalRank(value: T): SingleValueHypotheticalSetAggregate<T, Long> =
    hypotheticalSetAggregate(HypotheticalSetAggregateKind.Rank, value, hypotheticalRankResult())

/** Oracle native multi-argument hypothetical `RANK(...) WITHIN GROUP (...)` aggregate. */
fun hypotheticalRank(
    firstArgument: SqlExpression<*>,
    vararg additionalArguments: SqlExpression<*>,
): HypotheticalSetAggregate<Long> = hypotheticalSetAggregate(
    HypotheticalSetAggregateKind.Rank,
    firstArgument,
    *additionalArguments,
    result = hypotheticalRankResult(),
)

/** Oracle native hypothetical `DENSE_RANK(value) WITHIN GROUP (ORDER BY value)` aggregate. */
fun <T : Any> hypotheticalDenseRank(value: T): SingleValueHypotheticalSetAggregate<T, Long> =
    hypotheticalSetAggregate(HypotheticalSetAggregateKind.DenseRank, value, hypotheticalRankResult())

/** Oracle native multi-argument hypothetical `DENSE_RANK(...) WITHIN GROUP (...)` aggregate. */
fun hypotheticalDenseRank(
    firstArgument: SqlExpression<*>,
    vararg additionalArguments: SqlExpression<*>,
): HypotheticalSetAggregate<Long> = hypotheticalSetAggregate(
    HypotheticalSetAggregateKind.DenseRank,
    firstArgument,
    *additionalArguments,
    result = hypotheticalRankResult(),
)

/** Oracle native hypothetical `PERCENT_RANK(value) WITHIN GROUP (ORDER BY value)` aggregate. */
fun <T : Any> hypotheticalPercentRank(value: T): SingleValueHypotheticalSetAggregate<T, Double> =
    hypotheticalSetAggregate(HypotheticalSetAggregateKind.PercentRank, value, hypotheticalDistributionResult())

/** Oracle native multi-argument hypothetical `PERCENT_RANK(...) WITHIN GROUP (...)` aggregate. */
fun hypotheticalPercentRank(
    firstArgument: SqlExpression<*>,
    vararg additionalArguments: SqlExpression<*>,
): HypotheticalSetAggregate<Double> = hypotheticalSetAggregate(
    HypotheticalSetAggregateKind.PercentRank,
    firstArgument,
    *additionalArguments,
    result = hypotheticalDistributionResult(),
)

/** Oracle native hypothetical `CUME_DIST(value) WITHIN GROUP (ORDER BY value)` aggregate. */
fun <T : Any> hypotheticalCumeDist(value: T): SingleValueHypotheticalSetAggregate<T, Double> =
    hypotheticalSetAggregate(HypotheticalSetAggregateKind.CumeDist, value, hypotheticalDistributionResult())

/** Oracle native multi-argument hypothetical `CUME_DIST(...) WITHIN GROUP (...)` aggregate. */
fun hypotheticalCumeDist(
    firstArgument: SqlExpression<*>,
    vararg additionalArguments: SqlExpression<*>,
): HypotheticalSetAggregate<Double> = hypotheticalSetAggregate(
    HypotheticalSetAggregateKind.CumeDist,
    firstArgument,
    *additionalArguments,
    result = hypotheticalDistributionResult(),
)
