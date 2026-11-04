package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.WindowCapableExpression
import dev.quwac.kagiroi.schema.WindowedSqlExpression

/**
 * A complete typed window specification shared by inline `OVER (...)` and query-scoped named
 * `WINDOW` definitions. Frame exclusions belong to [WindowFrame], because they change frame
 * membership rather than filtering the query result.
 *
 * @property partitionBy expressions that divide rows into independent partitions.
 * @property orderBy ordering expressions applied within each partition.
 * @property frame optional window frame.
 */
data class WindowSpecification(
    val partitionBy: List<SqlExpression<*>>,
    val orderBy: List<Order<*>>,
    val frame: WindowFrame?,
)

/** The portable window-frame type. `ROWS` counts rows, `RANGE` ordered values, and `GROUPS` peers.
 *
 * @property sql SQL frame-unit keyword.
 */
enum class WindowFrameUnit(val sql: String) {
    /** Counts physical rows in the window ordering. */
    Rows("ROWS"),

    /** Measures the frame over the ordered values. */
    Range("RANGE"),

    /** Counts peer groups with equal ordering values. */
    Groups("GROUPS"),
}

/** One validated window frame, including its optional native exclusion semantics.
 *
 * @property unit ROWS, RANGE, or GROUPS frame semantics.
 * @property extent one boundary or an explicit start/end pair.
 * @property exclusion optional native frame exclusion.
 */
data class WindowFrame(
    val unit: WindowFrameUnit,
    val extent: WindowFrameExtent,
    val exclusion: WindowFrameExclusion? = null,
)

/** Native SQL frame exclusions; no peer membership is calculated by Kagiroi.
 *
 * @property sql SQL exclusion clause.
 */
enum class WindowFrameExclusion(val sql: String) {
    /** Excludes the current row from the frame. */
    CurrentRow("EXCLUDE CURRENT ROW"),

    /** Excludes the current row's peer group. */
    Group("EXCLUDE GROUP"),

    /** Excludes peers except for the current row. */
    Ties("EXCLUDE TIES"),

    /** Retains all rows in the frame. */
    NoOthers("EXCLUDE NO OTHERS"),
}

/** A frame can have one start boundary or explicit start and end boundaries. */
sealed interface WindowFrameExtent

/** A frame extent with one start boundary and an implicit end at CURRENT ROW.
 *
 * @property boundary start boundary.
 */
class WindowFrameStart internal constructor(val boundary: WindowFrameBoundary) : WindowFrameExtent

/** A frame extent with explicit start and end boundaries.
 *
 * @property start first boundary.
 * @property end final boundary.
 */
class WindowFrameBetween internal constructor(val start: WindowFrameBoundary, val end: WindowFrameBoundary) :
    WindowFrameExtent

/** A type-safe frame boundary; offsets are validated integer literals, never SQL fragments. */
sealed interface WindowFrameBoundary {
    /** SQL boundary syntax. */
    val sql: String

    /** Position relative to the current row, used to reject impossible BETWEEN ranges. */
    val position: Int
}

/** Boundary at the first row of the partition. */
data object UnboundedPreceding : WindowFrameBoundary {
    override val sql: String = "UNBOUNDED PRECEDING"
    override val position: Int = Int.MIN_VALUE
}

/** Boundary at the row currently being evaluated. */
data object CurrentRow : WindowFrameBoundary {
    override val sql: String = "CURRENT ROW"
    override val position: Int = 0
}

/** Boundary at the last row of the partition. */
data object UnboundedFollowing : WindowFrameBoundary {
    override val sql: String = "UNBOUNDED FOLLOWING"
    override val position: Int = Int.MAX_VALUE
}

/** Boundary a positive number of rows before the current row.
 *
 * @property offset positive row offset.
 */
class Preceding internal constructor(val offset: Int) : WindowFrameBoundary {
    override val sql: String = "$offset PRECEDING"
    override val position: Int = -offset
}

/** Boundary a positive number of rows after the current row.
 *
 * @property offset positive row offset.
 */
class Following internal constructor(val offset: Int) : WindowFrameBoundary {
    override val sql: String = "$offset FOLLOWING"
    override val position: Int = offset
}

/** The first row in the partition. */
val unboundedPreceding: WindowFrameBoundary = UnboundedPreceding

/** The row currently being evaluated. */
val currentRow: WindowFrameBoundary = CurrentRow

/** The last row in the partition. */
val unboundedFollowing: WindowFrameBoundary = UnboundedFollowing

/** A frame boundary [offset] rows before the current row. */
fun preceding(offset: Int): WindowFrameBoundary {
    require(offset > 0) { "PRECEDING offset must be positive; use currentRow for zero" }
    return Preceding(offset)
}

/** A frame boundary [offset] rows after the current row. */
fun following(offset: Int): WindowFrameBoundary {
    require(offset > 0) { "FOLLOWING offset must be positive; use currentRow for zero" }
    return Following(offset)
}

/** Creates an explicit validated `BETWEEN start AND end` extent. */
fun between(start: WindowFrameBoundary, end: WindowFrameBoundary): WindowFrameExtent {
    require(start !is UnboundedFollowing) { "Window frame cannot start at UNBOUNDED FOLLOWING" }
    require(end !is UnboundedPreceding) { "Window frame cannot end at UNBOUNDED PRECEDING" }
    require(start.position <= end.position) { "Window frame start must not follow its end" }
    return WindowFrameBetween(start, end)
}

/** Builds a reusable complete [WindowSpecification] for inline or named windows. */
@Suppress("TooManyFunctions") // One DSL builder intentionally owns the complete window grammar.
class WindowSpecificationBuilder internal constructor() {
    private val partitions = mutableListOf<SqlExpression<*>>()
    private val orders = mutableListOf<Order<*>>()
    private var selectedFrame: WindowFrame? = null

    /** Splits the result set by one or more typed expressions. */
    fun partitionBy(expression: SqlExpression<*>, vararg additionalExpressions: SqlExpression<*>) {
        require(partitions.isEmpty()) { "PARTITION BY can only be specified once per window" }
        partitions += expression
        partitions += additionalExpressions
    }

    /** Orders rows inside the window. This is distinct from the query's outer `orderBy`. */
    fun orderBy(order: Order<*>, vararg additionalOrders: Order<*>) {
        require(orders.isEmpty()) { "Window ORDER BY can only be specified once per window" }
        orders += order
        orders += additionalOrders
    }

    /** Adds a `ROWS BETWEEN ... AND ...` frame. */
    fun rows(extent: WindowFrameExtent) {
        setFrame(WindowFrame(WindowFrameUnit.Rows, extent))
    }

    /** Adds the SQL short form `ROWS boundary`, whose end is implicitly `CURRENT ROW`. */
    fun rows(boundary: WindowFrameBoundary) {
        require(boundary !is UnboundedFollowing && boundary.position <= CurrentRow.position) {
            "A single ROWS boundary must not follow CURRENT ROW"
        }
        setFrame(WindowFrame(WindowFrameUnit.Rows, WindowFrameStart(boundary)))
    }

    /**
     * Adds a portable `RANGE BETWEEN ... AND ...` frame.
     *
     * Unlike [rows], this is a logical frame over the window `ORDER BY` values. Kagiroi only
     * supports the cross-dialect subset with unbounded/current-row boundaries; value offsets
     * require dialect- and ordered-type-specific semantics and are deliberately not modeled.
     */
    fun range(extent: WindowFrameExtent) {
        validatePortableRangeExtent(extent)
        setFrame(WindowFrame(WindowFrameUnit.Range, extent))
    }

    /** Adds the SQL short form `RANGE boundary`, whose end is implicitly `CURRENT ROW`. */
    fun range(boundary: WindowFrameBoundary) {
        require(boundary is UnboundedPreceding || boundary is CurrentRow) {
            "Portable RANGE supports only UNBOUNDED PRECEDING or CURRENT ROW as a single boundary"
        }
        setFrame(WindowFrame(WindowFrameUnit.Range, WindowFrameStart(boundary)))
    }

    /** Adds a peer-group frame. Unlike `ROWS`, offsets count complete `ORDER BY` peer groups. */
    fun groups(extent: WindowFrameExtent) {
        setFrame(WindowFrame(WindowFrameUnit.Groups, extent))
    }

    /** Adds the SQL short form `GROUPS boundary`, whose end is implicitly `CURRENT ROW`. */
    fun groups(boundary: WindowFrameBoundary) {
        require(boundary !is UnboundedFollowing && boundary.position <= CurrentRow.position) {
            "A single GROUPS boundary must not follow CURRENT ROW"
        }
        setFrame(WindowFrame(WindowFrameUnit.Groups, WindowFrameStart(boundary)))
    }

    /** Adds an explicit native [exclusion] to the already selected explicit frame. */
    fun exclude(exclusion: WindowFrameExclusion) {
        val frame = requireNotNull(selectedFrame) { "EXCLUDE requires an explicit window frame" }
        require(frame.exclusion == null) { "Window frame exclusion can only be specified once per window" }
        selectedFrame = frame.copy(exclusion = exclusion)
    }

    /** Excludes the current row from the selected frame. */
    fun excludeCurrentRow() = exclude(WindowFrameExclusion.CurrentRow)

    /** Excludes the current row's peer group from the selected frame. */
    fun excludeGroup() = exclude(WindowFrameExclusion.Group)

    /** Excludes peer rows other than the current row from the selected frame. */
    fun excludeTies() = exclude(WindowFrameExclusion.Ties)

    /** Keeps all rows in the selected frame. */
    fun excludeNoOthers() = exclude(WindowFrameExclusion.NoOthers)

    private fun setFrame(frame: WindowFrame) {
        require(selectedFrame == null) { "Window frame can only be specified once per window" }
        selectedFrame = frame
    }

    internal fun build(): WindowSpecification {
        require(
            selectedFrame?.unit !in setOf(WindowFrameUnit.Range, WindowFrameUnit.Groups) || orders.isNotEmpty(),
        ) {
            "${selectedFrame?.unit?.sql} frames require a window ORDER BY in the portable API"
        }
        return WindowSpecification(
            partitionBy = partitions.toList(),
            orderBy = orders.toList(),
            frame = selectedFrame,
        )
    }
}

private fun validatePortableRangeExtent(extent: WindowFrameExtent) {
    when (extent) {
        is WindowFrameStart -> require(extent.boundary is UnboundedPreceding || extent.boundary is CurrentRow) {
            "Portable RANGE supports only UNBOUNDED PRECEDING or CURRENT ROW as a single boundary"
        }

        is WindowFrameBetween -> {
            require(extent.start.isPortableRangeBoundary() && extent.end.isPortableRangeBoundary()) {
                "Portable RANGE supports only UNBOUNDED PRECEDING, CURRENT ROW, and UNBOUNDED FOLLOWING; " +
                    "RANGE offsets are not supported"
            }
        }
    }
}

private fun WindowFrameBoundary.isPortableRangeBoundary(): Boolean =
    this is UnboundedPreceding || this is CurrentRow || this is UnboundedFollowing

/** A complete query-scoped named window definition.
 *
 * @property name SQL window name.
 * @property specification window partitioning, ordering, and frame.
 */
class NamedWindow internal constructor(val name: String, val specification: WindowSpecification) {
    init {
        require(name.isNotBlank()) { "Window name must not be blank" }
    }
}

/** Builds a reusable named window definition; register it on each query with `.window(...)`. */
fun window(name: String, specification: WindowSpecificationBuilder.() -> Unit): NamedWindow =
    NamedWindow(name, WindowSpecificationBuilder().apply(specification).build())

/** The typed expressions owned by this specification, in SQL rendering order. */
internal fun WindowSpecification.expressions(): List<SqlExpression<*>> = partitionBy + orderBy.map { it.expression }

/**
 * A completed window expression. It is an ordinary typed projection/order expression, but is not
 * itself window-capable, preventing a second `OVER` clause from being added accidentally.
 */
class WindowExpression<T> internal constructor(
    /** Function completed by this window specification. */
    val function: SqlFunctionExpression<T>,
    /** Partitioning, ordering, and frame applied to the function. */
    val specification: WindowSpecification,
    /** Non-null only when SQL is rendered as `OVER named_window`. */
    val namedWindow: NamedWindow? = null,
) : WindowedSqlExpression<T> {
    override val referencedColumns: List<Column<*>> = function.referencedColumns +
        specification.partitionBy.flatMap(SqlExpression<*>::referencedColumns) +
        specification.orderBy.flatMap { it.expression.referencedColumns }
    override val type get() = function.type
    override val storageType get() = function.storageType
    override val isNullable get() = function.isNullable

    override fun toStorageValue(value: T): Any? = function.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = function.fromStorageValue(value)
}

/** Completes an aggregate or incomplete window function with an inline `OVER (...)` clause. */
@Suppress("UNCHECKED_CAST") // WindowCapableExpression is sealed to SqlFunctionExpression subclasses.
fun <T> WindowCapableExpression<T>.over(
    specification: WindowSpecificationBuilder.() -> Unit = {},
): WindowExpression<T> {
    val function = this as? SqlFunctionExpression<T>
        ?: error("Window-capable expressions must be function expressions")
    val built = WindowSpecificationBuilder().apply(specification).build()
    validateWindowSpecification(function.function, built)
    return WindowExpression(function, built)
}

/** Completes a function by referring to one query-scoped [NamedWindow]. */
@Suppress("UNCHECKED_CAST") // WindowCapableExpression is sealed to SqlFunctionExpression subclasses.
fun <T> WindowCapableExpression<T>.over(namedWindow: NamedWindow): WindowExpression<T> {
    val function = this as? SqlFunctionExpression<T>
        ?: error("Window-capable expressions must be function expressions")
    validateWindowSpecification(function.function, namedWindow.specification)
    return WindowExpression(function, namedWindow.specification, namedWindow)
}

private fun validateWindowSpecification(function: SqlFunction, specification: WindowSpecification) {
    when (function) {
        SqlFunction.RowNumber,
        SqlFunction.Rank,
        SqlFunction.DenseRank,
        SqlFunction.PercentRank,
        SqlFunction.CumeDist,
        SqlFunction.Ntile,
        SqlFunction.Lag,
        SqlFunction.Lead,
        SqlFunction.FirstValue,
        SqlFunction.LastValue,
        SqlFunction.NthValue,
        -> {
            require(specification.orderBy.isNotEmpty()) {
                "${functionName(function)} requires a window ORDER BY across Kagiroi's supported dialects"
            }
        }

        else -> Unit
    }

    when (function) {
        SqlFunction.RowNumber,
        SqlFunction.Rank,
        SqlFunction.DenseRank,
        SqlFunction.PercentRank,
        SqlFunction.CumeDist,
        SqlFunction.Ntile,
        SqlFunction.Lag,
        SqlFunction.Lead,
        -> {
            require(specification.frame == null) { "${functionName(function)} does not accept a window frame" }
        }

        else -> Unit
    }
}

private fun functionName(function: SqlFunction): String = when (function) {
    SqlFunction.RowNumber -> "ROW_NUMBER()"
    SqlFunction.Rank -> "RANK()"
    SqlFunction.DenseRank -> "DENSE_RANK()"
    SqlFunction.PercentRank -> "PERCENT_RANK()"
    SqlFunction.CumeDist -> "CUME_DIST()"
    SqlFunction.Ntile -> "NTILE()"
    SqlFunction.Lag -> "LAG()"
    SqlFunction.Lead -> "LEAD()"
    SqlFunction.FirstValue -> "FIRST_VALUE()"
    SqlFunction.LastValue -> "LAST_VALUE()"
    SqlFunction.NthValue -> "NTH_VALUE()"
    else -> function.toString()
}
