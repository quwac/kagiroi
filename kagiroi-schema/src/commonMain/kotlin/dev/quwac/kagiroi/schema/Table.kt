package dev.quwac.kagiroi.schema

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A relational table definition made from explicitly named, typed columns.
 *
 * @property tableName physical table name.
 * @property schemaName optional containing schema name.
 * @property comment optional logical schema metadata.
 */
@Suppress("TooManyFunctions") // Mirrors the explicit scalar types available at the HTTP input boundary.
abstract class Table(
    val tableName: String,
    val schemaName: String? = null,
    /** Optional logical schema metadata for this table. */
    val comment: String? = null,
) : TableRelationSource {
    override val table: Table get() = this
    override val alias: String? get() = null

    private val registeredColumns = mutableListOf<Column<*>>()
    private var declaredPrimaryKey: PrimaryKeyConstraint? = null
    private val declaredUniqueConstraints = mutableListOf<UniqueConstraint>()
    private val declaredForeignKeyBuilders = mutableListOf<ForeignKeyBuilder>()
    private val declaredChecks = mutableListOf<CheckConstraint>()
    private val declaredRenameHistory = mutableListOf<String>()
    private val declaredSequences = mutableListOf<Sequence>()
    private val declaredIndexes = mutableListOf<SchemaIndex>()
    private var isSchemaSealed = false

    /** Columns declared by this table, in declaration order. */
    val columns: List<Column<*>> get() = registeredColumns.toList()

    /** Optional table-level primary key. Column-level primary keys remain column metadata. */
    val primaryKey: PrimaryKeyConstraint? get() = declaredPrimaryKey

    /** Table-level UNIQUE constraints in declaration order. Column-level unique flags remain column metadata. */
    val uniqueConstraints: List<UniqueConstraint> get() = declaredUniqueConstraints.toList()

    /** Completed foreign keys in declaration order. Access rejects an unfinished DSL declaration. */
    val foreignKeys: List<ForeignKeyConstraint>
        get() = declaredForeignKeyBuilders.map(ForeignKeyBuilder::requireConstraint)

    /** CHECK constraints in declaration order. */
    val checks: List<CheckConstraint> get() = declaredChecks.toList()

    /** Ordered historical physical table names for schema evolution consumers; never part of current DDL. */
    val renameHistory: List<String> get() = declaredRenameHistory.toList()

    /** Independent physical sequences declared by this table for application-schema management. */
    val sequences: List<Sequence> get() = declaredSequences.toList()

    /** Physical indexes declared by this table, in declaration order. */
    val indexes: List<SchemaIndex> get() = declaredIndexes.toList()

    init {
        require(tableName.isNotBlank()) { "Table name must not be blank" }
        require(schemaName == null || schemaName.isNotBlank()) { "Schema name must not be blank" }
        require(comment == null || comment.isNotBlank()) { "Table comment must not be blank" }
    }

    /** Adds one or more ordered historical physical table names for schema evolution consumers. */
    protected fun renamedFrom(first: String, vararg remaining: String) {
        requireSchemaMutable()
        val added = listOf(first) + remaining
        added.forEach { previous ->
            require(previous.isNotBlank()) { "Previous table name must not be blank: $tableName" }
            require(previous != tableName) { "Previous table name must differ from the current name: $tableName" }
        }
        require((declaredRenameHistory + added).distinct().size == declaredRenameHistory.size + added.size) {
            "Previous table names must not repeat: $tableName"
        }
        declaredRenameHistory += added
    }

    /** Declares an independent sequence managed with this table's application schema root. */
    protected fun sequence(name: String, schemaName: String? = this.schemaName): Sequence {
        requireSchemaMutable()
        val sequence = Sequence(name, schemaName)
        require(declaredSequences.none { it.name == sequence.name && it.schemaName == sequence.schemaName }) {
            "Table already declares sequence ${sequence.name}: $this"
        }
        declaredSequences += sequence
        return sequence
    }

    /**
     * Declares an application-schema index owned by this table.
     *
     * Use this inside an `init` block for composite, expression, partial, or covering indexes.
     */
    protected fun index(
        name: String,
        unique: Boolean = false,
        comment: String? = null,
        configure: SchemaIndexBuilder.() -> Unit,
    ): SchemaIndex {
        requireSchemaMutable()
        val schemaIndex = SchemaIndexBuilder(this).apply(configure).build(name, unique, comment)
        require(declaredIndexes.none { it.name == schemaIndex.name }) {
            "Table already defines an index named ${schemaIndex.name}: $this"
        }
        declaredIndexes += schemaIndex
        return schemaIndex
    }

    /** Declares a single-column application-schema index and retains the writable column type. */
    protected fun <T> WritableColumn<T>.index(
        name: String,
        unique: Boolean = false,
        comment: String? = null,
        configure: SchemaIndexBuilder.() -> Unit = {},
    ): WritableColumn<T> {
        val column = this
        this@Table.index(name, unique, comment) {
            on(column)
            configure()
        }
        return this
    }

    protected fun string(name: String): WritableColumn<String> = column(name, StringColumnType)

    protected fun byte(name: String): WritableColumn<Byte> = column(name, ByteColumnType)

    protected fun short(name: String): WritableColumn<Short> = column(name, ShortColumnType)

    protected fun int(name: String): WritableColumn<Int> = column(name, IntColumnType)

    protected fun long(name: String): WritableColumn<Long> = column(name, LongColumnType)

    protected fun float(name: String): WritableColumn<Float> = column(name, FloatColumnType)

    protected fun double(name: String): WritableColumn<Double> = column(name, DoubleColumnType)

    protected fun ubyte(name: String): WritableColumn<UByte> = column(name, UByteColumnType)

    protected fun ushort(name: String): WritableColumn<UShort> = column(name, UShortColumnType)

    protected fun uint(name: String): WritableColumn<UInt> = column(name, UIntColumnType)

    protected fun ulong(name: String): WritableColumn<ULong> = column(name, ULongColumnType)

    protected fun boolean(name: String): WritableColumn<Boolean> = column(name, BooleanColumnType)

    protected fun bytes(name: String): WritableColumn<ByteArray> = column(name, ByteArrayColumnType)

    protected fun instant(name: String): WritableColumn<Instant> = column(name, InstantColumnType)

    protected fun duration(name: String): WritableColumn<Duration> = column(name, DurationColumnType)

    protected fun uuid(name: String): WritableColumn<Uuid> = column(name, UuidColumnType)

    /** Fails clearly when a primary-key declaration omits every column. */
    protected fun primaryKey(name: String? = null): Nothing {
        require(name == null || name.isNotBlank()) { "Primary key constraint name must not be blank: $this" }
        throw IllegalArgumentException("Primary key requires at least one column: $this")
    }

    /** Declares a table-level primary key, preserving the supplied column order. */
    protected fun primaryKey(first: Column<*>, vararg remaining: Column<*>, name: String? = null) {
        requireSchemaMutable()
        val columns = listOf(first) + remaining
        require(name == null || name.isNotBlank()) { "Primary key constraint name must not be blank: $this" }
        require(declaredPrimaryKey == null) { "Table already defines a table-level primary key: $this" }
        require(registeredColumns.none(Column<*>::isPrimaryKey)) {
            "Table-level primary key conflicts with a column-level primary key: $this"
        }
        validatePrimaryKeyColumns(columns)
        declaredPrimaryKey = PrimaryKeyConstraint(name, columns.toList())
    }

    /** Fails clearly when a UNIQUE declaration omits every column. */
    protected fun unique(name: String? = null, comment: String? = null): Nothing {
        require(name == null || name.isNotBlank()) { "UNIQUE constraint name must not be blank: $this" }
        require(comment == null || comment.isNotBlank()) { "UNIQUE constraint comment must not be blank: $this" }
        require(comment == null || name != null) { "UNIQUE constraint comment requires a constraint name: $this" }
        throw IllegalArgumentException("UNIQUE constraint requires at least one column: $this")
    }

    /** Declares a table-level UNIQUE constraint, preserving the supplied column order. */
    protected fun unique(
        first: Column<*>,
        vararg remaining: Column<*>,
        name: String? = null,
        comment: String? = null,
    ) {
        requireSchemaMutable()
        val columns = listOf(first) + remaining
        require(name == null || name.isNotBlank()) { "UNIQUE constraint name must not be blank: $this" }
        require(comment == null || comment.isNotBlank()) { "UNIQUE constraint comment must not be blank: $this" }
        require(comment == null || name != null) { "UNIQUE constraint comment requires a constraint name: $this" }
        validateUniqueColumns(columns)
        require(declaredUniqueConstraints.none { it.columns == columns }) {
            "Table already defines this table-level UNIQUE constraint: $this"
        }
        declaredUniqueConstraints += UniqueConstraint(name, columns, comment)
    }

    /** Declares a typed one-column foreign key. Nullable sources retain their non-null logical type. */
    protected fun <T : Any> foreignKey(source: Column<out T?>, name: String? = null): ForeignKeyBuilder1<T> {
        requireSchemaMutable()
        return ForeignKeyBuilder1(this, validatedConstraintName(name, "Foreign key"), source)
            .also(declaredForeignKeyBuilders::add)
    }

    /**
     * Declares a writable foreign-key source column from [target]'s logical and storage mapping.
     * The returned column can be used directly in typed DML and predicates.
     */
    protected fun <T : Any> foreignKey(
        name: String,
        target: Column<out T?>,
        constraintName: String? = null,
    ): ForeignKeyColumn<T> {
        requireSchemaMutable()
        require(name.isNotBlank()) { "Column name must not be blank" }
        require(registeredColumns.none { it.name == name }) { "Duplicate column name in $this: $name" }
        require(target.table.hasColumn(target)) { "Foreign key target must be a current column: $target" }
        val targetDefinition = target.definition
        val source = ForeignKeyColumn<T>(
            ColumnDefinition(
                this,
                name,
                targetDefinition.type,
                targetDefinition.storageType,
                targetDefinition.encode,
                targetDefinition.decode,
                targetDefinition.hasValueMapping,
            ),
        ).also(registeredColumns::add)
        val builder = ForeignKeyBuilder1(this, validatedConstraintName(constraintName, "Foreign key"), source)
        builder.references(target)
        source.foreignKey = builder
        declaredForeignKeyBuilders += builder
        return source
    }

    /** Declares a typed composite foreign key with per-position type compatibility. */
    protected fun <A : Any, B : Any> foreignKey(
        first: Column<out A?>,
        second: Column<out B?>,
        name: String? = null,
    ): ForeignKeyBuilder2<A, B> {
        requireSchemaMutable()
        return ForeignKeyBuilder2(
            this,
            validatedConstraintName(name, "Foreign key"),
            first,
            second,
        ).also(declaredForeignKeyBuilders::add)
    }

    /** Declares a typed three-column foreign key with per-position type compatibility. */
    protected fun <A : Any, B : Any, C : Any> foreignKey(
        first: Column<out A?>,
        second: Column<out B?>,
        third: Column<out C?>,
        name: String? = null,
    ): ForeignKeyBuilder3<A, B, C> {
        requireSchemaMutable()
        return ForeignKeyBuilder3(
            this,
            validatedConstraintName(name, "Foreign key"),
            first,
            second,
            third,
        ).also(declaredForeignKeyBuilders::add)
    }

    /** Stores a typed Boolean expression as a table-level CHECK constraint. */
    protected fun checkConstraint(name: String? = null, expression: CheckConstraintScope.() -> BooleanExpression) {
        requireSchemaMutable()
        require(name == null || name.isNotBlank()) { "CHECK constraint name must not be blank: $this" }
        val value = CheckConstraintScope().expression()
        require(!value.containsWindowExpression()) { "CHECK constraint cannot contain a window expression" }
        require(
            !value.containsAggregateForSchemaConstraint(),
        ) { "CHECK constraint cannot contain an aggregate expression" }
        require(value.referencedColumns.isNotEmpty()) { "CHECK constraint must reference a column of $this" }
        value.referencedColumns.forEach { column ->
            requireCurrentColumn(column, "CHECK constraint")
        }
        declaredChecks += CheckConstraint(name, value)
    }

    /** Registers a typed column. Integration modules use this to add external Kotlin value types. */
    fun <T : Any> column(name: String, type: ColumnType<T>): WritableColumn<T> {
        requireSchemaMutable()
        require(name.isNotBlank()) { "Column name must not be blank" }
        require(registeredColumns.none { it.name == name }) { "Duplicate column name in $this: $name" }
        return WritableColumn<T>(ColumnDefinition(this, name, type)).also(registeredColumns::add)
    }

    internal fun replaceColumn(previous: Column<*>, replacement: Column<*>) {
        requireSchemaMutable()
        val index = registeredColumns.indexOfFirst { it === previous }
        require(index >= 0) { "Column definition is no longer current: $previous" }
        require(replacement.table === this && replacement.name == previous.name) {
            "Replacement column must preserve its table and name: $previous"
        }
        registeredColumns[index] = replacement
        declaredForeignKeyBuilders.forEach { it.replaceSource(previous, replacement) }
    }

    /** Returns whether [column] is a current definition registered by this table. */
    fun hasColumn(column: Column<*>): Boolean = registeredColumns.any { it === column }

    internal fun buildForeignKey(
        name: String?,
        sourceColumns: List<Column<*>>,
        targetColumns: List<Column<*>>,
        onDelete: ForeignKeyAction?,
        onUpdate: ForeignKeyAction?,
    ): ForeignKeyConstraint {
        require(sourceColumns.isNotEmpty()) { "Foreign key requires at least one source column: $this" }
        require(sourceColumns.size == targetColumns.size) {
            "Foreign key source and target column counts must match: $this"
        }
        sourceColumns.forEach { requireCurrentColumn(it, "Foreign key source") }
        require(sourceColumns.distinctBy { it }.size == sourceColumns.size) {
            "Foreign key source columns must not repeat: $this"
        }
        val targetTable = targetColumns.first().table
        targetColumns.forEach { column ->
            require(column.table === targetTable && targetTable.hasColumn(column)) {
                "Foreign key target columns must be current columns of one table: $column"
            }
        }
        require(targetColumns.distinctBy { it }.size == targetColumns.size) {
            "Foreign key target columns must not repeat: $this"
        }
        return ForeignKeyConstraint(name, sourceColumns.toList(), targetColumns.toList(), onDelete, onUpdate)
    }

    /** Validates the complete schema metadata before a consumer snapshots or renders it. */
    fun validateConstraints() {
        val columnPrimaryKeys = registeredColumns.filter(Column<*>::isPrimaryKey)
        require(columnPrimaryKeys.size <= 1) { "Table has multiple column-level primary keys: $this" }
        require(declaredPrimaryKey == null || columnPrimaryKeys.isEmpty()) {
            "Table-level primary key conflicts with a column-level primary key: $this"
        }
        declaredPrimaryKey?.let { validatePrimaryKeyColumns(it.columns) }
        uniqueConstraints.forEach { unique -> validateUniqueColumns(unique.columns) }
        foreignKeys.forEach { foreignKey ->
            buildForeignKey(
                foreignKey.name,
                foreignKey.sourceColumns,
                foreignKey.targetColumns,
                foreignKey.onDelete,
                foreignKey.onUpdate,
            )
            if (foreignKey.onDelete == ForeignKeyAction.SetDefault ||
                foreignKey.onUpdate == ForeignKeyAction.SetDefault
            ) {
                require(foreignKey.sourceColumns.all(Column<*>::hasDefault)) {
                    "ON DELETE/UPDATE SET DEFAULT requires every foreign-key source column to declare DEFAULT"
                }
            }
        }
        checks.forEach { check ->
            require(
                check.expression.referencedColumns.isNotEmpty(),
            ) { "CHECK constraint must reference a column of $this" }
            check.expression.referencedColumns.forEach { requireCurrentColumn(it, "CHECK constraint") }
        }
    }

    /** Prevents further schema-definition changes after registration in an application schema root. */
    internal fun sealSchema() {
        isSchemaSealed = true
    }

    /** Rejects schema mutation after this table has become part of an application schema root. */
    internal fun requireSchemaMutable() {
        require(!isSchemaSealed) {
            "Table schema is sealed by an application schema registry: $this"
        }
    }

    private fun validatePrimaryKeyColumns(columns: List<Column<*>>) {
        require(columns.isNotEmpty()) { "Primary key requires at least one column: $this" }
        columns.forEach { column ->
            requireCurrentColumn(column, "Primary key")
            require(!column.isNullable) { "Primary key column must not be nullable: $column" }
            require(!column.isComputed && !column.isGenerated) {
                "Computed or generated columns cannot be generic primary-key columns: $column"
            }
        }
        require(columns.distinctBy { it }.size == columns.size) { "Primary key columns must not repeat: $this" }
    }

    private fun validateUniqueColumns(columns: List<Column<*>>) {
        require(columns.isNotEmpty()) { "UNIQUE constraint requires at least one column: $this" }
        columns.forEach { column ->
            requireCurrentColumn(column, "UNIQUE constraint")
            require(!column.isComputed) { "Computed columns cannot be generic UNIQUE constraints: $column" }
        }
        require(columns.distinctBy { it }.size == columns.size) { "UNIQUE constraint columns must not repeat: $this" }
    }

    private fun requireCurrentColumn(column: Column<*>, operation: String) {
        require(
            column.table === this && hasColumn(column),
        ) { "$operation column must be a current column of $this: $column" }
    }

    private fun validatedConstraintName(name: String?, kind: String): String? {
        require(name == null || name.isNotBlank()) { "$kind constraint name must not be blank: $this" }
        return name
    }

    override fun toString(): String = tableName
}
