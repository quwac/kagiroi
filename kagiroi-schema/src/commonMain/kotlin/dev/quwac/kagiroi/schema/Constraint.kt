package dev.quwac.kagiroi.schema

/** A table-level primary-key definition in declaration order. */
@ConsistentCopyVisibility
data class PrimaryKeyConstraint internal constructor(val name: String?, val columns: List<Column<*>>)

/** A table-level UNIQUE definition in declaration order. */
@ConsistentCopyVisibility
data class UniqueConstraint internal constructor(
    val name: String?,
    val columns: List<Column<*>>,
    /** Optional logical schema metadata for this named constraint. */
    val comment: String?,
)

/** Referential actions intentionally limited to the portable supported subset. */
enum class ForeignKeyAction(val sql: String) {
    Cascade("CASCADE"),
    Restrict("RESTRICT"),
    NoAction("NO ACTION"),
    SetNull("SET NULL"),
    SetDefault("SET DEFAULT"),
}

/** A table-level foreign key, including its referenced columns and declared actions. */
@ConsistentCopyVisibility
data class ForeignKeyConstraint internal constructor(
    val name: String?,
    val sourceColumns: List<Column<*>>,
    val targetColumns: List<Column<*>>,
    val onDelete: ForeignKeyAction?,
    val onUpdate: ForeignKeyAction?,
) {
    val targetTable: Table get() = targetColumns.first().table
}

/** A named or database-named typed CHECK expression. */
@ConsistentCopyVisibility
data class CheckConstraint internal constructor(val name: String?, val expression: BooleanExpression)

/**
 * Builds standalone primary-key metadata for a typed DDL operation without changing [table].
 */
fun primaryKeyConstraint(
    table: Table,
    first: Column<*>,
    vararg remaining: Column<*>,
    name: String? = null,
): PrimaryKeyConstraint {
    val columns = listOf(first) + remaining
    require(name == null || name.isNotBlank()) { "Primary key constraint name must not be blank: $table" }
    require(columns.all { it.table === table && table.hasColumn(it) }) {
        "Primary key columns must be current columns of $table"
    }
    require(columns.all { !it.isNullable && !it.isComputed && !it.isGenerated }) {
        "Primary key columns must be non-null physical columns of $table"
    }
    require(columns.distinctBy { it }.size == columns.size) { "Primary key columns must not repeat: $table" }
    return PrimaryKeyConstraint(name, columns)
}

/** Builds standalone UNIQUE metadata for a typed DDL operation without changing [table]. */
fun uniqueConstraint(
    table: Table,
    first: Column<*>,
    vararg remaining: Column<*>,
    name: String? = null,
    comment: String? = null,
): UniqueConstraint {
    val columns = listOf(first) + remaining
    require(name == null || name.isNotBlank()) { "UNIQUE constraint name must not be blank: $table" }
    require(comment == null || comment.isNotBlank()) { "UNIQUE constraint comment must not be blank: $table" }
    require(comment == null || name != null) { "UNIQUE constraint comment requires a constraint name: $table" }
    require(columns.all { it.table === table && table.hasColumn(it) && !it.isComputed }) {
        "UNIQUE columns must be current non-computed columns of $table"
    }
    require(columns.distinctBy { it }.size == columns.size) { "UNIQUE constraint columns must not repeat: $table" }
    return UniqueConstraint(name, columns, comment)
}

/** Builds standalone CHECK metadata for a typed DDL operation without changing [table]. */
fun checkConstraint(
    table: Table,
    name: String? = null,
    expression: CheckConstraintScope.() -> BooleanExpression,
): CheckConstraint {
    require(name == null || name.isNotBlank()) { "CHECK constraint name must not be blank: $table" }
    val value = CheckConstraintScope().expression()
    require(!value.containsWindowExpression()) { "CHECK constraint cannot contain a window expression" }
    require(!value.containsAggregateForSchemaConstraint()) { "CHECK constraint cannot contain an aggregate expression" }
    require(
        value.referencedColumns.isNotEmpty() && value.referencedColumns.all {
            it.table === table && table.hasColumn(
                it,
            )
        },
    ) {
        "CHECK constraint must reference current columns of $table"
    }
    return CheckConstraint(name, value)
}

/** Builds standalone foreign-key metadata for a typed DDL operation without changing [table]. */
fun foreignKeyConstraint(
    table: Table,
    name: String? = null,
    sourceColumns: List<Column<*>>,
    targetColumns: List<Column<*>>,
    onDelete: ForeignKeyAction? = null,
    onUpdate: ForeignKeyAction? = null,
): ForeignKeyConstraint {
    require(name == null || name.isNotBlank()) { "Foreign key constraint name must not be blank: $table" }
    val constraint = table.buildForeignKey(name, sourceColumns, targetColumns, onDelete, onUpdate)
    if (onDelete == ForeignKeyAction.SetNull || onUpdate == ForeignKeyAction.SetNull) {
        require(sourceColumns.all(Column<*>::isNullable)) {
            "ON DELETE/UPDATE SET NULL requires every foreign-key source column to be nullable"
        }
    }
    if (onDelete == ForeignKeyAction.SetDefault || onUpdate == ForeignKeyAction.SetDefault) {
        require(sourceColumns.all(Column<*>::hasDefault)) {
            "ON DELETE/UPDATE SET DEFAULT requires every foreign-key source column to declare DEFAULT"
        }
    }
    return constraint
}

/** Fluent one-column foreign-key definition that preserves the logical Kotlin type. */
class ForeignKeyBuilder1<T : Any> internal constructor(
    override val table: Table,
    private val name: String?,
    internal var source: Column<out T?>,
) : ForeignKeyBuilder() {
    internal override var onDeleteAction: ForeignKeyAction? = null
    internal override var onUpdateAction: ForeignKeyAction? = null
    internal override var constraint: ForeignKeyConstraint? = null

    fun references(target: Column<out T?>): ForeignKeyBuilder1<T> {
        table.requireSchemaMutable()
        constraint = table.buildForeignKey(name, listOf(source), listOf(target), onDeleteAction, onUpdateAction)
        return this
    }

    fun onDelete(action: ForeignKeyAction): ForeignKeyBuilder1<T> = apply { setOnDelete(action) }

    fun onUpdate(action: ForeignKeyAction): ForeignKeyBuilder1<T> = apply { setOnUpdate(action) }

    @Suppress("UNCHECKED_CAST") // The replacement is created from the same typed foreign-key column.
    internal override fun replaceSource(previous: Column<*>, replacement: Column<*>) {
        if (source !== previous) return
        source = replacement as Column<out T?>
        constraint = constraint?.copy(sourceColumns = listOf(replacement))
    }
}

/** Fluent two-column foreign-key definition with type compatibility at each position. */
class ForeignKeyBuilder2<A : Any, B : Any> internal constructor(
    override val table: Table,
    private val name: String?,
    internal val firstSource: Column<out A?>,
    internal val secondSource: Column<out B?>,
) : ForeignKeyBuilder() {
    internal override var onDeleteAction: ForeignKeyAction? = null
    internal override var onUpdateAction: ForeignKeyAction? = null
    internal override var constraint: ForeignKeyConstraint? = null

    fun references(firstTarget: Column<out A?>, secondTarget: Column<out B?>): ForeignKeyBuilder2<A, B> {
        table.requireSchemaMutable()
        constraint = table.buildForeignKey(
            name,
            listOf(firstSource, secondSource),
            listOf(firstTarget, secondTarget),
            onDeleteAction,
            onUpdateAction,
        )
        return this
    }

    fun onDelete(action: ForeignKeyAction): ForeignKeyBuilder2<A, B> = apply { setOnDelete(action) }

    fun onUpdate(action: ForeignKeyAction): ForeignKeyBuilder2<A, B> = apply { setOnUpdate(action) }
}

/** Fluent three-column foreign-key definition with type compatibility at each position. */
class ForeignKeyBuilder3<A : Any, B : Any, C : Any> internal constructor(
    override val table: Table,
    private val name: String?,
    internal val firstSource: Column<out A?>,
    internal val secondSource: Column<out B?>,
    internal val thirdSource: Column<out C?>,
) : ForeignKeyBuilder() {
    internal override var onDeleteAction: ForeignKeyAction? = null
    internal override var onUpdateAction: ForeignKeyAction? = null
    internal override var constraint: ForeignKeyConstraint? = null

    fun references(
        firstTarget: Column<out A?>,
        secondTarget: Column<out B?>,
        thirdTarget: Column<out C?>,
    ): ForeignKeyBuilder3<A, B, C> {
        table.requireSchemaMutable()
        constraint = table.buildForeignKey(
            name,
            listOf(firstSource, secondSource, thirdSource),
            listOf(firstTarget, secondTarget, thirdTarget),
            onDeleteAction,
            onUpdateAction,
        )
        return this
    }

    fun onDelete(action: ForeignKeyAction): ForeignKeyBuilder3<A, B, C> = apply { setOnDelete(action) }

    fun onUpdate(action: ForeignKeyAction): ForeignKeyBuilder3<A, B, C> = apply { setOnUpdate(action) }
}

/** Shared lifecycle for a foreign-key DSL that is registered before `references` completes it. */
abstract class ForeignKeyBuilder internal constructor() {
    internal abstract val table: Table
    internal abstract var onDeleteAction: ForeignKeyAction?
    internal abstract var onUpdateAction: ForeignKeyAction?
    internal abstract var constraint: ForeignKeyConstraint?

    internal fun setOnDelete(action: ForeignKeyAction) {
        table.requireSchemaMutable()
        require(onDeleteAction == null) { "ON DELETE action has already been specified" }
        validateSetNull(action)
        onDeleteAction = action
        constraint = constraint?.copy(onDelete = action)
    }

    internal fun setOnUpdate(action: ForeignKeyAction) {
        table.requireSchemaMutable()
        require(onUpdateAction == null) { "ON UPDATE action has already been specified" }
        validateSetNull(action)
        onUpdateAction = action
        constraint = constraint?.copy(onUpdate = action)
    }

    internal fun requireConstraint(): ForeignKeyConstraint = requireNotNull(constraint) {
        "Foreign key requires references(...)"
    }

    internal open fun replaceSource(previous: Column<*>, replacement: Column<*>) = Unit

    private fun validateSetNull(action: ForeignKeyAction) {
        if (action != ForeignKeyAction.SetNull) return
        val sourceColumns = when (this) {
            is ForeignKeyBuilder1<*> -> listOf(source)
            is ForeignKeyBuilder2<*, *> -> listOf(firstSource, secondSource)
            is ForeignKeyBuilder3<*, *, *> -> listOf(firstSource, secondSource, thirdSource)
            else -> error("Unknown foreign-key builder implementation")
        }
        require(sourceColumns.all(Column<*>::isNullable)) {
            "ON DELETE/UPDATE SET NULL requires every foreign-key source column to be nullable"
        }
    }
}
