package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ByteColumnType
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.DoubleColumnType
import dev.quwac.kagiroi.schema.FloatColumnType
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.MappedColumnType
import dev.quwac.kagiroi.schema.ShortColumnType
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.UByteColumnType
import dev.quwac.kagiroi.schema.UIntColumnType
import dev.quwac.kagiroi.schema.ULongColumnType
import dev.quwac.kagiroi.schema.UShortColumnType
import dev.quwac.kagiroi.schema.UuidColumnType
import dev.quwac.kagiroi.sql.AlterTable
import dev.quwac.kagiroi.sql.AlterTableOperation
import dev.quwac.kagiroi.sql.CreateIndex
import dev.quwac.kagiroi.sql.CreateSequence
import dev.quwac.kagiroi.sql.CreateTable
import dev.quwac.kagiroi.sql.DdlStatement
import dev.quwac.kagiroi.sql.DropIndex
import dev.quwac.kagiroi.sql.DropSequence
import dev.quwac.kagiroi.sql.DropTable
import dev.quwac.kagiroi.sql.LateralJoinType
import dev.quwac.kagiroi.sql.NullOrdering
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SetOperation
import dev.quwac.kagiroi.sql.SortDirection
import dev.quwac.kagiroi.sql.SqlDdlDialect
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlInsertConflictDialect
import dev.quwac.kagiroi.sql.SqlProposedInsertReferenceStyle
import dev.quwac.kagiroi.sql.SqlReplaceDialect
import dev.quwac.kagiroi.sql.SqlReturningDialect
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.SqlWindowCapability
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.renderDefaultExpression
import dev.quwac.kagiroi.sql.renderGeneratedColumnExpression
import dev.quwac.kagiroi.sql.renderSchemaIndex
import dev.quwac.kagiroi.sql.renderTableConstraints

/** SQLite's runtime value storage classes, without choosing column affinity or driver binding conversions. */
enum class SqliteStorageClass {
    /** Value associated with integer. */
    INTEGER,

    /** Value associated with real. */
    REAL,

    /** Value associated with text. */
    TEXT,

    /** Value associated with blob. */
    BLOB,

    /** Value associated with null. */
    NULL,
}

/** SQLite syntax for the SQL query models supported by kagiroi-sql. */
@Suppress("TooManyFunctions") // SQLite owns each dialect rendering variation in one public contract.
object SqliteDialect : SqlDdlDialect, SqlReplaceDialect, SqlInsertConflictDialect, SqlReturningDialect {
    private val ddlRenderer = SqliteDdlRenderer(SqliteTypeRegistry())

    override fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    override fun parameterMarker(position: Int): String = "?"

    override fun supportsSetOperation(operation: SetOperation): Boolean = operation !in setOf(
        SetOperation.IntersectAll,
        SetOperation.ExceptAll,
    )

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = true

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String =
        throw UnsupportedCapability(type.unsupportedCapability)

    override fun renderCastTarget(target: SqlCastTarget<*>): String = SqliteTypeRegistry().castTargetName(target)

    override fun renderNullSafeComparison(distinct: Boolean, left: String, right: String): String =
        "$left IS ${if (distinct) "NOT " else ""}$right"

    override fun renderUpdateFrom(sources: List<String>): String =
        sources.joinToString(", ", prefix = " FROM ").takeIf { sources.isNotEmpty() }.orEmpty()

    override fun renderOrder(direction: SortDirection, nullOrdering: NullOrdering?): String =
        direction.sql + nullOrdering?.let { " ${it.sql}" }.orEmpty()

    @Suppress("MagicNumber") // Closed arity of the SQL substring grammar.
    override fun renderFunction(function: SqlFunction, arguments: List<String>): String = when (function) {
        SqlFunction.Substring -> {
            require(arguments.size in 2..3) { "substring requires a string, one-based start, and optional length" }
            "substr(${arguments.joinToString(", ")})"
        }

        SqlFunction.Concat -> {
            require(arguments.size >= 2) { "concat requires at least two expressions" }
            arguments.joinToString(" || ", prefix = "(", postfix = ")")
        }

        SqlFunction.CurrentDate -> {
            require(arguments.isEmpty()) { "currentDate does not accept arguments" }
            "date('now')"
        }

        SqlFunction.CurrentTime -> {
            require(arguments.isEmpty()) { "currentTime does not accept arguments" }
            "time('now')"
        }

        SqlFunction.CurrentTimestamp -> {
            require(arguments.isEmpty()) { "currentTimestamp does not accept arguments" }
            "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"
        }

        SqlFunction.Year,
        SqlFunction.Month,
        SqlFunction.Day,
        SqlFunction.Hour,
        SqlFunction.Minute,
        SqlFunction.Second,
        -> sqliteExtract(function, arguments)

        else -> super<SqlDdlDialect>.renderFunction(function, arguments)
    }

    override fun proposedInsertReferenceStyle(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): SqlProposedInsertReferenceStyle = sqliteConflictReferenceStyle(behavior)

    override fun requiresInsertSelectConflictDisambiguation(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): Boolean = (
        behavior is dev.quwac.kagiroi.sql.TargetedInsertConflictBehavior ||
            behavior is dev.quwac.kagiroi.sql.MultipleTargetedInsertConflictBehavior
        )

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = renderSqliteConflict(this, behavior, renderAssignments, renderPredicate)

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)

    /** Provides the configured result. */
    fun configured(extensions: Iterable<SqliteExtension>): SqlDdlDialect {
        val installed = extensions.toList()
        return if (installed.isEmpty()) this else ConfiguredSqliteDialect(installed)
    }
}

@Suppress("TooManyFunctions") // Explicitly delegates every dialect capability while changing only the type registry.
private class ConfiguredSqliteDialect(extensions: List<SqliteExtension>) :
    SqlDdlDialect,
    SqlReplaceDialect,
    SqlInsertConflictDialect,
    SqlReturningDialect {
    private val ddlRenderer = SqliteDdlRenderer(SqliteTypeRegistry(extensions))

    override fun quoteIdentifier(identifier: String): String = SqliteDialect.quoteIdentifier(identifier)

    override fun parameterMarker(position: Int): String = SqliteDialect.parameterMarker(position)

    override fun supportsSetOperation(operation: SetOperation): Boolean = SqliteDialect.supportsSetOperation(operation)

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = SqliteDialect.supportsWindow(capability)

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String =
        SqliteDialect.renderLateralJoin(type, relation)

    override fun renderCastTarget(target: SqlCastTarget<*>): String = ddlRenderer.castTargetName(target)

    override fun renderNullSafeComparison(distinct: Boolean, left: String, right: String): String =
        SqliteDialect.renderNullSafeComparison(distinct, left, right)

    override fun renderUpdateFrom(sources: List<String>): String = SqliteDialect.renderUpdateFrom(sources)

    override fun renderOrder(direction: SortDirection, nullOrdering: NullOrdering?): String =
        SqliteDialect.renderOrder(direction, nullOrdering)

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String =
        SqliteDialect.renderFunction(function, arguments)

    override fun proposedInsertReferenceStyle(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): SqlProposedInsertReferenceStyle = SqliteDialect.proposedInsertReferenceStyle(behavior)

    override fun requiresInsertSelectConflictDisambiguation(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): Boolean = SqliteDialect.requiresInsertSelectConflictDisambiguation(behavior)

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = SqliteDialect.renderInsertConflict(behavior, renderAssignments, renderPredicate)

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)
}

private fun sqliteConflictReferenceStyle(
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
): SqlProposedInsertReferenceStyle {
    if (behavior !is dev.quwac.kagiroi.sql.TargetedInsertConflictBehavior &&
        behavior !is dev.quwac.kagiroi.sql.MultipleTargetedInsertConflictBehavior
    ) {
        throw UnsupportedCapability("on-duplicate-key-update")
    }
    return SqlProposedInsertReferenceStyle.Excluded
}

private fun renderSqliteConflict(
    dialect: SqlDialect,
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
    renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
): String {
    if (behavior is dev.quwac.kagiroi.sql.MultipleTargetedInsertConflictBehavior) {
        return behavior.clauses.joinToString(
            " ",
        ) { renderSqliteConflict(dialect, it, renderAssignments, renderPredicate) }
    }
    val targeted = behavior as? dev.quwac.kagiroi.sql.TargetedInsertConflictBehavior
        ?: throw UnsupportedCapability("on-duplicate-key-update")
    val target = when (val target = targeted.target) {
        null -> ""

        is dev.quwac.kagiroi.sql.InsertConflictTarget.Columns -> buildString {
            append(" (")
            append(target.columns.joinToString(", ") { dialect.quoteIdentifier(it.name) })
            append(')')
            target.predicate?.let { append(" WHERE ").append(renderPredicate(it)) }
        }

        is dev.quwac.kagiroi.sql.InsertConflictTarget.Constraint ->
            throw UnsupportedCapability("on-conflict-constraint")
    }
    return "ON CONFLICT$target " + when (val action = targeted.action) {
        dev.quwac.kagiroi.sql.TargetedInsertConflictAction.DoNothing -> "DO NOTHING"

        is dev.quwac.kagiroi.sql.TargetedInsertConflictAction.DoUpdate -> buildString {
            append("DO UPDATE SET ")
            append(renderAssignments(action.update.assignments))
            action.update.predicate?.let { append(" WHERE ").append(renderPredicate(it)) }
        }
    }
}

private fun sqliteExtract(function: SqlFunction, arguments: List<String>): String {
    require(arguments.size == 1) { "$function requires exactly one expression" }
    val format = when (function) {
        SqlFunction.Year -> "%Y"
        SqlFunction.Month -> "%m"
        SqlFunction.Day -> "%d"
        SqlFunction.Hour -> "%H"
        SqlFunction.Minute -> "%M"
        SqlFunction.Second -> "%S"
        else -> error("Not a date extraction function: $function")
    }
    return "CAST(strftime('$format', ${arguments.single()}) AS INTEGER)"
}

/** Resolves SQLite storage classes from schema types and installed transport mappings. */
class SqliteTypeRegistry(extensions: Iterable<SqliteExtension> = emptyList()) {
    private val codecs = SqlTypeCodecRegistry(
        SqliteStandardTypes.typeCodecs + extensions.flatMap(SqliteExtension::typeCodecs),
    )

    /** Provides the type of result. */
    fun typeOf(type: ColumnType<*>): SqliteStorageClass? {
        val physicalType = codecs.storageType(type)
            ?: (type as? MappedColumnType<*, *>)?.underlyingType
            ?: type
        return when (physicalType) {
            StringColumnType, UuidColumnType -> SqliteStorageClass.TEXT

            BooleanColumnType,
            ByteColumnType,
            ShortColumnType,
            IntColumnType,
            LongColumnType,
            UByteColumnType,
            UShortColumnType,
            UIntColumnType,
            ULongColumnType,
            -> SqliteStorageClass.INTEGER

            FloatColumnType, DoubleColumnType -> SqliteStorageClass.REAL

            ByteArrayColumnType -> SqliteStorageClass.BLOB

            else -> null
        }
    }

    internal fun sqlNameOf(type: ColumnType<*>): String? = when (type) {
        is SqliteColumnType<*, *> -> type.sqlDeclaration
        is MappedColumnType<*, *> -> sqlNameOf(type.underlyingType)
        else -> typeOf(type)?.name
    }

    /** SQLite CAST accepts affinity names, not arbitrary CREATE TABLE declarations. */
    internal fun castTargetName(target: SqlCastTarget<*>): String =
        (typeOf(target.type) ?: typeOf(target.storageType))?.name
            ?: throw UnsupportedCapability("cast-target:${target.type.name}")
}

private class SqliteDdlRenderer(private val types: SqliteTypeRegistry) {
    fun castTargetName(target: SqlCastTarget<*>): String = types.castTargetName(target)

    fun render(dialect: SqlDialect, statement: DdlStatement): RenderedSql = RenderedSql(
        when (statement) {
            is dev.quwac.kagiroi.sql.SecurityDdlStatement -> throw dev.quwac.kagiroi.sql.UnsupportedDdlOperation(
                "native security",
                "unsupported dialect",
            )

            is CreateTable -> renderCreateTable(dialect, statement)

            is CreateSequence -> unsupported("SQLite CREATE SEQUENCE", "SQLite has no independent sequence objects")

            is DropTable -> "DROP TABLE ${if (statement.ifExists) "IF EXISTS " else ""}${dialect.quoteTable(
                statement.table,
            )}"

            is DropSequence -> unsupported("SQLite DROP SEQUENCE", "SQLite has no independent sequence objects")

            is AlterTable -> renderAlterTable(dialect, statement)

            is CreateIndex -> renderCreateIndex(dialect, statement)

            is DropIndex -> "DROP INDEX ${dialect.quoteQualifiedIdentifier(statement.table.schemaName, statement.name)}"
        },
        emptyList(),
    )

    private fun renderCreateTable(dialect: SqlDialect, statement: CreateTable): String {
        requireAtMostOneIdentityColumn(statement)
        val definitions = statement.columns.map { renderColumn(dialect, it) } + renderTableConstraints(
            statement,
            dialect::quoteIdentifier,
            dialect::quoteTable,
            { value -> if (value) "1" else "0" },
            ::stringLiteral,
            renderForeignKeyAction = { operation, action -> " ON $operation ${action.sql}" },
            renderFunction = dialect::renderFunction,
            renderCastTarget = dialect::renderCastTarget,
        )
        return buildString {
            append("CREATE TABLE ")
            if (statement.ifNotExists) append("IF NOT EXISTS ")
            append(dialect.quoteTable(statement.table))
            append(" (")
            append(definitions.joinToString(", "))
            append(')')
        }
    }

    private fun renderAlterTable(dialect: SqlDialect, statement: AlterTable): String {
        val prefix = "ALTER TABLE ${dialect.quoteTable(statement.table)} "
        return prefix + when (val operation = statement.operation) {
            is AlterTableOperation.AddColumn ->
                renderSqliteAddedColumn(operation.column) { column -> renderColumn(dialect, column) }

            is AlterTableOperation.DropColumn -> "DROP COLUMN ${dialect.quoteIdentifier(operation.column.name)}"

            is AlterTableOperation.RenameColumn ->
                "RENAME COLUMN ${dialect.quoteIdentifier(operation.column.name)} TO " +
                    dialect.quoteIdentifier(operation.newName)

            is AlterTableOperation.RenameTable -> "RENAME TO ${dialect.quoteIdentifier(operation.newName)}"

            else -> throw UnsupportedDdlOperation(
                "SQLite ALTER TABLE operation",
                "SQLite rebuild emulation is not part of typed ALTER TABLE",
            )
        }
    }

    private fun renderCreateIndex(dialect: SqlDialect, statement: CreateIndex): String = renderSchemaIndex(
        statement.index,
        dialect::quoteIdentifier,
        { table -> dialect.quoteIdentifier(table.tableName) },
        { index -> dialect.quoteQualifiedIdentifier(index.table.schemaName, index.name) },
        { value -> if (value) "1" else "0" },
        ::stringLiteral,
        dialect::renderFunction,
        dialect::renderCastTarget,
        dialect::renderNumericBinary,
        supportsExpressionKeys = true,
        supportsPredicate = true,
        supportsIncludedColumns = false,
        expressionKeyNeedsParentheses = false,
    )

    private fun renderColumn(dialect: SqlDialect, column: Column<*>): String = buildString {
        val type = types.sqlNameOf(column.storageType)
            ?: unsupported("SQLite column type", "No storage class mapping for ${column.storageType.name}")
        append(dialect.quoteIdentifier(column.name))
        append(' ')
        append(type)
        when {
            column.isIdentity -> appendIdentityColumn(type, column)
            column.isGenerated -> appendGeneratedColumn(dialect, column)
            else -> appendOrdinaryColumnConstraints(column)
        }
    }

    private fun StringBuilder.appendIdentityColumn(type: String, column: Column<*>) {
        if (column.isGenerated) unsupported("SQLite identity column", "identity cannot be a generated column")
        if (column.identity?.startWith != null || column.identity?.incrementBy != null) {
            unsupported(
                "SQLite identity options",
                "SQLite rowid allocation has no column definition for an identity start or increment",
            )
        }
        if (type != SqliteStorageClass.INTEGER.name) {
            unsupported("SQLite identity column", "requires the exact INTEGER physical type")
        }
        if (!column.isPrimaryKey) {
            unsupported("SQLite identity column", "requires INTEGER PRIMARY KEY rowid semantics")
        }
        append(" PRIMARY KEY")
    }

    private fun requireAtMostOneIdentityColumn(statement: CreateTable) {
        if (statement.columns.count(Column<*>::isIdentity) > 1) {
            unsupported(
                "SQLite identity columns",
                "INTEGER PRIMARY KEY permits only one identity column per table",
            )
        }
    }

    private fun StringBuilder.appendGeneratedColumn(dialect: SqlDialect, column: Column<*>) {
        append(" GENERATED ALWAYS AS ")
        append(
            renderGeneratedColumnExpression(
                requireNotNull(column.generatedExpression),
                dialect::quoteIdentifier,
                dialect::renderFunction,
                dialect::renderCastTarget,
            ),
        )
        append(' ')
        append(if (column.generatedColumnStorage == GeneratedColumnStorage.Virtual) "VIRTUAL" else "STORED")
        appendOrdinaryColumnConstraints(column)
    }

    private fun StringBuilder.appendOrdinaryColumnConstraints(column: Column<*>) {
        column.defaultExpression?.let {
            append(" DEFAULT ")
            append(
                renderDefaultExpression(
                    column,
                    { value -> if (value) "1" else "0" },
                    ::stringLiteral,
                    { unsupported("SQLite DEFAULT expression", "SQLite has no independent sequence objects") },
                    { "(STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'))" },
                    SqliteDialect::renderFunction,
                    SqliteDialect::renderCastTarget,
                    SqliteDialect::renderNumericBinary,
                    parenthesizeFunctionOrCast = true,
                ),
            )
        }
        if (!column.isNullable) append(" NOT NULL")
        when {
            column.isPrimaryKey -> append(" PRIMARY KEY")
            column.isUnique -> append(" UNIQUE")
        }
    }

    private fun stringLiteral(value: String): String = "'${value.replace("'", "''")}'"
}

private fun renderSqliteAddedColumn(column: Column<*>, renderColumn: (Column<*>) -> String): String {
    if (column.isIdentity) unsupported("SQLite ADD COLUMN", "identity columns cannot be added")
    if (column.isGenerated && column.generatedColumnStorage == GeneratedColumnStorage.Stored) {
        unsupported("SQLite ADD COLUMN", "STORED generated columns are not accepted")
    }
    if (column.defaultExpression?.expression !is dev.quwac.kagiroi.schema.SqlLiteral<*> &&
        column.defaultExpression != null
    ) {
        unsupported("SQLite ADD COLUMN DEFAULT", "SQLite requires a literal DEFAULT when adding a column")
    }
    if (column.requiresSqliteAddedColumnDefault()) {
        unsupported("SQLite ADD COLUMN", "NOT NULL requires a non-null literal DEFAULT")
    }
    if (column.isPrimaryKey || column.isUnique) {
        unsupported("SQLite ADD COLUMN", "PRIMARY KEY and UNIQUE constraints are not accepted")
    }
    return "ADD COLUMN ${renderColumn(column)}"
}

private fun Column<*>.requiresSqliteAddedColumnDefault(): Boolean {
    if (isGenerated || isNullable) return false
    val default = defaultExpression?.expression
    return default !is dev.quwac.kagiroi.schema.SqlLiteral<*> || default.value == null
}

private fun unsupported(operation: String, reason: String): Nothing = throw UnsupportedDdlOperation(operation, reason)
