@file:Suppress("TooManyFunctions") // Index construction and structural snapshotting define one schema value.

package dev.quwac.kagiroi.schema

import kotlin.uuid.Uuid

/** Explicit ordering for one index key. A missing direction retains the database default.
 *
 * @property sql SQL ordering token.
 */
enum class IndexDirection(val sql: String) {
    /** Sort index entries in ascending order. */
    Ascending("ASC"),

    /** Sort index entries in descending order. */
    Descending("DESC"),
}

/** One immutable key in a schema index. Query [Order] values are converted to this model on input. */
sealed interface IndexKey {
    /** Requested ordering, or `null` to use the database default. */
    val direction: IndexDirection?

    /** Columns referenced by this key expression. */
    val referencedColumns: List<Column<*>>
}

/**
 * A query ordering that can be reused as an index key while retaining only index-relevant data.
 *
 * Query modules implement this interface for their `asc()` and `desc()` values. The schema model
 * does not depend on query-statement types.
 */
interface IndexOrderedExpression {
    /** Typed expression indexed by this ordering. */
    val indexExpression: SqlExpression<*>

    /** Requested index ordering. */
    val indexDirection: IndexDirection

    /** Whether this ordering requests `NULLS FIRST` or `NULLS LAST`. */
    val indexHasNullOrdering: Boolean
}

/** A direct physical column key.
 *
 * @property column indexed table column.
 * @property direction requested ordering, or `null` for the database default.
 */
class ColumnIndexKey internal constructor(val column: Column<*>, override val direction: IndexDirection? = null) :
    IndexKey {
    override val referencedColumns: List<Column<*>> = listOf(column)
}

/** A typed expression key. It is rendered only by dialects that support native expression indexes.
 *
 * @property expression expression used as the index key.
 * @property direction requested ordering, or `null` for the database default.
 */
class ExpressionIndexKey internal constructor(
    val expression: SqlExpression<*>,
    override val direction: IndexDirection? = null,
) : IndexKey {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns
}

/**
 * Immutable schema metadata for one named index.
 *
 * Indexes remain independent DDL operations: declaring this value never changes [createTable]
 * behavior. [CreateIndex] and [createIndexes] snapshot this metadata when they are built.
 *
 * @property name physical index name.
 * @property table table that owns the index.
 * @property unique whether the index enforces uniqueness.
 * @property predicate optional partial-index predicate.
 * @property comment optional logical metadata comment.
 * @property keys ordered index keys.
 * @property includedColumns columns stored in the index without participating in its key.
 */
class SchemaIndex internal constructor(
    val name: String,
    val table: Table,
    keys: List<IndexKey>,
    val unique: Boolean,
    val predicate: BooleanExpression?,
    includedColumns: List<Column<*>>,
    val comment: String?,
    renameHistory: List<String>,
) {
    val keys: List<IndexKey> = keys.toList()
    val includedColumns: List<Column<*>> = includedColumns.toList()

    /** Ordered historical physical index names for schema evolution consumers; never part of ordinary DDL. */
    val renameHistory: List<String> = renameHistory.toList()

    private val structure = IndexStructure.of(this)

    override fun equals(other: Any?): Boolean = other is SchemaIndex && structure == other.structure

    override fun hashCode(): Int = structure.hashCode()

    override fun toString(): String = "SchemaIndex($structure)"
}

/** Builds stable typed index metadata before it is turned into a [CreateIndex] statement. */
class SchemaIndexBuilder internal constructor(private val declaredTable: Table?) {
    private var declaredKeys: List<IndexKey>? = null
    private var declaredPredicate: BooleanExpression? = null
    private var declaredIncludedColumns: List<Column<*>> = emptyList()
    private var declaredRenameHistory: List<String> = emptyList()

    /** Adds ordered historical physical index names for schema evolution planning. */
    fun renamedFrom(first: String, vararg remaining: String) {
        val added = listOf(first) + remaining
        require(added.all(String::isNotBlank)) { "Previous index name must not be blank" }
        require((declaredRenameHistory + added).distinct().size == declaredRenameHistory.size + added.size) {
            "Previous index names must not repeat"
        }
        declaredRenameHistory += added
    }

    /** Declares one or more direct column keys while retaining the existing simple-index API. */
    fun on(first: Column<*>, vararg remaining: Column<*>) {
        onKeys((listOf(first) + remaining).map(::ColumnIndexKey))
    }

    /** Declares one or more typed expression keys with the default database ordering. */
    fun on(first: SqlExpression<*>, vararg remaining: SqlExpression<*>) {
        onKeys((listOf(first) + remaining).map(::toIndexKey))
    }

    /** Converts query ordering syntax into independent index-key ordering metadata. */
    fun on(first: IndexOrderedExpression, vararg remaining: IndexOrderedExpression) {
        onKeys((listOf(first) + remaining).map(::toIndexKey))
    }

    /** Adds non-key columns for dialects that support covering indexes. */
    fun include(first: Column<*>, vararg remaining: Column<*>) {
        require(declaredIncludedColumns.isEmpty()) { "Index included columns have already been specified" }
        declaredIncludedColumns = listOf(first) + remaining
    }

    /** Adds a typed partial or filtered-index predicate. */
    fun where(predicate: BooleanExpression) {
        require(declaredPredicate == null) { "Index predicate has already been specified" }
        declaredPredicate = predicate
    }

    internal fun build(name: String, unique: Boolean, comment: String?): SchemaIndex {
        require(name.isNotBlank()) { "Index name must not be blank" }
        require(comment == null || comment.isNotBlank()) { "Index comment must not be blank" }
        require(name !in declaredRenameHistory) { "Previous index name must differ from the current name: $name" }
        val keys = requireNotNull(declaredKeys) { "CREATE INDEX requires at least one key" }
        val table = declaredTable ?: requireNotNull(keys.first().referencedColumns.firstOrNull()) {
            "CREATE INDEX keys must reference a table column when no table is supplied"
        }.table

        keys.forEach { key -> validateIndexKey(key, table) }
        require(keys.map(::indexKeyStructure).distinct().size == keys.size) {
            "Index keys must not repeat: $name"
        }

        val directKeyColumns = keys.mapNotNull { (it as? ColumnIndexKey)?.column }.toSet()
        declaredIncludedColumns.forEach { column ->
            requireCurrentIndexColumn(column, table, "Index included column")
            require(column !in directKeyColumns) {
                "Index included column cannot also be a direct key column: $column"
            }
        }
        require(declaredIncludedColumns.distinct().size == declaredIncludedColumns.size) {
            "Index included columns must not repeat: $name"
        }

        declaredPredicate?.let { predicate ->
            require(predicate.referencedColumns.isNotEmpty()) {
                "Index predicate must reference a column of $table"
            }
            validateIndexPredicate(predicate, table)
        }

        return SchemaIndex(
            name = name,
            table = table,
            keys = keys,
            unique = unique,
            predicate = declaredPredicate,
            includedColumns = declaredIncludedColumns,
            comment = comment,
            renameHistory = declaredRenameHistory,
        )
    }

    private fun onKeys(keys: List<IndexKey>) {
        require(declaredKeys == null) { "Index keys have already been specified" }
        declaredKeys = keys
    }
}

/** Builds one immutable index definition for the explicit SQL DDL layer. */
@InternalSchemaDdlApi
fun buildSchemaIndex(
    table: Table?,
    name: String,
    unique: Boolean = false,
    comment: String? = null,
    configure: SchemaIndexBuilder.() -> Unit,
): SchemaIndex = SchemaIndexBuilder(table).apply(configure).build(name, unique, comment)

private fun toIndexKey(expression: SqlExpression<*>): IndexKey = when (expression) {
    is Column<*> -> ColumnIndexKey(expression)
    else -> ExpressionIndexKey(expression)
}

private fun toIndexKey(order: IndexOrderedExpression): IndexKey {
    require(!order.indexHasNullOrdering) { "Generic index keys do not support NULLS FIRST or NULLS LAST" }
    return when (val expression = order.indexExpression) {
        is Column<*> -> ColumnIndexKey(expression, order.indexDirection)
        else -> ExpressionIndexKey(expression, order.indexDirection)
    }
}

private fun validateIndexKey(key: IndexKey, table: Table) {
    when (key) {
        is ColumnIndexKey -> requireCurrentIndexColumn(key.column, table, "Index key")

        is ExpressionIndexKey -> {
            require(key.referencedColumns.isNotEmpty()) { "Index expression must reference a column of $table" }
            key.referencedColumns.forEach { requireCurrentIndexColumn(it, table, "Index expression") }
            validateIndexExpression(key.expression, table)
        }
    }
}

private fun requireCurrentIndexColumn(column: Column<*>, table: Table, subject: String) {
    require(column.table === table && table.hasColumn(column)) {
        "$subject must reference a current column of $table: $column"
    }
    require(!column.isComputed) { "$subject cannot reference logical computed column: $column" }
}

@Suppress("CyclomaticComplexMethod")
private fun validateIndexExpression(expression: SqlExpression<*>, table: Table) {
    when (expression) {
        is Column<*> -> requireCurrentIndexColumn(expression, table, "Index expression")

        is SqlLiteral<*> -> Unit

        is GeneratedColumnBinaryExpression<*> -> {
            validateIndexExpression(expression.left, table)
            validateIndexExpression(expression.right, table)
        }

        is NumericBinaryExpression<*> -> {
            validateIndexExpression(expression.left, table)
            validateIndexExpression(expression.right, table)
        }

        is SqlFunctionExpression<*> -> {
            require(expression !is AggregateFunctionExpression<*>) {
                "Aggregate function cannot be an index expression"
            }
            require(expression !is WindowFunctionExpression<*>) {
                "Window function cannot be an index expression"
            }
            require(expression.function.isIndexScalarFunction()) {
                "Function is not available in a generic index expression: ${expression.function}"
            }
            expression.arguments.forEach { argument -> validateIndexExpression(argument, table) }
        }

        is CastExpression<*> -> validateIndexExpression(expression.expression, table)

        is CaseExpression<*> -> {
            expression.branches.forEach { branch ->
                validateIndexPredicate(branch.condition, table)
                validateIndexExpression(branch.result, table)
            }
            validateIndexExpression(expression.elseResult, table)
        }

        is SqlExpressionAlias<*> -> throw IllegalArgumentException("Index expression cannot use an alias")

        else -> throw IllegalArgumentException("Expression is not available in a generic index: $expression")
    }
}

@Suppress("CyclomaticComplexMethod")
private fun validateIndexPredicate(predicate: BooleanExpression, table: Table) {
    predicate.referencedColumns.forEach { requireCurrentIndexColumn(it, table, "Index predicate") }
    when (predicate) {
        is ValueComparison<*>, is NullComparison -> Unit

        is ExpressionValueComparison<*> -> validateIndexExpression(predicate.expression, table)

        is ExpressionComparison<*> -> {
            validateIndexExpression(predicate.left, table)
            validateIndexExpression(predicate.right, table)
        }

        is LikeValueComparison<*> -> validateIndexExpression(predicate.expression, table)

        is LikeExpressionComparison<*> -> {
            validateIndexExpression(predicate.expression, table)
            validateIndexExpression(predicate.pattern, table)
        }

        is ExpressionNullComparison -> validateIndexExpression(predicate.expression, table)

        is LogicalPredicate -> {
            validateIndexPredicate(predicate.left, table)
            validateIndexPredicate(predicate.right, table)
        }

        else -> throw IllegalArgumentException("Predicate is not available in a generic index: $predicate")
    }
}

private fun SqlFunction.isIndexScalarFunction(): Boolean = when (this) {
    SqlFunction.Lower,
    SqlFunction.Upper,
    SqlFunction.Length,
    SqlFunction.Trim,
    SqlFunction.LeftTrim,
    SqlFunction.RightTrim,
    SqlFunction.Substring,
    SqlFunction.Replace,
    SqlFunction.Concat,
    SqlFunction.Coalesce,
    SqlFunction.NullIf,
    SqlFunction.Abs,
    SqlFunction.Round,
    SqlFunction.Floor,
    SqlFunction.Ceiling,
    SqlFunction.SquareRoot,
    SqlFunction.Power,
    -> true

    SqlFunction.CurrentDate,
    SqlFunction.CurrentTime,
    SqlFunction.CurrentTimestamp,
    SqlFunction.Year,
    SqlFunction.Month,
    SqlFunction.Day,
    SqlFunction.Hour,
    SqlFunction.Minute,
    SqlFunction.Second,
    SqlFunction.Count,
    SqlFunction.Sum,
    SqlFunction.Average,
    SqlFunction.Minimum,
    SqlFunction.Maximum,
    SqlFunction.Grouping,
    SqlFunction.RowNumber,
    SqlFunction.Rank,
    SqlFunction.DenseRank,
    SqlFunction.PercentRank,
    SqlFunction.CumeDist,
    SqlFunction.Lag,
    SqlFunction.Lead,
    SqlFunction.FirstValue,
    SqlFunction.LastValue,
    SqlFunction.Ntile,
    SqlFunction.NthValue,
    SqlFunction.PostgresCurrentSetting,
    is SqlFunction.Unsafe,
    -> false
}

private data class IndexStructure(
    val name: String,
    val table: TableReference,
    val unique: Boolean,
    val keys: List<IndexKeyStructure>,
    val predicate: IndexPredicateStructure?,
    val includedColumns: List<ColumnReference>,
    val comment: String?,
) {
    companion object {
        fun of(index: SchemaIndex): IndexStructure = IndexStructure(
            index.name,
            index.table.asReference(),
            index.unique,
            index.keys.map(::indexKeyStructure),
            index.predicate?.let(::indexPredicateStructure),
            index.includedColumns.map(Column<*>::asReference),
            index.comment,
        )
    }
}

private data class TableReference(val schemaName: String?, val tableName: String)

private data class ColumnReference(val table: TableReference, val name: String)

private fun Table.asReference(): TableReference = TableReference(schemaName, tableName)

private fun Column<*>.asReference(): ColumnReference = ColumnReference(table.asReference(), name)

private sealed interface IndexKeyStructure {
    data class Column(val column: ColumnReference, val direction: IndexDirection?) : IndexKeyStructure

    data class Expression(val expression: IndexExpressionStructure, val direction: IndexDirection?) : IndexKeyStructure
}

private fun indexKeyStructure(key: IndexKey): IndexKeyStructure = when (key) {
    is ColumnIndexKey -> IndexKeyStructure.Column(key.column.asReference(), key.direction)
    is ExpressionIndexKey -> IndexKeyStructure.Expression(indexExpressionStructure(key.expression), key.direction)
}

private sealed interface IndexExpressionStructure {
    data class Column(val column: ColumnReference) : IndexExpressionStructure

    data class Literal(val value: Any?, val type: String, val storageType: String) : IndexExpressionStructure

    data class Function(val function: SqlFunction, val arguments: List<IndexExpressionStructure>) :
        IndexExpressionStructure

    data class NumericBinary(
        val operator: dev.quwac.kagiroi.schema.NumericBinaryOperator,
        val left: IndexExpressionStructure,
        val right: IndexExpressionStructure,
    ) : IndexExpressionStructure

    data class GeneratedBinary(
        val operator: dev.quwac.kagiroi.schema.GeneratedColumnBinaryOperator,
        val left: IndexExpressionStructure,
        val right: IndexExpressionStructure,
    ) : IndexExpressionStructure

    data class Cast(val expression: IndexExpressionStructure, val targetType: String, val targetStorageType: String) :
        IndexExpressionStructure

    data class Case(
        val branches: List<Pair<IndexPredicateStructure, IndexExpressionStructure>>,
        val elseResult: IndexExpressionStructure,
    ) : IndexExpressionStructure
}

private fun indexExpressionStructure(expression: SqlExpression<*>): IndexExpressionStructure = when (expression) {
    is Column<*> -> IndexExpressionStructure.Column(expression.asReference())

    is SqlLiteral<*> -> IndexExpressionStructure.Literal(
        canonicalLiteral(expression.value),
        expression.type.name,
        expression.storageType.name,
    )

    is SqlFunctionExpression<*> -> IndexExpressionStructure.Function(
        expression.function,
        expression.arguments.map(::indexExpressionStructure),
    )

    is NumericBinaryExpression<*> -> IndexExpressionStructure.NumericBinary(
        expression.operator,
        indexExpressionStructure(expression.left),
        indexExpressionStructure(expression.right),
    )

    is GeneratedColumnBinaryExpression<*> -> IndexExpressionStructure.GeneratedBinary(
        expression.operator,
        indexExpressionStructure(expression.left),
        indexExpressionStructure(expression.right),
    )

    is CastExpression<*> -> IndexExpressionStructure.Cast(
        indexExpressionStructure(expression.expression),
        expression.target.type.name,
        expression.target.storageType.name,
    )

    is CaseExpression<*> -> IndexExpressionStructure.Case(
        expression.branches.map { branch ->
            indexPredicateStructure(branch.condition) to indexExpressionStructure(branch.result)
        },
        indexExpressionStructure(expression.elseResult),
    )

    else -> error("Index expression was not validated: $expression")
}

private sealed interface IndexPredicateStructure {
    data class ValueComparison(
        val column: ColumnReference,
        val operator: dev.quwac.kagiroi.schema.ComparisonOperator,
        val value: Any?,
    ) : IndexPredicateStructure

    data class ExpressionValueComparison(
        val expression: IndexExpressionStructure,
        val operator: dev.quwac.kagiroi.schema.ComparisonOperator,
        val value: Any?,
    ) : IndexPredicateStructure

    data class ExpressionComparison(
        val left: IndexExpressionStructure,
        val operator: dev.quwac.kagiroi.schema.ComparisonOperator,
        val right: IndexExpressionStructure,
    ) : IndexPredicateStructure

    data class LikeValue(
        val expression: IndexExpressionStructure,
        val operator: dev.quwac.kagiroi.schema.LikeOperator,
        val pattern: String,
    ) : IndexPredicateStructure

    data class LikeExpression(
        val expression: IndexExpressionStructure,
        val operator: dev.quwac.kagiroi.schema.LikeOperator,
        val pattern: IndexExpressionStructure,
    ) : IndexPredicateStructure

    data class Null(val expression: IndexExpressionStructure, val operator: dev.quwac.kagiroi.schema.NullOperator) :
        IndexPredicateStructure

    data class Logical(
        val left: IndexPredicateStructure,
        val operator: dev.quwac.kagiroi.schema.LogicalOperator,
        val right: IndexPredicateStructure,
    ) : IndexPredicateStructure
}

private fun indexPredicateStructure(predicate: BooleanExpression): IndexPredicateStructure = when (predicate) {
    is ValueComparison<*> -> IndexPredicateStructure.ValueComparison(
        predicate.column.asReference(),
        predicate.operator,
        canonicalLiteral(predicate.storageValue()),
    )

    is ExpressionValueComparison<*> -> IndexPredicateStructure.ExpressionValueComparison(
        indexExpressionStructure(predicate.expression),
        predicate.operator,
        canonicalLiteral(predicate.storageValue()),
    )

    is ExpressionComparison<*> -> IndexPredicateStructure.ExpressionComparison(
        indexExpressionStructure(predicate.left),
        predicate.operator,
        indexExpressionStructure(predicate.right),
    )

    is LikeValueComparison<*> -> IndexPredicateStructure.LikeValue(
        indexExpressionStructure(predicate.expression),
        predicate.operator,
        predicate.pattern,
    )

    is LikeExpressionComparison<*> -> IndexPredicateStructure.LikeExpression(
        indexExpressionStructure(predicate.expression),
        predicate.operator,
        indexExpressionStructure(predicate.pattern),
    )

    is NullComparison -> IndexPredicateStructure.Null(
        IndexExpressionStructure.Column(predicate.column.asReference()),
        predicate.operator,
    )

    is ExpressionNullComparison -> IndexPredicateStructure.Null(
        indexExpressionStructure(predicate.expression),
        predicate.operator,
    )

    is LogicalPredicate -> IndexPredicateStructure.Logical(
        indexPredicateStructure(predicate.left),
        predicate.operator,
        indexPredicateStructure(predicate.right),
    )

    else -> error("Index predicate was not validated: $predicate")
}

private fun canonicalLiteral(value: Any?): Any? = when (value) {
    is ByteArray -> value.toList()
    is Uuid -> value.toString()
    else -> value
}
