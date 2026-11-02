@file:Suppress("TooManyFunctions") // Explicit typed DDL entry points intentionally share this source file.

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.CheckConstraint
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnDefault
import dev.quwac.kagiroi.schema.ColumnIndexKey
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.ForeignKeyConstraint
import dev.quwac.kagiroi.schema.IndexKey
import dev.quwac.kagiroi.schema.InternalSchemaDdlApi
import dev.quwac.kagiroi.schema.PrimaryKeyConstraint
import dev.quwac.kagiroi.schema.SchemaIndex
import dev.quwac.kagiroi.schema.SchemaIndexBuilder
import dev.quwac.kagiroi.schema.Sequence
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.UniqueConstraint
import dev.quwac.kagiroi.schema.buildSchemaIndex
import dev.quwac.kagiroi.schema.checkConstraint
import dev.quwac.kagiroi.schema.columnDefault
import dev.quwac.kagiroi.schema.foreignKeyConstraint
import dev.quwac.kagiroi.schema.primaryKeyConstraint
import dev.quwac.kagiroi.schema.uniqueConstraint

/** An explicit schema operation. This model does not inspect or diff an existing database schema. */
sealed interface DdlStatement

class CreateTable internal constructor(
    val table: Table,
    val columns: NonEmptyList<Column<*>>,
    val ifNotExists: Boolean,
    /** Snapshot of [Table.comment] taken when this statement is built. */
    val tableComment: String?,
    /** Snapshot of table-level primary-key metadata taken when this statement is built. */
    val primaryKey: PrimaryKeyConstraint?,
    /** Snapshot of table-level UNIQUE metadata taken when this statement is built. */
    val uniqueConstraints: List<UniqueConstraint>,
    /** Snapshot of foreign-key metadata taken when this statement is built. */
    val foreignKeys: List<ForeignKeyConstraint>,
    /** Snapshot of CHECK metadata taken when this statement is built. */
    val checks: List<CheckConstraint>,
) : DdlStatement

class DropTable internal constructor(val table: Table, val ifExists: Boolean) : DdlStatement

/** Creates an independent sequence object. */
class CreateSequence internal constructor(val sequence: Sequence, val ifNotExists: Boolean) : DdlStatement

/** Drops an independent sequence object. */
class DropSequence internal constructor(val sequence: Sequence, val ifExists: Boolean) : DdlStatement

sealed interface AlterTableOperation {
    class AddColumn internal constructor(val column: Column<*>) : AlterTableOperation

    class DropColumn internal constructor(val column: Column<*>) : AlterTableOperation

    class RenameColumn internal constructor(val column: Column<*>, val newName: String) : AlterTableOperation

    class RenameTable internal constructor(val newName: String) : AlterTableOperation

    class AddPrimaryKey internal constructor(val constraint: PrimaryKeyConstraint) : AlterTableOperation

    class DropPrimaryKey internal constructor(val name: String?) : AlterTableOperation

    class AddUnique internal constructor(val constraint: UniqueConstraint) : AlterTableOperation

    class DropUnique internal constructor(val name: String) : AlterTableOperation

    class AddForeignKey internal constructor(val constraint: ForeignKeyConstraint) : AlterTableOperation

    class DropForeignKey internal constructor(val name: String) : AlterTableOperation

    class AddCheck internal constructor(val constraint: CheckConstraint) : AlterTableOperation

    class DropCheck internal constructor(val name: String) : AlterTableOperation

    class AlterColumnType internal constructor(
        val column: Column<*>,
        /** Kotlin mapping for the new value. The existing Column object is never changed. */
        val type: ColumnType<*>,
        /** Database storage type rendered by the selected dialect. */
        val storageType: ColumnType<*>,
    ) : AlterTableOperation

    class SetDefault internal constructor(val column: Column<*>, val default: ColumnDefault<*>) : AlterTableOperation

    class DropDefault internal constructor(val column: Column<*>) : AlterTableOperation

    class SetNotNull internal constructor(val column: Column<*>) : AlterTableOperation

    class DropNotNull internal constructor(val column: Column<*>) : AlterTableOperation
}

/** Ordered, immutable changes to one table.  One operation may render to one or more SQL statements. */
class AlterTable internal constructor(val table: Table, val operations: NonEmptyList<AlterTableOperation>) :
    DdlStatement {
    /** Compatibility view for code that renders a single-operation ALTER TABLE. */
    val operation: AlterTableOperation get() = operations.first

    /** Returns a one-operation view for dialect renderers while preserving the snapshotted operation. */
    fun singleOperation(operation: AlterTableOperation): AlterTable = AlterTable(table, nonEmptyListOf(operation))
}

class CreateIndex internal constructor(
    /** Immutable index schema metadata snapshotted when this DDL statement is built. */
    val index: SchemaIndex,
) : DdlStatement {
    val name: String get() = index.name
    val table: Table get() = index.table
    val keys: List<IndexKey> get() = index.keys
    val unique: Boolean get() = index.unique
    val predicate get() = index.predicate
    val includedColumns: List<Column<*>> get() = index.includedColumns

    /** Optional logical schema metadata for this named index. */
    val comment: String? get() = index.comment

    /**
     * Direct column keys retained for existing simple-index callers.
     *
     * Expression keys are intentionally absent from this compatibility view; use [keys] for the
     * complete index definition.
     */
    val columns: List<Column<*>> get() = keys.mapNotNull { (it as? ColumnIndexKey)?.column }
}

class DropIndex internal constructor(val name: String, val table: Table) : DdlStatement

/** Builds a CREATE TABLE statement from current physical columns of [table]. */
fun createTable(table: Table, ifNotExists: Boolean = false): CreateTable {
    val columns = requireNotNull(
        table.columns.filterNot(Column<*>::isComputed).toNonEmptyListOrNull(),
    ) { "CREATE TABLE requires at least one physical column: $table" }
    table.validateConstraints()
    return CreateTable(
        table,
        columns,
        ifNotExists,
        table.comment,
        table.primaryKey,
        table.uniqueConstraints,
        table.foreignKeys,
        table.checks,
    )
}

fun dropTable(table: Table, ifExists: Boolean = false): DropTable = DropTable(table, ifExists)

fun createSequence(sequence: Sequence, ifNotExists: Boolean = false): CreateSequence =
    CreateSequence(sequence, ifNotExists)

fun dropSequence(sequence: Sequence, ifExists: Boolean = false): DropSequence = DropSequence(sequence, ifExists)

@DdlDsl
@Suppress("TooManyFunctions") // The ALTER DSL deliberately exposes each typed operation directly.
class AlterTableBuilder internal constructor(private val table: Table) {
    private val operations = mutableListOf<AlterTableOperation>()
    private val droppedColumns = mutableSetOf<Column<*>>()
    private val foreignKeys = mutableListOf<() -> Boolean>()
    private var endsWithRename = false

    fun addColumn(column: Column<*>) {
        requireCurrentColumn(column)
        require(!column.isComputed) {
            "Logical computed columns are not physical database columns and cannot be added by generic DDL: $column"
        }
        addOperation(AlterTableOperation.AddColumn(column))
    }

    fun dropColumn(column: Column<*>) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.DropColumn(column))
        droppedColumns += column
    }

    fun renameColumn(column: Column<*>, newName: String) {
        requireCurrentColumn(column)
        require(newName.isNotBlank()) { "New column name must not be blank" }
        require(newName != column.name) { "New column name must differ from the current name: $column" }
        require(table.columns.none { it !== column && it.name == newName }) {
            "Table $table already contains a column named $newName"
        }
        addOperation(AlterTableOperation.RenameColumn(column, newName))
        endsWithRename = true
    }

    fun renameTable(newName: String) {
        require(newName.isNotBlank()) { "New table name must not be blank" }
        require(newName != table.tableName) { "New table name must differ from the current name: $table" }
        addOperation(AlterTableOperation.RenameTable(newName))
        endsWithRename = true
    }

    fun addPrimaryKey(first: Column<*>, vararg remaining: Column<*>, name: String? = null) {
        addOperation(AlterTableOperation.AddPrimaryKey(primaryKeyConstraint(table, first, *remaining, name = name)))
    }

    fun dropPrimaryKey(name: String? = null) {
        require(name == null || name.isNotBlank()) { "Primary key constraint name must not be blank" }
        addOperation(AlterTableOperation.DropPrimaryKey(name))
    }

    fun addUnique(first: Column<*>, vararg remaining: Column<*>, name: String? = null, comment: String? = null) {
        addOperation(
            AlterTableOperation.AddUnique(uniqueConstraint(table, first, *remaining, name = name, comment = comment)),
        )
    }

    fun dropUnique(name: String) = dropNamedConstraint(name, "UNIQUE") { AlterTableOperation.DropUnique(it) }

    fun <T : Any> addForeignKey(source: Column<out T?>, name: String? = null): AlterForeignKeyBuilder<T> {
        requireCurrentColumn(source)
        var index: Int? = null
        return AlterForeignKeyBuilder(table, source, name) { constraint ->
            val replacement = AlterTableOperation.AddForeignKey(constraint)
            val existing = index
            if (existing == null) {
                index = operations.size
                addOperation(replacement)
            } else {
                operations[existing] = replacement
            }
        }
            .also { foreignKeys += { it.isComplete } }
    }

    fun <A : Any, B : Any> addForeignKey(
        firstSource: Column<out A?>,
        secondSource: Column<out B?>,
        name: String? = null,
    ): AlterForeignKeyBuilder2<A, B> {
        requireCurrentColumn(firstSource)
        requireCurrentColumn(secondSource)
        var index: Int? = null
        return AlterForeignKeyBuilder2(table, firstSource, secondSource, name) { constraint ->
            val replacement = AlterTableOperation.AddForeignKey(constraint)
            val existing = index
            if (existing == null) {
                index = operations.size
                addOperation(replacement)
            } else {
                operations[existing] = replacement
            }
        }
            .also { foreignKeys += { it.isComplete } }
    }

    fun <A : Any, B : Any, C : Any> addForeignKey(
        firstSource: Column<out A?>,
        secondSource: Column<out B?>,
        thirdSource: Column<out C?>,
        name: String? = null,
    ): AlterForeignKeyBuilder3<A, B, C> {
        requireCurrentColumn(firstSource)
        requireCurrentColumn(secondSource)
        requireCurrentColumn(thirdSource)
        var index: Int? = null
        return AlterForeignKeyBuilder3(table, firstSource, secondSource, thirdSource, name) { constraint ->
            val replacement = AlterTableOperation.AddForeignKey(constraint)
            val existing = index
            if (existing == null) {
                index = operations.size
                addOperation(replacement)
            } else {
                operations[existing] = replacement
            }
        }
            .also { foreignKeys += { it.isComplete } }
    }

    fun dropForeignKey(name: String) = dropNamedConstraint(
        name,
        "foreign key",
    ) { AlterTableOperation.DropForeignKey(it) }

    fun addCheck(
        name: String? = null,
        expression: dev.quwac.kagiroi.schema.CheckConstraintScope.() -> dev.quwac.kagiroi.schema.BooleanExpression,
    ) {
        addOperation(AlterTableOperation.AddCheck(checkConstraint(table, name, expression)))
    }

    fun dropCheck(name: String) = dropNamedConstraint(name, "CHECK") { AlterTableOperation.DropCheck(it) }

    fun alterColumnType(column: Column<*>, type: ColumnType<*>) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.AlterColumnType(column, type, type))
    }

    fun alterColumnType(column: Column<*>, target: SqlCastTarget<*>) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.AlterColumnType(column, target.type, target.storageType))
    }

    fun <T> setDefault(column: Column<T>, value: T) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.SetDefault(column, columnDefault(column, value)))
    }

    fun <T> setDefault(column: Column<T>, expression: SqlExpression<T>) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.SetDefault(column, columnDefault(column, expression)))
    }

    fun dropDefault(column: Column<*>) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.DropDefault(column))
    }

    fun setNotNull(column: Column<*>) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.SetNotNull(column))
    }

    fun dropNotNull(column: Column<*>) {
        requireCurrentColumn(column)
        addOperation(AlterTableOperation.DropNotNull(column))
    }

    internal fun build(): AlterTable {
        require(foreignKeys.all { it() }) {
            "ALTER TABLE foreign key requires references(...)"
        }
        return AlterTable(
            table,
            requireNotNull(operations.toNonEmptyListOrNull()) { "ALTER TABLE requires at least one operation" },
        )
    }

    private fun requireCurrentColumn(column: Column<*>) {
        require(column.table === table && table.hasColumn(column)) {
            "ALTER TABLE column must be a current column of $table: $column"
        }
        require(column !in droppedColumns) {
            "ALTER TABLE column was already dropped earlier in this block: $column"
        }
    }

    private fun addOperation(value: AlterTableOperation) {
        require(!endsWithRename) {
            "ALTER TABLE renameColumn/renameTable must be the final operation in a block"
        }
        operations += value
    }

    private fun dropNamedConstraint(name: String, kind: String, operation: (String) -> AlterTableOperation) {
        require(name.isNotBlank()) { "$kind constraint name must not be blank" }
        addOperation(operation(name))
    }
}

/** Completes a typed ALTER TABLE foreign key without changing either table object. */
class AlterForeignKeyBuilder<T : Any> internal constructor(
    private val table: Table,
    private val source: Column<out T?>,
    private val name: String?,
    private val complete: (ForeignKeyConstraint) -> Unit,
) {
    private var onDelete: dev.quwac.kagiroi.schema.ForeignKeyAction? = null
    private var onUpdate: dev.quwac.kagiroi.schema.ForeignKeyAction? = null
    private var target: Column<out T?>? = null

    internal val isComplete: Boolean get() = target != null

    fun references(target: Column<out T?>): AlterForeignKeyBuilder<T> = apply {
        require(this.target == null) { "ALTER TABLE foreign key already has references(...)" }
        this.target = target
        publish()
    }

    fun onDelete(action: dev.quwac.kagiroi.schema.ForeignKeyAction): AlterForeignKeyBuilder<T> = apply {
        require(onDelete == null) { "ALTER TABLE foreign key already has ON DELETE" }
        onDelete = action
        if (target != null) publish()
    }

    fun onUpdate(action: dev.quwac.kagiroi.schema.ForeignKeyAction): AlterForeignKeyBuilder<T> = apply {
        require(onUpdate == null) { "ALTER TABLE foreign key already has ON UPDATE" }
        onUpdate = action
        if (target != null) publish()
    }

    private fun publish() {
        complete(foreignKeyConstraint(table, name, listOf(source), listOf(requireNotNull(target)), onDelete, onUpdate))
    }
}

/** Completes a two-column typed ALTER TABLE foreign key without changing either table object. */
class AlterForeignKeyBuilder2<A : Any, B : Any> internal constructor(
    private val table: Table,
    private val firstSource: Column<out A?>,
    private val secondSource: Column<out B?>,
    private val name: String?,
    private val complete: (ForeignKeyConstraint) -> Unit,
) {
    private var onDelete: dev.quwac.kagiroi.schema.ForeignKeyAction? = null
    private var onUpdate: dev.quwac.kagiroi.schema.ForeignKeyAction? = null
    private var firstTarget: Column<out A?>? = null
    private var secondTarget: Column<out B?>? = null

    internal val isComplete: Boolean get() = firstTarget != null && secondTarget != null

    fun references(firstTarget: Column<out A?>, secondTarget: Column<out B?>): AlterForeignKeyBuilder2<A, B> = apply {
        require(!isComplete) { "ALTER TABLE foreign key already has references(...)" }
        this.firstTarget = firstTarget
        this.secondTarget = secondTarget
        publish()
    }

    fun onDelete(action: dev.quwac.kagiroi.schema.ForeignKeyAction): AlterForeignKeyBuilder2<A, B> = apply {
        require(onDelete == null) { "ALTER TABLE foreign key already has ON DELETE" }
        onDelete = action
        if (isComplete) publish()
    }

    fun onUpdate(action: dev.quwac.kagiroi.schema.ForeignKeyAction): AlterForeignKeyBuilder2<A, B> = apply {
        require(onUpdate == null) { "ALTER TABLE foreign key already has ON UPDATE" }
        onUpdate = action
        if (isComplete) publish()
    }

    private fun publish() {
        complete(
            foreignKeyConstraint(
                table,
                name,
                listOf(firstSource, secondSource),
                listOf(requireNotNull(firstTarget), requireNotNull(secondTarget)),
                onDelete,
                onUpdate,
            ),
        )
    }
}

/** Completes a three-column typed ALTER TABLE foreign key without changing either table object. */
class AlterForeignKeyBuilder3<A : Any, B : Any, C : Any> internal constructor(
    private val table: Table,
    private val firstSource: Column<out A?>,
    private val secondSource: Column<out B?>,
    private val thirdSource: Column<out C?>,
    private val name: String?,
    private val complete: (ForeignKeyConstraint) -> Unit,
) {
    private var onDelete: dev.quwac.kagiroi.schema.ForeignKeyAction? = null
    private var onUpdate: dev.quwac.kagiroi.schema.ForeignKeyAction? = null
    private var firstTarget: Column<out A?>? = null
    private var secondTarget: Column<out B?>? = null
    private var thirdTarget: Column<out C?>? = null

    internal val isComplete: Boolean get() = firstTarget != null && secondTarget != null && thirdTarget != null

    fun references(
        firstTarget: Column<out A?>,
        secondTarget: Column<out B?>,
        thirdTarget: Column<out C?>,
    ): AlterForeignKeyBuilder3<A, B, C> = apply {
        require(!isComplete) { "ALTER TABLE foreign key already has references(...)" }
        this.firstTarget = firstTarget
        this.secondTarget = secondTarget
        this.thirdTarget = thirdTarget
        publish()
    }

    fun onDelete(action: dev.quwac.kagiroi.schema.ForeignKeyAction): AlterForeignKeyBuilder3<A, B, C> = apply {
        require(onDelete == null) { "ALTER TABLE foreign key already has ON DELETE" }
        onDelete = action
        if (isComplete) publish()
    }

    fun onUpdate(action: dev.quwac.kagiroi.schema.ForeignKeyAction): AlterForeignKeyBuilder3<A, B, C> = apply {
        require(onUpdate == null) { "ALTER TABLE foreign key already has ON UPDATE" }
        onUpdate = action
        if (isComplete) publish()
    }

    private fun publish() {
        complete(
            foreignKeyConstraint(
                table,
                name,
                listOf(firstSource, secondSource, thirdSource),
                listOf(requireNotNull(firstTarget), requireNotNull(secondTarget), requireNotNull(thirdTarget)),
                onDelete,
                onUpdate,
            ),
        )
    }
}

fun alterTable(table: Table, configure: AlterTableBuilder.() -> Unit): AlterTable =
    AlterTableBuilder(table).apply(configure).build()

/** Executes explicit DDL statements and sequence reads through [SqlExecution.execute]. */
@DdlDsl
@Suppress("TooManyFunctions") // Keeps the complete typed DDL surface on the execution receiver.
class DdlExecutionScope internal constructor(private val execution: SqlExecution) {
    private var executedOperation = false

    suspend fun createTable(table: Table, ifNotExists: Boolean = false) {
        executeDdl(dev.quwac.kagiroi.sql.createTable(table, ifNotExists))
    }

    suspend fun dropTable(table: Table, ifExists: Boolean = false) {
        executeDdl(dev.quwac.kagiroi.sql.dropTable(table, ifExists))
    }

    suspend fun createSequence(sequence: Sequence, ifNotExists: Boolean = false) {
        executeDdl(dev.quwac.kagiroi.sql.createSequence(sequence, ifNotExists))
    }

    suspend fun dropSequence(sequence: Sequence, ifExists: Boolean = false) {
        executeDdl(dev.quwac.kagiroi.sql.dropSequence(sequence, ifExists))
    }

    suspend fun alterTable(table: Table, configure: AlterTableBuilder.() -> Unit) {
        executeDdl(dev.quwac.kagiroi.sql.alterTable(table, configure))
    }

    suspend fun createIndex(
        name: String,
        table: Table,
        unique: Boolean = false,
        comment: String? = null,
        configure: CreateIndexBuilder.() -> Unit,
    ) {
        executeDdl(dev.quwac.kagiroi.sql.createIndex(name, table, unique, comment, configure))
    }

    suspend fun createIndex(
        name: String,
        unique: Boolean = false,
        comment: String? = null,
        configure: CreateIndexBuilder.() -> Unit,
    ) {
        executeDdl(dev.quwac.kagiroi.sql.createIndex(name, unique, comment, configure))
    }

    suspend fun createIndex(
        name: String,
        table: Table,
        columns: List<Column<*>>,
        unique: Boolean = false,
        comment: String? = null,
    ) {
        executeDdl(dev.quwac.kagiroi.sql.createIndex(name, table, columns, unique, comment))
    }

    suspend fun createIndex(index: SchemaIndex) {
        executeDdl(dev.quwac.kagiroi.sql.createIndex(index))
    }

    /** Executes every index declared by [table] in that table's declaration order. */
    suspend fun createIndexes(table: Table) {
        dev.quwac.kagiroi.sql.createIndexes(table).forEach { index -> executeDdl(index) }
    }

    suspend fun dropIndex(name: String, table: Table) {
        executeDdl(dev.quwac.kagiroi.sql.dropIndex(name, table))
    }

    suspend fun nextSequenceValue(sequence: Sequence): Long {
        executedOperation = true
        return execution.nextSequenceValue(sequence)
    }

    internal fun requireExecution() {
        require(executedOperation) {
            "DDL execution requires at least one DDL statement or sequence read"
        }
    }

    private suspend fun executeDdl(statement: DdlStatement) {
        execution.execute(statement)
        executedOperation = true
    }
}

/** Executes DDL statements and sequence reads in declaration order without adding transaction semantics. */
suspend fun <T> SqlExecution.execute(configure: suspend DdlExecutionScope.() -> T): T {
    val scope = DdlExecutionScope(this)
    val result = scope.configure()
    scope.requireExecution()
    return result
}

typealias CreateIndexBuilder = SchemaIndexBuilder

@OptIn(InternalSchemaDdlApi::class)
fun createIndex(
    name: String,
    table: Table,
    unique: Boolean = false,
    comment: String? = null,
    configure: CreateIndexBuilder.() -> Unit,
): CreateIndex = CreateIndex(buildSchemaIndex(table, name, unique, comment, configure))

@OptIn(InternalSchemaDdlApi::class)
fun createIndex(
    name: String,
    unique: Boolean = false,
    comment: String? = null,
    configure: CreateIndexBuilder.() -> Unit,
): CreateIndex = CreateIndex(buildSchemaIndex(null, name, unique, comment, configure))

fun createIndex(
    name: String,
    table: Table,
    columns: List<Column<*>>,
    unique: Boolean = false,
    comment: String? = null,
): CreateIndex {
    require(columns.isNotEmpty()) { "CREATE INDEX requires at least one column" }
    return createIndex(name, table, unique, comment) {
        on(columns.first(), *columns.drop(1).toTypedArray())
    }
}

/** Turns table-declared [index] metadata into an explicit CREATE INDEX operation. */
fun createIndex(index: SchemaIndex): CreateIndex = CreateIndex(index)

/** Turns every index declared by [table] into explicit CREATE INDEX operations. */
fun createIndexes(table: Table): List<CreateIndex> {
    val indexes = table.indexes
    require(indexes.isNotEmpty()) { "Table does not declare any indexes: $table" }
    return indexes.map(::CreateIndex)
}

/** DROP INDEX retains its table because MySQL requires it in the statement syntax. */
fun dropIndex(name: String, table: Table): DropIndex {
    require(name.isNotBlank()) { "Index name must not be blank" }
    return DropIndex(name, table)
}

@DslMarker
annotation class DdlDsl
