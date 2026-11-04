package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.CaseExpression
import dev.quwac.kagiroi.schema.CastExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ExpressionComparison
import dev.quwac.kagiroi.schema.ExpressionNullComparison
import dev.quwac.kagiroi.schema.ExpressionValueComparison
import dev.quwac.kagiroi.schema.GeneratedColumnBinaryExpression
import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.LikeExpressionComparison
import dev.quwac.kagiroi.schema.LikeValueComparison
import dev.quwac.kagiroi.schema.NullSafeComparison
import dev.quwac.kagiroi.schema.NullSafeValueComparison
import dev.quwac.kagiroi.schema.NullableRelationColumn
import dev.quwac.kagiroi.schema.NullableSqlExpression
import dev.quwac.kagiroi.schema.NumericBinaryExpression
import dev.quwac.kagiroi.schema.NumericBinaryOperator
import dev.quwac.kagiroi.schema.RelationColumn
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlExpressionAlias
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.SqlLiteral
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.TableRelationSource
import dev.quwac.kagiroi.schema.ValueWindowFunctionExpression
import dev.quwac.kagiroi.schema.WindowFunctionExpression
import dev.quwac.kagiroi.schema.WindowNullTreatment

private const val DEFAULT_WINDOW_NAVIGATION_OFFSET = 1

/** The semantic kind of the statement which owns a lexical WITH scope. */
enum class SqlStatementKind {
    /** SELECT statement context. */
    Select,

    /** INSERT statement context. */
    Insert,

    /** UPDATE statement context. */
    Update,

    /** DELETE statement context. */
    Delete,
}

/** The grammar position selected by a dialect for one statement's CTE scope. */
sealed interface SqlWithClausePlacement {
    /** The WITH clause precedes the whole statement. */
    data object BeforeStatement : SqlWithClausePlacement

    /** The WITH clause begins the SELECT source of INSERT ... SELECT. */
    data object BeforeInsertSelect : SqlWithClausePlacement

    /** This statement kind cannot carry a CTE scope in the dialect's supported version. */
    data object Unsupported : SqlWithClausePlacement
}

/** Syntax choices required by the currently supported SQL subset. */
@Suppress("TooManyFunctions") // One dialect contract intentionally owns all portable rendering decisions.
interface SqlDialect {
    /** Quotes one SQL identifier for this dialect. */
    fun quoteIdentifier(identifier: String): String

    /** Returns the parameter marker at the one-based [position]. */
    fun parameterMarker(position: Int): String

    /** Renders one typed arithmetic operator after both numeric operands have been rendered. */
    fun renderNumericBinary(operator: NumericBinaryOperator, left: String, right: String): String =
        "($left ${operator.sql} $right)"

    /** Renders one LIKE operator or rejects a dialect-specific case-insensitive form. */
    fun renderLikeOperator(operator: dev.quwac.kagiroi.schema.LikeOperator): String = when (operator) {
        dev.quwac.kagiroi.schema.LikeOperator.LIKE,
        dev.quwac.kagiroi.schema.LikeOperator.NOT_LIKE,
        -> operator.sql

        dev.quwac.kagiroi.schema.LikeOperator.ILIKE,
        dev.quwac.kagiroi.schema.LikeOperator.NOT_ILIKE,
        -> throw UnsupportedCapability("ilike")
    }

    /** Renders one native null-safe equality comparison without boolean-expression emulation. */
    fun renderNullSafeComparison(distinct: Boolean, left: String, right: String): String =
        throw UnsupportedCapability(if (distinct) "is-distinct-from" else "is-not-distinct-from")

    /** Renders a SELECT duplicate-elimination modifier after its expressions are dialect-checked. */
    fun renderSelectDistinct(distinct: Boolean, distinctOn: List<String>): String {
        if (distinctOn.isNotEmpty()) throw UnsupportedCapability("distinct-on")
        return if (distinct) "DISTINCT " else ""
    }

    /** Renders direct UPDATE ... FROM sources after the SET clause or rejects the feature. */
    fun renderUpdateFrom(sources: List<String>): String {
        if (sources.isNotEmpty()) throw UnsupportedCapability("update-from")
        return ""
    }

    /** Renders a result ordering direction and optional native SQL NULL placement. */
    fun renderOrder(direction: SortDirection, nullOrdering: NullOrdering?): String {
        if (nullOrdering != null) throw UnsupportedCapability("null-ordering")
        return direction.sql
    }

    /**
     * Reports whether this dialect's supported server baseline natively implements [operation].
     *
     * The ALL variants are opt-in so a dialect that supports distinct INTERSECT or EXCEPT does
     * not accidentally claim the duplicate-preserving forms. Unsupported operations are never
     * rewritten into another SQL construct.
     */
    fun supportsSetOperation(operation: SetOperation): Boolean = when (operation) {
        SetOperation.IntersectAll,
        SetOperation.ExceptAll,
        -> false

        else -> true
    }

    /** Chooses the native keyword for one typed set operation. */
    fun renderSetOperation(operation: SetOperation): String {
        if (!supportsSetOperation(operation)) {
            throw UnsupportedCapability(operation.unsupportedCapability)
        }
        return operation.sql
    }

    /** Chooses the WITH keyword once a clause contains at least one recursive CTE definition. */
    fun renderWithKeyword(hasRecursiveCte: Boolean): String = if (hasRecursiveCte) "WITH RECURSIVE" else "WITH"

    /**
     * Chooses where a lexical CTE scope is rendered for a statement kind.
     *
     * The query AST records only the scope-to-statement relationship. Dialects own this grammar
     * choice because, for example, MySQL and Oracle place a CTE for INSERT ... SELECT directly
     * before the SELECT source rather than before INSERT.
     */
    fun withClausePlacement(statement: SqlStatementKind): SqlWithClausePlacement =
        SqlWithClausePlacement.BeforeStatement

    /** Rejects a known unsupported CTE/DML pairing before SQL reaches the database. */
    fun validateWithClause(statement: SqlStatementKind, hasRecursiveCte: Boolean) {
        if (withClausePlacement(statement) == SqlWithClausePlacement.Unsupported) {
            throw UnsupportedCapability("with-${statement.name.lowercase()}")
        }
    }

    /** Rejects known DML result-clause combinations before execution. */
    fun validateDmlReturningInto(statement: SqlStatementKind, insertUsesQuerySource: Boolean) = Unit

    /**
     * Validates the portable subset used by a recursive member before it is rendered.
     *
     * The default deliberately accepts only a direct SELECT with no grouping, distinctness,
     * aggregates, windows, ordering, or pagination. These constructs differ materially between
     * recursive implementations; a dialect can widen this method when it owns that contract.
     */
    fun validateRecursiveCteMember(member: QueryExpression<*, *>) {
        require(member is SelectQuery<*, *, *>) { "Recursive CTE member must be a SELECT query" }
        require(member.withClause.isEmpty()) { "Recursive CTE member cannot declare a nested WITH clause" }
        require(member.windows.isEmpty()) { "Recursive CTE member cannot declare a WINDOW clause in the portable API" }
        require(!member.distinct) { "Recursive CTE member cannot use DISTINCT in the portable API" }
        require(member.groupBy.isEmpty() && member.having == null) {
            "Recursive CTE member cannot use GROUP BY or HAVING in the portable API"
        }
        require(member.columns.none(SqlExpression<*>::containsAggregate)) {
            "Recursive CTE member cannot use aggregate functions in the portable API"
        }
        require(
            member.columns.none(SqlExpression<*>::containsWindowExpression) &&
                member.order.none { it.expression.containsWindowExpression() },
        ) {
            "Recursive CTE member cannot use window functions in the portable API"
        }
        require(member.order.isEmpty() && member.limit == null && member.offset == null) {
            "Recursive CTE member cannot use ORDER BY, LIMIT, or OFFSET in the portable API"
        }
    }

    /** Supplies the relation required by dialects that do not permit a source-less SELECT. */
    fun sourceLessSelectRelation(): String? = null

    /**
     * Allows a dialect to make an anchor parameter's result type explicit for recursive CTE type
     * inference. The projection identity remains unchanged; this affects only SQL rendering.
     */
    fun renderRecursiveCteAnchorProjection(projection: SqlExpression<*>, reference: String): String = reference

    /**
     * Chooses where pagination parameters and clauses occur in a SELECT statement.
     *
     * Most dialects place both clauses after ORDER BY. T-SQL places TOP immediately
     * after SELECT and places OFFSET/FETCH after ORDER BY, so this is intentionally a
     * dialect decision rather than a conditional in the generic renderer.
     */
    fun selectPagination(limit: Int?, offset: Int?, hasOrderBy: Boolean): SqlSelectPagination =
        LimitOffsetPagination(limit, offset)

    /**
     * Whether an ORDER BY is legal inside a scalar, predicate, or FROM subquery.
     *
     * The query AST deliberately does not rewrite ORDER BY away: it can determine a
     * scalar value together with a limit. Dialects that prohibit a bare nested ORDER
     * BY reject it at rendering time instead.
     */
    fun supportsOrderByInSubquery(limit: Int?, offset: Int?): Boolean = true

    /** Allows a dialect to select a lossless result transport for a projected column. */
    fun renderSelectColumn(column: Column<*>, reference: String): String = reference

    /** Allows a dialect to select a lossless result transport for any projected expression. */
    fun renderSelectExpression(expression: SqlExpression<*>, reference: String): String = when (expression) {
        is Column<*> -> renderSelectColumn(expression, reference)
        is RelationColumn<*> -> renderSelectColumn(expression.column, reference)
        is NullableRelationColumn<*> -> renderSelectColumn(expression.column, reference)
        is NullableSqlExpression<*> -> renderSelectExpression(expression.expression, reference)
        else -> reference
    }

    /** Chooses dialect spelling for a built-in function. */
    @Suppress("CyclomaticComplexMethod") // The explicit function catalog is intentionally one exhaustive mapping.
    fun renderFunctionName(function: SqlFunction): String = when (function) {
        SqlFunction.Lower -> "LOWER"
        SqlFunction.Upper -> "UPPER"
        SqlFunction.Length -> "LENGTH"
        SqlFunction.Trim -> "TRIM"
        SqlFunction.LeftTrim -> "LTRIM"
        SqlFunction.RightTrim -> "RTRIM"
        SqlFunction.Substring -> "SUBSTRING"
        SqlFunction.Replace -> "REPLACE"
        SqlFunction.Concat -> "CONCAT"
        SqlFunction.Coalesce -> "COALESCE"
        SqlFunction.NullIf -> "NULLIF"
        SqlFunction.Abs -> "ABS"
        SqlFunction.Round -> "ROUND"
        SqlFunction.Floor -> "FLOOR"
        SqlFunction.Ceiling -> "CEIL"
        SqlFunction.SquareRoot -> "SQRT"
        SqlFunction.Power -> "POWER"
        SqlFunction.CurrentDate -> "CURRENT_DATE"
        SqlFunction.CurrentTime -> "CURRENT_TIME"
        SqlFunction.CurrentTimestamp -> "CURRENT_TIMESTAMP"
        SqlFunction.Year -> "YEAR"
        SqlFunction.Month -> "MONTH"
        SqlFunction.Day -> "DAY"
        SqlFunction.Hour -> "HOUR"
        SqlFunction.Minute -> "MINUTE"
        SqlFunction.Second -> "SECOND"
        SqlFunction.Count -> "COUNT"
        SqlFunction.Sum -> "SUM"
        SqlFunction.Average -> "AVG"
        SqlFunction.Minimum -> "MIN"
        SqlFunction.Maximum -> "MAX"
        SqlFunction.Grouping -> "GROUPING"
        SqlFunction.RowNumber -> "ROW_NUMBER"
        SqlFunction.Rank -> "RANK"
        SqlFunction.DenseRank -> "DENSE_RANK"
        SqlFunction.PercentRank -> "PERCENT_RANK"
        SqlFunction.CumeDist -> "CUME_DIST"
        SqlFunction.Lag -> "LAG"
        SqlFunction.Lead -> "LEAD"
        SqlFunction.FirstValue -> "FIRST_VALUE"
        SqlFunction.LastValue -> "LAST_VALUE"
        SqlFunction.Ntile -> "NTILE"
        SqlFunction.NthValue -> "NTH_VALUE"
        is SqlFunction.Unsafe -> function.name
    }

    /** Renders a function after its typed argument expressions have been rendered. */
    fun renderFunction(function: SqlFunction, arguments: List<String>): String = when (function) {
        SqlFunction.CurrentDate,
        SqlFunction.CurrentTime,
        SqlFunction.CurrentTimestamp,
        -> {
            require(arguments.isEmpty()) { "$function does not accept arguments" }
            renderFunctionName(function)
        }

        SqlFunction.Year,
        SqlFunction.Month,
        SqlFunction.Day,
        SqlFunction.Hour,
        SqlFunction.Minute,
        -> {
            require(arguments.size == 1) { "$function requires exactly one expression" }
            "EXTRACT(${renderFunctionName(function)} FROM ${arguments.single()})"
        }

        SqlFunction.Second -> {
            require(arguments.size == 1) { "$function requires exactly one expression" }
            "FLOOR(EXTRACT(SECOND FROM ${arguments.single()}))"
        }

        else -> "${renderFunctionName(function)}(${if (function == SqlFunction.Count && arguments.isEmpty()) {
            "*"
        } else {
            arguments.joinToString(", ")
        }})"
    }

    /** Renders a DISTINCT aggregate argument without conflating it with SELECT DISTINCT. */
    fun renderFunction(function: SqlFunction, arguments: List<String>, distinct: Boolean): String = if (!distinct) {
        renderFunction(function, arguments)
    } else {
        require(arguments.isNotEmpty()) { "DISTINCT aggregate requires an argument" }
        "${renderFunctionName(function)}(DISTINCT ${arguments.joinToString(", ")})"
    }

    /**
     * Validates a typed value-window call before SQL is emitted.
     *
     * The common model uses the syntax shared by supported dialects. Dialects override this only
     * for documented capability gaps such as MariaDB's missing navigation default argument.
     */
    fun validateWindowFunction(function: SqlFunction, argumentCount: Int) = Unit

    /** Whether this dialect's supported baseline natively implements one window feature. */
    fun supportsWindow(capability: SqlWindowCapability): Boolean = false

    /**
     * Whether a value-window function accepts one explicit SQL NULL-treatment option.
     *
     * This is deliberately separate from the function's baseline availability: an engine can
     * render `FIRST_VALUE` while rejecting only its `IGNORE NULLS` option.
     */
    fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean = false

    /** Whether this dialect natively implements one typed `WITHIN GROUP` aggregate form. */
    fun supportsWithinGroup(capability: SqlWithinGroupCapability): Boolean = false

    /** Chooses the native function spelling for an already typed `WITHIN GROUP` aggregate. */
    fun renderWithinGroupFunctionName(function: WithinGroupAggregateKind): String = function.sqlName

    /**
     * Chooses a physical CAST target for one logical result mapping.
     *
     * DDL declarations are intentionally not reused implicitly: some engines accept a different
     * and smaller CAST target grammar than their CREATE TABLE type grammar.
     */
    fun renderCastTarget(target: SqlCastTarget<*>): String =
        throw UnsupportedCapability("cast-target:${target.type.name}")

    /** Quotes [identifier] and its optional [namespace] separately. */
    fun quoteQualifiedIdentifier(namespace: String?, identifier: String): String = buildString {
        namespace?.let {
            append(quoteIdentifier(it))
            append('.')
        }
        append(quoteIdentifier(identifier))
    }

    /** Quotes a table name together with its optional schema. */
    fun quoteTable(table: Table): String = quoteQualifiedIdentifier(table.schemaName, table.tableName)

    /** Renders one source in a FROM tree. Derived sources can extend this without changing SELECT. */
    fun renderRelationSource(source: RelationSource): String = buildString {
        val tableSource = source as? TableRelationSource
            ?: error("This dialect requires SqlRenderer to render non-table source: $source")
        append(quoteTable(tableSource.table))
        source.alias?.let { append(renderRelationAlias(it)) }
    }

    /** Renders a FROM-source alias. Oracle deliberately omits AS for relation aliases. */
    fun renderRelationAlias(alias: String): String = " AS ${quoteIdentifier(alias)}"

    /** Renders the qualifier used by a column reference from [source]. */
    fun quoteRelation(source: RelationSource): String = source.alias?.let(::quoteIdentifier) ?: run {
        val tableSource = source as? TableRelationSource
            ?: error("A non-table relation source must have an alias: $source")
        quoteTable(tableSource.table)
    }

    /** Whether this dialect accepts the requested standard join syntax without a semantic rewrite. */
    fun supportsJoin(type: JoinType): Boolean = true

    /** Whether this dialect has the native syntax required for one JOIN-condition semantic. */
    fun supportsJoinCapability(capability: SqlJoinCapability): Boolean = true

    /**
     * Renders one semantic left-correlated derived-relation join.
     *
     * The common AST records the correlation relation and CROSS/LEFT result semantics, rather
     * than a vendor keyword or a synthetic ON predicate. Dialects that use APPLY override this
     * method; dialects without stable lateral derived tables reject the capability here.
     */
    fun renderLateralJoin(type: LateralJoinType, relation: String): String = when (type) {
        LateralJoinType.Cross -> "CROSS JOIN LATERAL $relation"
        LateralJoinType.Left -> "LEFT JOIN LATERAL $relation ON TRUE"
    }

    /** Whether one native advanced-grouping feature is available without query rewriting. */
    fun supportsGrouping(capability: SqlGroupingCapability): Boolean = false

    /**
     * Renders the complete `GROUP BY` payload after validating every native feature it contains.
     * Dialects that use a different native grammar override this instead of changing semantics.
     */
    fun renderGroupBy(elements: List<GroupByElement>, renderExpression: (SqlExpression<*>) -> String): String {
        elements.flatMap(GroupByElement::requiredCapabilities).firstOrNull { !supportsGrouping(it) }?.let {
            throw UnsupportedCapability(it.unsupportedCapability)
        }
        return elements.joinToString(", ") { it.renderStandardGrouping(renderExpression) }
    }

    /** Rejects only dialect combinations that become illegal once advanced grouping is present. */
    fun validateGroupingOrder(elements: List<GroupByElement>, order: List<Order<*>>) = Unit
}

/** Window syntax/features that have materially different availability between supported engines.
 *
 * @property unsupportedCapability capability identifier used when rendering is unsupported.
 */
enum class SqlWindowCapability(val unsupportedCapability: String) {
    /** Native `NTILE` window function. */
    Ntile("window-ntile"),

    /** Native `NTH_VALUE` window function. */
    NthValue("window-nth-value"),

    /** Native `PERCENT_RANK` window function. */
    PercentRank("window-percent-rank"),

    /** Native `CUME_DIST` window function. */
    CumeDist("window-cume-dist"),

    /** `GROUPS` window-frame unit. */
    GroupsFrame("window-groups-frame"),

    /** Window-frame exclusion clauses. */
    FrameExclusion("window-frame-exclusion"),

    /** Named `WINDOW` clause. */
    NamedWindow("named-window"),
}

/** A dialect that implements the SQL-standard RETURNING clause for DML statements. */
interface SqlReturningDialect : SqlDialect

/** The native spelling selected by a dialect for a proposed inserted-row column reference. */
enum class SqlProposedInsertReferenceStyle {
    /** PostgreSQL/SQLite's special `excluded` relation. */
    Excluded,

    /** MySQL's non-deprecated row alias after VALUES, or its INSERT SELECT derived-table alias. */
    RowAlias,

    /** MariaDB's native `VALUES(column)` expression. */
    ValuesFunction,
}

/**
 * A dialect that renders one typed, dialect-specific INSERT conflict behavior.
 *
 * The common AST owns only the semantic behavior and typed expressions. This contract owns every
 * vendor syntax choice, including proposed-row spelling and SQLite's parser workaround.
 */
interface SqlInsertConflictDialect : SqlDialect {
    /** Chooses how assignments reference the row proposed for insertion. */
    fun proposedInsertReferenceStyle(behavior: InsertConflictBehavior): SqlProposedInsertReferenceStyle

    /** Whether INSERT SELECT requires a parser-disambiguating source clause before conflict syntax. */
    fun requiresInsertSelectConflictDisambiguation(behavior: InsertConflictBehavior): Boolean = false

    /** Renders the typed conflict behavior and its assignment and predicate expressions. */
    fun renderInsertConflict(
        behavior: InsertConflictBehavior,
        renderAssignments: (List<WriteAssignment>) -> String,
        renderPredicate: (SqlPredicate) -> String,
    ): String
}

/** A dialect that renders a typed DML row-image result clause. */
interface SqlDmlOutputDialect : SqlDialect {
    /** Renders a typed DML output clause. */
    fun renderDmlOutput(output: SqlDmlOutput): String
}

/** A dialect that renders a typed DML RETURNING INTO out-bind clause. */
interface SqlDmlReturningIntoDialect : SqlDialect {
    /** Renders a typed DML RETURNING INTO clause. */
    fun renderDmlReturningInto(returning: SqlDmlReturningInto): String
}

/** Double-quoted identifiers and question-mark parameters, without a database compatibility claim. */
object DoubleQuotedQuestionMarkDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    override fun parameterMarker(position: Int): String = "?"

    override fun supportsSetOperation(operation: SetOperation): Boolean = true

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = true

    override fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean = true

    override fun supportsWithinGroup(capability: SqlWithinGroupCapability): Boolean = true
}

/** Placement-specific rendering of SELECT pagination without exposing a vendor clause in the query model. */
interface SqlSelectPagination {
    /** Parameter positions rendered immediately after SELECT. */
    val selectParameters: List<Int>

    /** Parameter positions rendered after ORDER BY. */
    val trailingParameters: List<Int>

    /** Renders pagination syntax placed after SELECT. */
    fun renderAfterSelect(markers: List<String>): String

    /** Renders pagination syntax placed after ORDER BY. */
    fun renderAfterOrderBy(markers: List<String>): String
}

private class LimitOffsetPagination(private val limit: Int?, private val offset: Int?) : SqlSelectPagination {
    override val selectParameters: List<Int> = emptyList()
    override val trailingParameters: List<Int> = listOfNotNull(limit, offset)

    override fun renderAfterSelect(markers: List<String>): String = ""

    override fun renderAfterOrderBy(markers: List<String>): String = buildString {
        var index = 0
        if (limit != null) append(" LIMIT ").append(markers[index++])
        if (offset != null) append(" OFFSET ").append(markers[index])
    }
}

/** One bound value and the expression that supplies its storage mapping.
 *
 * @property value Kotlin value passed to the driver.
 * @property expression optional typed expression used to select a codec.
 */
data class SqlParameter<T>(val value: T, val expression: SqlExpression<*>?) {
    /** Compatibility view for driver bindings that have a physical table-column mapping. */
    val column: Column<*>? get() = expression as? Column<*>
}

/** Rendered SQL text with ordered bound parameters.
 *
 * @property sql SQL statement containing parameter markers.
 * @property parameters values in marker order.
 */
data class RenderedSql(val sql: String, val parameters: List<SqlParameter<*>>)

/** Dialect capability for native REPLACE INTO statements. */
interface SqlReplaceDialect

/** Renders a query model and keeps every runtime value separate from the SQL text. */
class SqlRenderer(private val dialect: SqlDialect) {
    /** Renders a row-producing query expression. */
    fun render(query: QueryExpression<*, *>): RenderedSql {
        val context = RenderContext(dialect)
        return RenderedSql(context.renderQuery(query), context.parameters.toList())
    }

    /** Renders an INSERT statement. */
    fun <S : InsertQueryState.Renderable> render(query: InsertQuery<S>): RenderedSql =
        DmlOutputRenderer(dialect).render(query)

    /** Renders a typed MERGE statement. */
    fun render(query: MergeQuery): RenderedSql = DmlOutputRenderer(dialect).render(query)

    /** Renders an UPDATE statement. */
    fun <S : UpdateQueryState.Renderable> render(query: UpdateQuery<S>): RenderedSql =
        DmlOutputRenderer(dialect).render(query)

    /** Renders a DELETE statement. */
    fun <S : DeleteQueryState.Renderable> render(query: DeleteQuery<S>): RenderedSql =
        DmlOutputRenderer(dialect).render(query)

    /** Renders a typed DML RETURNING query. */
    fun render(query: ReturningQuery): RenderedSql {
        if (dialect !is SqlReturningDialect) throw UnsupportedCapability("returning")
        val rendered = when (query) {
            is ReturningInsertQuery<*> -> render(query.query)
            is ReturningUpdateQuery<*> -> render(query.query)
            is ReturningDeleteQuery<*> -> render(query.query)
        }
        val returning = query.columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }
        return rendered.copy(sql = "${rendered.sql} RETURNING $returning")
    }
}

/** Renders DML statements with an optional dialect-specific row-image result clause. */
@Suppress("TooManyFunctions") // INSERT placement, DML kinds, and OUTPUT share one parameter context.
internal class DmlOutputRenderer(private val dialect: SqlDialect) {
    @Suppress("CyclomaticComplexMethod") // The two native MERGE grammars share one parameter context.
    fun render(query: MergeQuery): RenderedSql {
        val mergeDialect = dialect as? SqlMergeDialect ?: throw UnsupportedCapability("merge")
        if (mergeDialect.mergeKind != query.kind) throw UnsupportedCapability("merge-dialect")
        val context = RenderContext(dialect)
        val sql = buildString {
            append("MERGE INTO ").append(dialect.quoteTable(query.target))
            append(" USING ").append(context.renderMergeSource(query.source))
            append(" ON (").append(context.renderPredicate(query.on)).append(')')
            query.clauses.forEach { clause ->
                append(
                    when (clause.match) {
                        MergeMatch.MATCHED -> " WHEN MATCHED"
                        MergeMatch.NOT_MATCHED -> " WHEN NOT MATCHED"
                        MergeMatch.NOT_MATCHED_BY_SOURCE -> " WHEN NOT MATCHED BY SOURCE"
                    },
                )
                if (query.kind == SqlMergeKind.SQL_SERVER) {
                    clause.predicate?.let { append(" AND ").append(context.renderPredicate(it)) }
                }
                when (val action = clause.action) {
                    is MergeAction.Update -> {
                        append(" THEN UPDATE SET ").append(renderWriteAssignments(action.assignments, context))
                        if (query.kind == SqlMergeKind.ORACLE) {
                            clause.predicate?.let { append(" WHERE ").append(context.renderPredicate(it)) }
                            action.deleteWhere?.let { append(" DELETE WHERE ").append(context.renderPredicate(it)) }
                        }
                    }

                    MergeAction.Delete -> append(" THEN DELETE")

                    is MergeAction.Insert -> {
                        append(" THEN INSERT (")
                        append(action.assignments.joinToString(", ") { dialect.quoteIdentifier(it.column.name) })
                        append(") VALUES (")
                        append(
                            action.assignments.joinToString(", ") {
                                when (it) {
                                    is ColumnAssignment<*> -> context.addColumnParameter(it)
                                    is ExpressionAssignment<*> -> context.renderExpression(it.expression)
                                }
                            },
                        )
                        append(')')
                        if (query.kind == SqlMergeKind.ORACLE) {
                            clause.predicate?.let { append(" WHERE ").append(context.renderPredicate(it)) }
                        }
                    }
                }
            }
            if (query.kind == SqlMergeKind.SQL_SERVER) append(';')
        }
        return RenderedSql(sql, context.parameters.toList())
    }

    fun <S : InsertQueryState.Renderable> render(query: InsertQuery<S>): RenderedSql = renderInsert(query, null)

    fun <S : InsertQueryState.Renderable> render(query: InsertQuery<S>, output: SqlDmlOutput): RenderedSql =
        renderInsert(query, output)

    @Suppress("ThrowsCount") // Each unsupported dialect capability is rejected at its boundary.
    private fun <S : InsertQueryState.Renderable> renderInsert(
        query: InsertQuery<S>,
        output: SqlDmlOutput?,
    ): RenderedSql {
        query.requireWriteValidScope()
        val context = RenderContext(dialect)
        val source = requireNotNull(query.source) { "INSERT requires a VALUES or query source" }
        if (query.conflictBehavior === ReplaceInsertBehavior && dialect !is SqlReplaceDialect) {
            throw UnsupportedCapability("replace")
        }
        val conflict = query.conflictBehavior?.takeUnless { it === ReplaceInsertBehavior }?.let { behavior ->
            val conflictDialect = dialect as? SqlInsertConflictDialect
                ?: throw UnsupportedCapability("insert-conflict")
            InsertConflictRendering(
                behavior = behavior,
                dialect = conflictDialect,
                source = source,
                table = query.into,
            )
        }
        val placement = withPlacement(query.withClause, SqlStatementKind.Insert)
        val sql = when (placement) {
            SqlWithClausePlacement.BeforeStatement -> {
                val withClause = context.renderWithClause(query.withClause)
                withClause + renderInsertHead(
                    query,
                    source,
                    output,
                ) + renderInsertSource(source, context, query.withClause, conflict) +
                    renderInsertConflict(conflict, context)
            }

            SqlWithClausePlacement.BeforeInsertSelect -> {
                val querySource = source as? InsertSource.Query
                    ?: throw UnsupportedCapability("with-insert-values")
                renderInsertHead(
                    query,
                    querySource,
                    output,
                ) + " " + context.renderWithClause(query.withClause).trimEnd() +
                    renderInsertSource(querySource, context, query.withClause, conflict) +
                    renderInsertConflict(conflict, context)
            }

            SqlWithClausePlacement.Unsupported -> error("Unsupported WITH placement was not rejected")
        }
        return RenderedSql(sql, context.parameters.toList())
    }

    fun <S : UpdateQueryState.Renderable> render(query: UpdateQuery<S>): RenderedSql = renderUpdate(query, null)

    fun <S : UpdateQueryState.Renderable> render(query: UpdateQuery<S>, output: SqlDmlOutput): RenderedSql =
        renderUpdate(query, output)

    private fun <S : UpdateQueryState.Renderable> renderUpdate(
        query: UpdateQuery<S>,
        output: SqlDmlOutput?,
    ): RenderedSql {
        query.requireWriteValidScope()
        val context = RenderContext(dialect)
        withPlacement(query.withClause, SqlStatementKind.Update)
        val withClause = context.renderWithClause(query.withClause)
        val body = buildString {
            append("UPDATE ")
            append(dialect.quoteTable(query.table))
            append(" SET ")
            append(renderWriteAssignments(query.assignments, context))
            output?.let {
                append(' ')
                append(dmlOutput(it))
            }
            append(
                dialect.renderUpdateFrom(
                    query.fromSources.map { source -> context.renderRelationSource(source, query.withClause) },
                ),
            )
            query.predicate?.let {
                append(" WHERE ")
                append(context.renderPredicate(it, availableCtes = query.withClause))
            }
        }
        return RenderedSql(withClause + body, context.parameters.toList())
    }

    fun <S : DeleteQueryState.Renderable> render(query: DeleteQuery<S>): RenderedSql = renderDelete(query, null)

    fun <S : DeleteQueryState.Renderable> render(query: DeleteQuery<S>, output: SqlDmlOutput): RenderedSql =
        renderDelete(query, output)

    private fun <S : DeleteQueryState.Renderable> renderDelete(
        query: DeleteQuery<S>,
        output: SqlDmlOutput?,
    ): RenderedSql {
        query.requireWriteValidScope()
        val context = RenderContext(dialect)
        withPlacement(query.withClause, SqlStatementKind.Delete)
        val withClause = context.renderWithClause(query.withClause)
        val body = buildString {
            append("DELETE FROM ")
            append(dialect.quoteTable(query.from))
            output?.let {
                append(' ')
                append(dmlOutput(it))
            }
            query.predicate?.let {
                append(" WHERE ")
                append(context.renderPredicate(it, availableCtes = query.withClause))
            }
        }
        return RenderedSql(withClause + body, context.parameters.toList())
    }

    private fun withPlacement(
        definitions: List<CommonTableExpression>,
        statement: SqlStatementKind,
    ): SqlWithClausePlacement {
        if (definitions.isEmpty()) return SqlWithClausePlacement.BeforeStatement
        dialect.validateWithClause(statement, definitions.any(CommonTableExpression::isRecursive))
        return dialect.withClausePlacement(statement)
    }

    private fun renderInsertHead(query: InsertQuery<*>, source: InsertSource, output: SqlDmlOutput?): String =
        buildString {
            val columns = when (source) {
                is InsertSource.Values -> source.rows.first().assignments.map(ColumnAssignment<*>::column)
                is InsertSource.Query -> source.targetColumns
            }
            append(if (query.conflictBehavior === ReplaceInsertBehavior) "REPLACE INTO " else "INSERT INTO ")
            append(dialect.quoteTable(query.into))
            append(" (")
            append(columns.joinToString(", ") { dialect.quoteIdentifier(it.name) })
            append(")")
            output?.let {
                append(' ')
                append(dmlOutput(it))
            }
        }

    private fun renderInsertSource(
        source: InsertSource,
        context: RenderContext,
        availableCtes: List<CommonTableExpression>,
        conflict: InsertConflictRendering?,
    ): String = when (source) {
        is InsertSource.Values -> buildString {
            append(" VALUES ")
            append(
                source.rows.joinToString(", ") { row ->
                    row.assignments.joinToString(prefix = "(", postfix = ")") {
                        context.addColumnParameter(it)
                    }
                },
            )
            conflict?.rowAlias?.let { alias ->
                append(" AS ")
                append(dialect.quoteIdentifier(alias))
            }
        }

        is InsertSource.Query -> renderInsertQuerySource(source, context, availableCtes, conflict)
    }

    private fun renderInsertQuerySource(
        source: InsertSource.Query,
        context: RenderContext,
        availableCtes: List<CommonTableExpression>,
        conflict: InsertConflictRendering?,
    ): String {
        val rendered = context.renderQuery(
            source.query,
            availableCtes = availableCtes,
            appendWhereTrue = conflict?.requiresSelectDisambiguation == true && source.query is SelectQuery<*, *, *> &&
                source.query.predicate == null,
        )
        return when {
            conflict?.rowAlias != null -> " SELECT * FROM ($rendered) AS ${dialect.quoteIdentifier(conflict.rowAlias)}"

            conflict?.requiresSelectDisambiguation == true && source.query !is SelectQuery<*, *, *> -> {
                val alias = conflict.sqliteDisambiguationAlias
                " SELECT * FROM ($rendered) AS ${dialect.quoteIdentifier(alias)} WHERE TRUE"
            }

            else -> " $rendered"
        }
    }

    private fun renderInsertConflict(conflict: InsertConflictRendering?, context: RenderContext): String {
        if (conflict == null) return ""
        val proposedReference: (Column<*>) -> String = { column -> conflict.proposedReference(column) }
        return " " + conflict.dialect.renderInsertConflict(
            conflict.behavior,
            renderAssignments = { assignments ->
                renderWriteAssignments(assignments, context, proposedReference)
            },
            renderPredicate = { predicate -> context.renderConflictPredicate(predicate, proposedReference) },
        )
    }

    private fun renderWriteAssignments(
        assignments: List<WriteAssignment>,
        context: RenderContext,
        proposedReference: ((Column<*>) -> String)? = null,
    ): String = assignments.joinToString(", ") { assignment ->
        val value = when (assignment) {
            is ColumnAssignment<*> -> context.addColumnParameter(assignment)

            is ExpressionAssignment<*> -> if (proposedReference == null) {
                context.renderExpression(assignment.expression)
            } else {
                context.renderConflictExpression(assignment.expression, proposedReference)
            }
        }
        "${dialect.quoteIdentifier(assignment.column.name)} = $value"
    }

    private fun dmlOutput(output: SqlDmlOutput): String {
        val outputDialect = dialect as? SqlDmlOutputDialect ?: throw UnsupportedCapability("output")
        return outputDialect.renderDmlOutput(output)
    }
}

/** Rendering-only plan for one conflict clause; no alias or workaround enters the public AST. */
private class InsertConflictRendering(
    val behavior: InsertConflictBehavior,
    val dialect: SqlInsertConflictDialect,
    private val source: InsertSource,
    table: Table,
) {
    private val style = dialect.proposedInsertReferenceStyle(behavior)
    val rowAlias: String? = if (style == SqlProposedInsertReferenceStyle.RowAlias) {
        generatedInternalAlias("kagiroi_inserted", source, table)
    } else {
        null
    }
    val requiresSelectDisambiguation = dialect.requiresInsertSelectConflictDisambiguation(behavior)
    val sqliteDisambiguationAlias: String = generatedInternalAlias("kagiroi_upsert_source", source, table)

    fun proposedReference(column: Column<*>): String = when (style) {
        SqlProposedInsertReferenceStyle.Excluded -> "excluded.${dialect.quoteIdentifier(column.name)}"

        SqlProposedInsertReferenceStyle.ValuesFunction -> "VALUES(${dialect.quoteIdentifier(column.name)})"

        SqlProposedInsertReferenceStyle.RowAlias -> {
            val alias = requireNotNull(rowAlias)
            val outputName = when (source) {
                is InsertSource.Values -> column.name
                is InsertSource.Query -> source.proposedOutputName(column)
            }
            "${dialect.quoteIdentifier(alias)}.${dialect.quoteIdentifier(outputName)}"
        }
    }

    private fun InsertSource.Query.proposedOutputName(column: Column<*>): String {
        val index = targetColumns.indexOfFirst { it === column }
        require(index >= 0) {
            "Proposed inserted-row column is not an INSERT ... SELECT target column: $column"
        }
        return projectionName(query.columns[index])
    }
}

private fun generatedInternalAlias(prefix: String, source: InsertSource, table: Table): String {
    val reserved = buildSet {
        add(table.tableName)
        addAll(table.columns.map { it.name })
        if (source is InsertSource.Query) {
            addAll(source.query.relationAliases())
        }
    }
    var alias = prefix
    while (alias in reserved) alias += "_"
    return alias
}

/** Collects aliases that can be visible around a renderer-owned INSERT source wrapper. */
private fun QueryExpression<*, *>.relationAliases(): List<String> = when (this) {
    is SelectQuery<*, *, *> -> buildList {
        addAll(sources.mapNotNull(RelationSource::alias))
        addAll(withClause.map(CommonTableExpression::name))
    }

    is SetOperationQuery<*, *> -> left.relationAliases() + right.relationAliases() +
        withClause.map(CommonTableExpression::name)

    else -> emptyList()
}

@Suppress("LargeClass", "TooManyFunctions") // Shared state keeps parameter positions global across nested queries.
private class RenderContext(private val dialect: SqlDialect) {
    val parameters = mutableListOf<SqlParameter<*>>()
    private var nativeProjectionDepth = 0

    /** Renders one lexical CTE scope through this context's shared parameter collector. */
    fun renderWithClause(
        definitions: List<CommonTableExpression>,
        availableCtes: List<CommonTableExpression> = emptyList(),
    ): String = buildString {
        appendWithClause(definitions, availableCtes)
    }

    fun renderQuery(
        query: QueryExpression<*, *>,
        parentSources: List<RelationSource> = emptyList(),
        nested: Boolean = false,
        availableCtes: List<CommonTableExpression> = emptyList(),
        recursiveSelf: RecursiveCteSelf? = null,
        recursiveAnchor: Boolean = false,
        appendWhereTrue: Boolean = false,
    ): String = when (query) {
        is SelectQuery<*, *, *> -> renderSelectQuery(
            query,
            parentSources,
            nested,
            availableCtes,
            recursiveSelf,
            recursiveAnchor,
            appendWhereTrue,
        )

        is SetOperationQuery<*, *> -> renderSetOperationQuery(
            query,
            parentSources,
            availableCtes,
            recursiveSelf,
            recursiveAnchor,
        )

        else -> error("Unsupported query expression: $query")
    }

    fun renderSelectQuery(
        query: SelectQuery<*, *, *>,
        parentSources: List<RelationSource> = emptyList(),
        nested: Boolean = false,
        availableCtes: List<CommonTableExpression> = emptyList(),
        recursiveSelf: RecursiveCteSelf? = null,
        recursiveAnchor: Boolean = false,
        appendWhereTrue: Boolean = false,
    ): String {
        query.requireSelectValidScope(parentSources, availableCtes, recursiveSelf)
        validateSelectWithClause(query.withClause)
        require(!nested || query.order.isEmpty() || dialect.supportsOrderByInSubquery(query.limit, query.offset)) {
            "This dialect does not allow ORDER BY in a subquery without LIMIT or OFFSET"
        }
        val visibleSources = query.sources + parentSources
        val visibleCtes = availableCtes + query.withClause
        val pagination = dialect.selectPagination(query.limit, query.offset, query.order.isNotEmpty())
        return buildString {
            appendWithClause(query.withClause, availableCtes)
            renderSelectBody(query, visibleSources, visibleCtes, pagination, recursiveAnchor, appendWhereTrue)
        }
    }

    private fun renderSetOperationQuery(
        query: SetOperationQuery<*, *>,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
        recursiveSelf: RecursiveCteSelf?,
        recursiveAnchor: Boolean,
    ): String {
        query.requireValidScope(parentSources, availableCtes, recursiveSelf)
        validateSelectWithClause(query.withClause)
        val visibleCtes = availableCtes + query.withClause
        val pagination = dialect.selectPagination(query.limit, query.offset, query.order.isNotEmpty())
        return buildString {
            appendWithClause(query.withClause, availableCtes)
            val body = renderSetOperand(query.left, parentSources, visibleCtes, recursiveSelf, recursiveAnchor) + " " +
                dialect.renderSetOperation(query.operation) + " " +
                renderSetOperand(query.right, parentSources, visibleCtes, recursiveSelf, recursiveAnchor)
            if (pagination.selectParameters.isEmpty()) {
                append(body)
                renderSetOrdering(query.order)
                append(pagination.renderAfterOrderBy(addParameters(pagination.trailingParameters)))
            } else {
                append("SELECT ")
                append(pagination.renderAfterSelect(addParameters(pagination.selectParameters)))
                append("* FROM (")
                append(body)
                append(')')
                append(dialect.renderRelationAlias(SET_RESULT_ALIAS))
                renderSetOrdering(query.order, SET_RESULT_ALIAS)
                append(pagination.renderAfterOrderBy(addParameters(pagination.trailingParameters)))
            }
        }
    }

    private fun renderSetOperand(
        query: QueryExpression<*, *>,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
        recursiveSelf: RecursiveCteSelf?,
        recursiveAnchor: Boolean,
    ): String {
        val modifiersNeedBoundary = when (query) {
            is SelectQuery<*, *, *> -> query.order.isNotEmpty() || query.limit != null || query.offset != null
            is SetOperationQuery<*, *> -> true
            else -> error("Unsupported query expression: $query")
        }
        val needsBoundary = modifiersNeedBoundary || query.withClause().isNotEmpty()
        val rendered = renderQuery(
            query,
            parentSources,
            nested = true,
            availableCtes = availableCtes,
            recursiveSelf = recursiveSelf,
            recursiveAnchor = recursiveAnchor,
        )
        return if (needsBoundary) {
            "SELECT * FROM ($rendered)${dialect.renderRelationAlias(SET_OPERAND_ALIAS)}"
        } else {
            rendered
        }
    }

    private fun StringBuilder.renderSetOrdering(order: List<Order<*>>, relationAlias: String? = null) {
        if (order.isEmpty()) return
        append(" ORDER BY ")
        append(
            order.joinToString(", ") { entry ->
                val name = dialect.quoteIdentifier(projectionName(entry.expression))
                val reference = relationAlias?.let { "${dialect.quoteIdentifier(it)}.$name" } ?: name
                "$reference ${dialect.renderOrder(entry.direction, entry.nullOrdering)}"
            },
        )
    }

    private fun StringBuilder.appendWithClause(
        definitions: List<CommonTableExpression>,
        availableCtes: List<CommonTableExpression>,
    ) {
        if (definitions.isEmpty()) return
        append(dialect.renderWithKeyword(definitions.any(CommonTableExpression::isRecursive)))
        append(' ')
        definitions.forEachIndexed { index, definition ->
            if (index > 0) append(", ")
            append(dialect.quoteIdentifier(definition.name))
            if (definition is RecursiveCommonTableExpression) {
                append('(')
                append(
                    definition.anchor.columns.joinToString(", ") { projection ->
                        dialect.quoteIdentifier(projectionName(projection))
                    },
                )
                append(')')
            }
            append(" AS (")
            val preceding = availableCtes + definitions.take(index)
            if (definition is RecursiveCommonTableExpression) {
                append(
                    renderQuery(
                        definition.anchor,
                        nested = true,
                        availableCtes = preceding,
                        recursiveAnchor = true,
                    ),
                )
                append(' ')
                append(dialect.renderSetOperation(SetOperation.UnionAll))
                append(' ')
                dialect.validateRecursiveCteMember(definition.recursive)
                append(
                    renderQuery(
                        definition.recursive,
                        nested = true,
                        availableCtes = preceding,
                        recursiveSelf = definition.self,
                    ),
                )
            } else {
                append(renderQuery(definition.query, nested = true, availableCtes = preceding))
            }
            append(')')
        }
        append(' ')
    }

    private fun validateSelectWithClause(definitions: List<CommonTableExpression>) {
        if (definitions.isNotEmpty()) {
            dialect.validateWithClause(SqlStatementKind.Select, definitions.any(CommonTableExpression::isRecursive))
        }
    }

    private fun StringBuilder.renderSelectBody(
        query: SelectQuery<*, *, *>,
        visibleSources: List<RelationSource>,
        visibleCtes: List<CommonTableExpression>,
        pagination: SqlSelectPagination,
        recursiveAnchor: Boolean,
        appendWhereTrue: Boolean,
    ) {
        dialect.validateGroupingOrder(query.groupBy, query.order)
        append("SELECT ")
        append(
            dialect.renderSelectDistinct(
                query.distinct,
                query.distinctOn.map { expression -> renderExpression(expression, visibleSources, visibleCtes) },
            ),
        )
        append(pagination.renderAfterSelect(addParameters(pagination.selectParameters)))
        append(
            query.columns.joinToString(", ") { projection ->
                renderProjection(projection, visibleSources, visibleCtes, recursiveAnchor)
            },
        )
        query.fromSource?.let { source ->
            append(" FROM ")
            append(renderRelationSource(source, visibleCtes))
        } ?: dialect.sourceLessSelectRelation()?.let { relation -> append(" FROM ").append(relation) }
        renderJoins(query.joins, visibleSources, visibleCtes)
        if (query.predicate != null) {
            append(" WHERE ")
            append(renderPredicate(query.predicate, visibleSources, visibleCtes))
        } else if (appendWhereTrue) {
            append(" WHERE TRUE")
        }
        renderGrouping(query, visibleSources, visibleCtes)
        renderNamedWindows(query.windows, visibleSources, visibleCtes)
        renderOrdering(query.order, visibleSources, visibleCtes)
        append(pagination.renderAfterOrderBy(addParameters(pagination.trailingParameters)))
    }

    private fun renderProjection(
        projection: SqlExpression<*>,
        visibleSources: List<RelationSource>,
        visibleCtes: List<CommonTableExpression>,
        recursiveAnchor: Boolean,
    ): String {
        val alias = projection.projectionAlias()
        val expression = alias?.expression ?: projection.unwrappedNullableExpression()
        val reference = renderExpression(expression, visibleSources, visibleCtes)
        val rendered = if (nativeProjectionDepth > 0) {
            reference
        } else {
            dialect.renderSelectExpression(
                expression,
                reference,
            )
        }
        val recursiveAnchorRendered = if (recursiveAnchor) {
            dialect.renderRecursiveCteAnchorProjection(projection, rendered)
        } else {
            rendered
        }
        return if (alias != null) {
            "$recursiveAnchorRendered AS ${dialect.quoteIdentifier(alias.name)}"
        } else {
            recursiveAnchorRendered
        }
    }

    private fun SqlExpression<*>.projectionAlias(): SqlExpressionAlias<*>? = when (this) {
        is SqlExpressionAlias<*> -> this
        is NullableSqlExpression<*> -> expression.projectionAlias()
        else -> null
    }

    private fun SqlExpression<*>.unwrappedNullableExpression(): SqlExpression<*> = when (this) {
        is NullableSqlExpression<*> -> expression.unwrappedNullableExpression()
        else -> this
    }

    private fun StringBuilder.renderJoins(
        joins: List<Join>,
        visibleSources: List<RelationSource>,
        visibleCtes: List<CommonTableExpression>,
    ) {
        joins.forEach { join ->
            val lateralType = join.type.lateralType
            if (lateralType != null) {
                val lateral = join.right as LateralRelation
                append(' ')
                append(
                    dialect.renderLateralJoin(
                        lateralType,
                        renderLateralRelation(lateral, visibleCtes),
                    ),
                )
            } else {
                if (!dialect.supportsJoin(join.type)) throw UnsupportedCapability("${join.type.name.lowercase()}-join")
                append(" ${join.type.sql} ")
                append(renderRelationSource(join.right, visibleCtes))
                when (val condition = join.condition) {
                    null -> Unit

                    is JoinCondition.On -> {
                        append(" ON ")
                        append(renderPredicate(condition.predicate, visibleSources, visibleCtes))
                    }

                    is JoinCondition.Using -> {
                        if (!dialect.supportsJoinCapability(SqlJoinCapability.Using)) {
                            throw UnsupportedCapability(SqlJoinCapability.Using.unsupportedCapability)
                        }
                        append(" USING (")
                        append(condition.pairs.joinToString(", ") { pair -> dialect.quoteIdentifier(pair.name) })
                        append(')')
                    }
                }
            }
        }
    }

    private fun StringBuilder.renderGrouping(
        query: SelectQuery<*, *, *>,
        visibleSources: List<RelationSource>,
        visibleCtes: List<CommonTableExpression>,
    ) {
        if (query.groupBy.isNotEmpty()) {
            append(" GROUP BY ")
            append(
                dialect.renderGroupBy(query.groupBy) { expression ->
                    renderExpression(expression, visibleSources, visibleCtes)
                },
            )
        }
        query.having?.let {
            append(" HAVING ")
            append(renderPredicate(it, visibleSources, visibleCtes))
        }
    }

    private fun StringBuilder.renderNamedWindows(
        windows: List<NamedWindow>,
        visibleSources: List<RelationSource>,
        visibleCtes: List<CommonTableExpression>,
    ) {
        if (windows.isEmpty()) return
        if (!dialect.supportsWindow(SqlWindowCapability.NamedWindow)) {
            throw UnsupportedCapability(SqlWindowCapability.NamedWindow.unsupportedCapability)
        }
        windows.forEach { named -> validateWindowSpecificationCapabilities(named.specification) }
        append(" WINDOW ")
        append(
            windows.joinToString(", ") { named ->
                "${dialect.quoteIdentifier(named.name)} AS (" +
                    renderWindowSpecification(named.specification, visibleSources, visibleCtes) + ")"
            },
        )
    }

    private fun StringBuilder.renderOrdering(
        order: List<Order<*>>,
        visibleSources: List<RelationSource>,
        visibleCtes: List<CommonTableExpression>,
    ) {
        if (order.isEmpty()) return
        append(" ORDER BY ")
        append(
            order.joinToString(
                ", ",
            ) {
                "${renderExpression(it.expression, visibleSources, visibleCtes)} " +
                    dialect.renderOrder(it.direction, it.nullOrdering)
            },
        )
    }

    fun renderMergeSource(source: RelationSource): String {
        if (source !is DerivedTable) return renderRelationSource(source, emptyList())
        nativeProjectionDepth++
        return try {
            renderRelationSource(source, emptyList())
        } finally {
            nativeProjectionDepth--
        }
    }

    fun renderRelationSource(source: RelationSource, availableCtes: List<CommonTableExpression>): String =
        when (source) {
            is DerivedTable -> "(${renderQuery(
                source.query,
                nested = true,
                availableCtes = availableCtes,
            )})${dialect.renderRelationAlias(source.alias)}"

            is CommonTableExpression -> dialect.quoteIdentifier(source.name)

            is RecursiveCteSelf -> dialect.quoteIdentifier(source.sourceName)

            is LateralRelation -> error("Lateral relations must be rendered through a lateral join")

            else -> dialect.renderRelationSource(source)
        }

    /** Renders the relation body through the existing shared parameter context and exact left scope. */
    private fun renderLateralRelation(source: LateralRelation, availableCtes: List<CommonTableExpression>): String =
        "(${renderQuery(
            source.query,
            parentSources = source.outerDependencies,
            nested = true,
            availableCtes = availableCtes,
        )})${dialect.renderRelationAlias(source.alias)}"

    fun renderColumn(column: Column<*>): String =
        "${dialect.quoteTable(column.table)}.${dialect.quoteIdentifier(column.name)}"

    private fun renderRelationColumn(source: RelationSource, column: Column<*>): String =
        "${dialect.quoteRelation(source)}.${dialect.quoteIdentifier(column.name)}"

    private fun renderDerivedColumn(source: QueryResultRelation, name: String): String =
        "${dialect.quoteRelation(source)}.${dialect.quoteIdentifier(name)}"

    @Suppress("CyclomaticComplexMethod")
    fun renderExpression(
        expression: SqlExpression<*>,
        parentSources: List<RelationSource> = emptyList(),
        availableCtes: List<CommonTableExpression> = emptyList(),
    ): String = when (expression) {
        is NullableSqlExpression<*> -> renderExpression(expression.expression, parentSources, availableCtes)

        is RelationColumn<*> -> renderRelationColumn(expression.source, expression.column)

        is NullableRelationColumn<*> -> renderRelationColumn(expression.source, expression.column)

        is DerivedColumn<*> -> renderDerivedColumn(expression.source, expression.name)

        is NullableDerivedColumn<*> -> renderDerivedColumn(expression.source, expression.name)

        is Column<*> -> renderColumn(expression)

        is ProposedInsertColumn<*> -> requireNotNull(proposedInsertReference) {
            "A proposed inserted-row reference is only valid in a dialect-specific conflict update"
        }(expression.column)

        is SqlExpressionAlias<*> -> renderExpression(expression.expression, parentSources, availableCtes)

        is SqlLiteral<*> -> addLiteralParameter(expression)

        is CastExpression<*> -> "CAST(${renderExpression(
            expression.expression,
            parentSources,
            availableCtes,
        )} AS ${dialect.renderCastTarget(expression.target)})"

        is CaseExpression<*> -> renderCaseExpression(expression, parentSources, availableCtes)

        is WindowExpression<*> -> renderWindowExpression(expression, parentSources, availableCtes)

        is WithinGroupAggregateExpression<*> -> renderWithinGroupAggregate(expression, parentSources, availableCtes)

        is SqlFunctionExpression<*> -> renderFunction(expression, parentSources, availableCtes)

        is NumericBinaryExpression<*> -> dialect.renderNumericBinary(
            expression.operator,
            renderExpression(expression.left, parentSources, availableCtes),
            renderExpression(expression.right, parentSources, availableCtes),
        )

        is GeneratedColumnBinaryExpression<*> -> "(${renderExpression(
            expression.left,
            parentSources,
            availableCtes,
        )} " +
            "${expression.operator.sql} ${renderExpression(expression.right, parentSources, availableCtes)})"

        is ScalarSubquery<*> -> "(${renderQuery(
            expression.query,
            parentSources,
            nested = true,
            availableCtes = availableCtes,
        )})"

        else -> error("Unsupported SQL expression: $expression")
    }

    private fun renderFunction(
        expression: SqlFunctionExpression<*>,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
    ): String {
        if (
            expression.function == SqlFunction.Grouping &&
            !dialect.supportsGrouping(SqlGroupingCapability.GroupingFunction)
        ) {
            throw UnsupportedCapability(SqlGroupingCapability.GroupingFunction.unsupportedCapability)
        }
        val arguments = when (expression) {
            is WindowFunctionExpression<*> -> expression.integerArgument?.let { listOf(it.toString()) } ?: emptyList()

            is ValueWindowFunctionExpression<*> -> renderValueWindowFunctionArguments(
                expression,
                parentSources,
                availableCtes,
            )

            else -> expression.arguments.map { argument ->
                val rendered = renderExpression(argument, parentSources, availableCtes)
                if (
                    expression is dev.quwac.kagiroi.schema.AggregateFunctionExpression<*> &&
                    argument is CaseExpression<*>
                ) {
                    "CAST($rendered AS ${dialect.renderCastTarget(argument.target)})"
                } else {
                    rendered
                }
            }
        }
        dialect.validateWindowFunction(expression.function, arguments.size)
        return dialect.renderFunction(
            expression.function,
            arguments,
            (expression as? dev.quwac.kagiroi.schema.AggregateFunctionExpression<*>)?.distinct == true,
        )
    }

    private fun renderValueWindowFunctionArguments(
        expression: ValueWindowFunctionExpression<*>,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
    ): List<String> = buildList {
        add(renderExpression(expression.value, parentSources, availableCtes))
        // Offset is SQL integer grammar from a validated AST field, not a runtime value.
        expression.offset?.takeIf {
            expression.function == SqlFunction.NthValue ||
                it != DEFAULT_WINDOW_NAVIGATION_OFFSET || expression.defaultValue != null
        }
            ?.let { add(it.toString()) }
        // Defaults remain ordinary typed query expressions and retain normal parameter binding.
        expression.defaultValue?.let { default ->
            add(renderExpression(default, parentSources, availableCtes))
        }
    }

    private fun renderWithinGroupAggregate(
        expression: WithinGroupAggregateExpression<*>,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
    ): String {
        if (!dialect.supportsWithinGroup(expression.kind.capability)) {
            throw UnsupportedCapability(expression.kind.capability.unsupportedCapability)
        }
        val directArguments = expression.directArguments.joinToString(", ") {
            renderExpression(it, parentSources, availableCtes)
        }
        val order = expression.withinGroup.orderBy.joinToString(", ") { entry ->
            "${renderExpression(entry.expression, parentSources, availableCtes)} " +
                dialect.renderOrder(entry.direction, entry.nullOrdering)
        }
        return "${dialect.renderWithinGroupFunctionName(
            expression.kind,
        )}($directArguments) WITHIN GROUP (ORDER BY $order)"
    }

    private fun renderCaseExpression(
        expression: CaseExpression<*>,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
    ): String = buildString {
        append("CASE")
        expression.branches.forEach { branch ->
            append(" WHEN ")
            append(renderPredicate(branch.condition, parentSources, availableCtes))
            append(" THEN ")
            append(renderExpression(branch.result, parentSources, availableCtes))
        }
        append(" ELSE ")
        append(renderExpression(expression.elseResult, parentSources, availableCtes))
        append(" END")
    }

    private fun renderWindowExpression(
        expression: WindowExpression<*>,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
    ): String = buildString {
        validateWindowCapabilities(expression)
        append(renderFunction(expression.function, parentSources, availableCtes))
        (expression.function as? ValueWindowFunctionExpression<*>)?.nullTreatment?.let { treatment ->
            append(' ')
            append(treatment.sql)
        }
        val namedWindow = expression.namedWindow
        if (namedWindow == null) {
            append(" OVER (")
            append(renderWindowSpecification(expression.specification, parentSources, availableCtes))
            append(')')
        } else {
            append(" OVER ")
            append(dialect.quoteIdentifier(namedWindow.name))
        }
    }

    private fun validateWindowCapabilities(expression: WindowExpression<*>) {
        val capabilities = buildList {
            when (expression.function.function) {
                SqlFunction.Ntile -> add(SqlWindowCapability.Ntile)
                SqlFunction.NthValue -> add(SqlWindowCapability.NthValue)
                SqlFunction.PercentRank -> add(SqlWindowCapability.PercentRank)
                SqlFunction.CumeDist -> add(SqlWindowCapability.CumeDist)
                else -> Unit
            }
            addAll(windowSpecificationCapabilities(expression.specification))
            if (expression.namedWindow != null) add(SqlWindowCapability.NamedWindow)
        }
        capabilities.firstOrNull { !dialect.supportsWindow(it) }?.let { capability ->
            throw UnsupportedCapability(capability.unsupportedCapability)
        }
        (expression.function as? ValueWindowFunctionExpression<*>)?.nullTreatment?.let { treatment ->
            if (!dialect.supportsWindowNullTreatment(expression.function.function, treatment)) {
                throw UnsupportedCapability(
                    "window-null-treatment:${expression.function.function}:${treatment.name}",
                )
            }
        }
    }

    private fun validateWindowSpecificationCapabilities(specification: WindowSpecification) {
        windowSpecificationCapabilities(specification).firstOrNull { !dialect.supportsWindow(it) }?.let {
            throw UnsupportedCapability(it.unsupportedCapability)
        }
    }

    private fun windowSpecificationCapabilities(specification: WindowSpecification): List<SqlWindowCapability> =
        buildList {
            if (specification.frame?.unit == WindowFrameUnit.Groups) add(SqlWindowCapability.GroupsFrame)
            if (specification.frame?.exclusion != null) add(SqlWindowCapability.FrameExclusion)
        }

    private fun renderWindowSpecification(
        specification: WindowSpecification,
        parentSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
    ): String = buildList {
        if (specification.partitionBy.isNotEmpty()) {
            add(
                "PARTITION BY " + specification.partitionBy.joinToString(", ") {
                    renderExpression(it, parentSources, availableCtes)
                },
            )
        }
        if (specification.orderBy.isNotEmpty()) {
            add(
                "ORDER BY " + specification.orderBy.joinToString(", ") { order ->
                    "${renderExpression(order.expression, parentSources, availableCtes)} " +
                        dialect.renderOrder(order.direction, order.nullOrdering)
                },
            )
        }
        specification.frame?.let(::renderWindowFrame)?.let(::add)
    }.joinToString(" ")

    private fun renderWindowFrame(frame: WindowFrame): String = buildString {
        append(frame.unit.sql)
        append(' ')
        when (val extent = frame.extent) {
            is WindowFrameStart -> append(extent.boundary.sql)
            is WindowFrameBetween -> append("BETWEEN ").append(extent.start.sql).append(" AND ").append(extent.end.sql)
        }
        frame.exclusion?.let { exclusion -> append(' ').append(exclusion.sql) }
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive rendering over the closed predicate AST.
    fun renderPredicate(
        predicate: SqlPredicate,
        parentSources: List<RelationSource> = emptyList(),
        availableCtes: List<CommonTableExpression> = emptyList(),
    ): String = when (predicate) {
        is ValueComparison<*> -> {
            val marker = addColumnParameter(predicate)
            "${renderColumn(predicate.column)} ${predicate.operator.sql} $marker"
        }

        is ExpressionValueComparison<*> -> {
            val renderedExpression = renderExpression(
                predicate.expression,
                parentSources,
                availableCtes,
            )
            val marker = addExpressionParameter(predicate)
            "$renderedExpression ${predicate.operator.sql} $marker"
        }

        is ExpressionComparison<*> ->
            "${renderExpression(predicate.left, parentSources, availableCtes)} ${predicate.operator.sql} " +
                renderExpression(predicate.right, parentSources, availableCtes)

        is NullSafeComparison<*> -> dialect.renderNullSafeComparison(
            predicate.distinct,
            renderExpression(predicate.left, parentSources, availableCtes),
            renderExpression(predicate.right, parentSources, availableCtes),
        )

        is NullSafeValueComparison<*> -> dialect.renderNullSafeComparison(
            predicate.distinct,
            renderExpression(predicate.expression, parentSources, availableCtes),
            addNullSafeValueParameter(predicate),
        )

        is LikeValueComparison<*> -> {
            val marker = addLikeParameter(predicate)
            "${renderExpression(predicate.expression, parentSources, availableCtes)} " +
                "${dialect.renderLikeOperator(predicate.operator)} $marker"
        }

        is LikeExpressionComparison<*> ->
            "${renderExpression(predicate.expression, parentSources, availableCtes)} " +
                "${dialect.renderLikeOperator(predicate.operator)} " +
                renderExpression(predicate.pattern, parentSources, availableCtes)

        is NullComparison -> "${renderColumn(predicate.column)} ${predicate.operator.sql}"

        is ExpressionNullComparison ->
            "${renderExpression(predicate.expression, parentSources, availableCtes)} ${predicate.operator.sql}"

        is LogicalPredicate ->
            "(${renderPredicate(predicate.left, parentSources, availableCtes)} ${predicate.operator.sql} " +
                renderPredicate(predicate.right, parentSources, availableCtes) + ")"

        is InSubqueryPredicate -> "${renderExpression(predicate.expression, parentSources, availableCtes)} " +
            "${if (predicate.negated) "NOT IN" else "IN"} (" +
            renderQuery(predicate.query, parentSources, nested = true, availableCtes = availableCtes) + ")"

        is ExistsPredicate -> "${if (predicate.negated) "NOT EXISTS" else "EXISTS"} " +
            "(${renderQuery(predicate.query, parentSources, nested = true, availableCtes = availableCtes)})"

        else -> error("Unsupported SQL predicate: $predicate")
    }

    fun renderConflictExpression(expression: SqlExpression<*>, proposedReference: (Column<*>) -> String): String =
        withProposedInsertReference(proposedReference) { renderExpression(expression) }

    fun renderConflictPredicate(predicate: SqlPredicate, proposedReference: (Column<*>) -> String): String =
        withProposedInsertReference(proposedReference) { renderPredicate(predicate) }

    private var proposedInsertReference: ((Column<*>) -> String)? = null

    private fun <T> withProposedInsertReference(renderer: (Column<*>) -> String, block: () -> T): T {
        val previous = proposedInsertReference
        proposedInsertReference = renderer
        return try {
            block()
        } finally {
            proposedInsertReference = previous
        }
    }

    fun addParameter(value: Int): String {
        parameters += SqlParameter(value, null)
        return dialect.parameterMarker(parameters.size)
    }

    fun addParameters(values: List<Int>): List<String> = values.map(::addParameter)

    private fun <T> addColumnParameter(comparison: ValueComparison<T>): String {
        parameters += SqlParameter(comparison.column.toStorageValue(comparison.value), comparison.column)
        return dialect.parameterMarker(parameters.size)
    }

    private fun <T> addExpressionParameter(comparison: ExpressionValueComparison<T>): String {
        parameters += SqlParameter(comparison.storageValue(), comparison.expression)
        return dialect.parameterMarker(parameters.size)
    }

    private fun addNullSafeValueParameter(comparison: NullSafeValueComparison<*>): String {
        parameters += SqlParameter(comparison.storageValue(), comparison.expression)
        return dialect.parameterMarker(parameters.size)
    }

    private fun addLikeParameter(comparison: LikeValueComparison<*>): String {
        parameters += SqlParameter(comparison.pattern, null)
        return dialect.parameterMarker(parameters.size)
    }

    private fun <T> addLiteralParameter(literal: SqlLiteral<T>): String {
        parameters += SqlParameter(literal.toStorageValue(literal.value), literal)
        return dialect.parameterMarker(parameters.size)
    }

    fun addColumnParameter(assignment: ColumnAssignment<*>): String {
        parameters += SqlParameter(assignment.storageValue(), assignment.column)
        return dialect.parameterMarker(parameters.size)
    }
}

private const val SET_OPERAND_ALIAS = "kagiroi_set_operand"
private const val SET_RESULT_ALIAS = "kagiroi_set_result"
