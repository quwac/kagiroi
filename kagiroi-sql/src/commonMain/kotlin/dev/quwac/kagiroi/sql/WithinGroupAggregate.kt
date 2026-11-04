package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.AggregateSqlExpression
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.optional

/** Native aggregate features whose grammar contains a typed `WITHIN GROUP (ORDER BY ...)` clause.
 *
 * @property unsupportedCapability identifier used when the dialect does not support this aggregate form.
 */
enum class SqlWithinGroupCapability(val unsupportedCapability: String) {
    /** Continuous percentile ordered-set aggregate. */
    PercentileCont("ordered-set-percentile-cont"),

    /** Discrete percentile ordered-set aggregate. */
    PercentileDisc("ordered-set-percentile-disc"),

    /** Mode ordered-set aggregate. */
    Mode("ordered-set-mode"),

    /** Hypothetical rank aggregate. */
    HypotheticalRank("hypothetical-set-rank"),

    /** Hypothetical dense-rank aggregate. */
    HypotheticalDenseRank("hypothetical-set-dense-rank"),

    /** Hypothetical percent-rank aggregate. */
    HypotheticalPercentRank("hypothetical-set-percent-rank"),

    /** Hypothetical cumulative-distribution aggregate. */
    HypotheticalCumeDist("hypothetical-set-cume-dist"),
}

/** One ordered-set aggregate semantic, deliberately distinct from ordinary aggregates and windows.
 *
 * @property sqlName SQL function name.
 * @property capability capability that the dialect must support to render this aggregate.
 */
enum class OrderedSetAggregateKind(override val sqlName: String, override val capability: SqlWithinGroupCapability) :
    WithinGroupAggregateKind {
    /** Continuous percentile ordered-set aggregate. */
    PercentileCont("PERCENTILE_CONT", SqlWithinGroupCapability.PercentileCont),

    /** Discrete percentile ordered-set aggregate. */
    PercentileDisc("PERCENTILE_DISC", SqlWithinGroupCapability.PercentileDisc),

    /** Mode ordered-set aggregate. */
    Mode("MODE", SqlWithinGroupCapability.Mode),
}

/** One hypothetical-set aggregate semantic, distinct from similarly named window functions.
 *
 * @property sqlName SQL function name.
 * @property capability capability that the dialect must support to render this aggregate.
 */
enum class HypotheticalSetAggregateKind(
    override val sqlName: String,
    override val capability: SqlWithinGroupCapability,
) : WithinGroupAggregateKind {
    /** Hypothetical rank aggregate. */
    Rank("RANK", SqlWithinGroupCapability.HypotheticalRank),

    /** Hypothetical dense-rank aggregate. */
    DenseRank("DENSE_RANK", SqlWithinGroupCapability.HypotheticalDenseRank),

    /** Hypothetical percent-rank aggregate. */
    PercentRank("PERCENT_RANK", SqlWithinGroupCapability.HypotheticalPercentRank),

    /** Hypothetical cumulative-distribution aggregate. */
    CumeDist("CUME_DIST", SqlWithinGroupCapability.HypotheticalCumeDist),
}

/** A function that can be completed only by the aggregate-local [WithinGroupSpecification]. */
sealed interface WithinGroupAggregateKind {
    /** SQL function name rendered for this aggregate. */
    val sqlName: String

    /** Dialect capability required to render this aggregate. */
    val capability: SqlWithinGroupCapability
}

/**
 * The ordering input of an ordered-set or hypothetical-set aggregate.
 *
 * This is intentionally not [WindowSpecification]: its order expressions are aggregate inputs,
 * not the current-row ordering of an `OVER` clause.
 *
 * @property orderBy non-empty ordered inputs of the aggregate.
 */
data class WithinGroupSpecification(val orderBy: NonEmptyList<Order<*>>) {
    internal constructor(first: Order<*>, additional: Array<out Order<*>>) :
        this(nonEmptyListOf(first, *additional))
}

/**
 * A completed native `... WITHIN GROUP (ORDER BY ...)` aggregate expression.
 *
 * It is not [dev.quwac.kagiroi.schema.WindowCapableExpression], so ordered-set and
 * hypothetical-set aggregates cannot accidentally acquire `OVER (...)` through the ordinary
 * aggregate API.
 *
 * @property kind ordered-set or hypothetical-set aggregate kind.
 * @property directArguments values passed directly to the aggregate before WITHIN GROUP ordering.
 * @property withinGroup aggregate-local ordering specification.
 */
class WithinGroupAggregateExpression<T> internal constructor(
    val kind: WithinGroupAggregateKind,
    val directArguments: List<SqlExpression<*>>,
    val withinGroup: WithinGroupSpecification,
    private val result: SqlExpression<T>,
) : SqlExpression<T>,
    AggregateSqlExpression<T> {
    override val referencedColumns =
        directArguments.flatMap(SqlExpression<*>::referencedColumns) +
            withinGroup.orderBy.flatMap { it.expression.referencedColumns }
    override val type get() = result.type
    override val storageType get() = result.storageType
    override val isNullable get() = result.isNullable

    override fun toStorageValue(value: T): Any? = result.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = result.fromStorageValue(value)
}

/** A pending `PERCENTILE_CONT` whose one aggregate sort input must be supplied explicitly.
 *
 * @property percentile percentile value in the inclusive range from zero to one.
 */
class PercentileContAggregate(val percentile: Double) {
    init {
        require(percentile in 0.0..1.0) { "PERCENTILE_CONT percentile must be between 0.0 and 1.0" }
    }

    /**
     * Completes the aggregate with its only numeric sort input.
     *
     * The portable logical result is nullable `Double`: the exposed PostgreSQL and Oracle forms
     * use a floating result for numeric inputs, and an empty aggregate input can produce SQL NULL. Dialect
     * modules expose this builder only where their aggregate grammar actually matches this form.
     */
    fun <T : Number?> withinGroup(order: Order<T>): WithinGroupAggregateExpression<Double?> =
        WithinGroupAggregateExpression(
            OrderedSetAggregateKind.PercentileCont,
            listOf(literal(percentile)),
            WithinGroupSpecification(order, emptyArray()),
            literal(0.0).optional,
        )
}

/** A pending `PERCENTILE_DISC` whose one aggregate sort input determines the result mapping.
 *
 * @property percentile percentile value in the inclusive range from zero to one.
 */
class PercentileDiscAggregate(val percentile: Double) {
    init {
        require(percentile in 0.0..1.0) { "PERCENTILE_DISC percentile must be between 0.0 and 1.0" }
    }

    /** Completes the aggregate; the selected discrete input value keeps its Kotlin mapping. */
    fun <T> withinGroup(order: Order<T>): WithinGroupAggregateExpression<T?> = WithinGroupAggregateExpression(
        OrderedSetAggregateKind.PercentileDisc,
        listOf(literal(percentile)),
        WithinGroupSpecification(order, emptyArray()),
        order.expression.optional,
    )
}

/** A pending `MODE()` ordered-set aggregate whose only sort input determines the result mapping. */
class ModeOrderedSetAggregate {
    /** Completes the aggregate; empty input and an all-NULL input can produce SQL NULL. */
    fun <T> withinGroup(order: Order<T>): WithinGroupAggregateExpression<T?> = WithinGroupAggregateExpression(
        OrderedSetAggregateKind.Mode,
        emptyList(),
        WithinGroupSpecification(order, emptyArray()),
        order.expression.optional,
    )
}

/** A pending hypothetical-set aggregate with expression direct arguments. */
class HypotheticalSetAggregate<T> internal constructor(
    private val kind: HypotheticalSetAggregateKind,
    private val directArguments: List<SqlExpression<*>>,
    private val result: SqlExpression<T>,
) {
    init {
        require(directArguments.isNotEmpty()) { "Hypothetical-set aggregates require at least one direct argument" }
    }

    /**
     * Completes the native aggregate after verifying every direct argument corresponds positionally
     * to exactly one same-mapped `WITHIN GROUP` sort expression. No coercion is inserted.
     */
    fun withinGroup(firstOrder: Order<*>, vararg additionalOrders: Order<*>): WithinGroupAggregateExpression<T> {
        val specification = WithinGroupSpecification(firstOrder, additionalOrders)
        require(directArguments.size == specification.orderBy.size) {
            "Hypothetical-set direct argument count must match WITHIN GROUP ORDER BY expression count"
        }
        directArguments.zip(specification.orderBy).forEachIndexed { index, (argument, order) ->
            require(argument.type === order.expression.type && argument.storageType === order.expression.storageType) {
                "Hypothetical-set direct argument ${index + 1} must use the same logical and storage mapping " +
                    "as WITHIN GROUP ORDER BY expression ${index + 1}"
            }
        }
        return WithinGroupAggregateExpression(kind, directArguments, specification, result)
    }
}

/** A pending single-value hypothetical-set aggregate whose typed value is mapped by its order input. */
class SingleValueHypotheticalSetAggregate<T : Any, R> internal constructor(
    private val kind: HypotheticalSetAggregateKind,
    private val value: T,
    private val result: SqlExpression<R>,
) {
    /** Completes a one-column hypothetical aggregate without any implicit value conversion. */
    fun withinGroup(order: Order<out T?>): WithinGroupAggregateExpression<R> {
        @Suppress("UNCHECKED_CAST")
        val orderExpression = order.expression as SqlExpression<T?>
        return HypotheticalSetAggregate(
            kind,
            listOf(literal(value, orderExpression)),
            result,
        ).withinGroup(order)
    }
}

/** Constructs the common semantic builder used by dialect-owned `PERCENTILE_CONT` APIs. */
fun percentileContAggregate(percentile: Double): PercentileContAggregate = PercentileContAggregate(percentile)

/** Constructs the common semantic builder used by dialect-owned `PERCENTILE_DISC` APIs. */
fun percentileDiscAggregate(percentile: Double): PercentileDiscAggregate = PercentileDiscAggregate(percentile)

/** Constructs the common semantic builder used by dialect-owned `MODE` APIs. */
fun modeOrderedSetAggregate(): ModeOrderedSetAggregate = ModeOrderedSetAggregate()

/** Constructs one typed hypothetical-set aggregate from direct expressions. */
fun <T> hypotheticalSetAggregate(
    kind: HypotheticalSetAggregateKind,
    firstArgument: SqlExpression<*>,
    vararg additionalArguments: SqlExpression<*>,
    result: SqlExpression<T>,
): HypotheticalSetAggregate<T> = HypotheticalSetAggregate(
    kind,
    listOf(firstArgument) + additionalArguments,
    result,
)

/** Constructs one typed one-column hypothetical-set aggregate from a Kotlin value. */
fun <T : Any, R> hypotheticalSetAggregate(
    kind: HypotheticalSetAggregateKind,
    value: T,
    result: SqlExpression<R>,
): SingleValueHypotheticalSetAggregate<T, R> = SingleValueHypotheticalSetAggregate(kind, value, result)

/** Typed result template for native hypothetical `RANK` and `DENSE_RANK`. */
fun hypotheticalRankResult(): SqlExpression<Long> = literal(0L)

/** Typed result template for native hypothetical `PERCENT_RANK` and `CUME_DIST`. */
fun hypotheticalDistributionResult(): SqlExpression<Double> = literal(0.0)
