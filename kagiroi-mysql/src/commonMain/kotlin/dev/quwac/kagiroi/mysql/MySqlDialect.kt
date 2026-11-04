package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnIndexKey
import dev.quwac.kagiroi.schema.ExpressionIndexKey
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlExpressionAlias
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlLiteral
import dev.quwac.kagiroi.schema.WindowNullTreatment
import dev.quwac.kagiroi.schema.castTarget
import dev.quwac.kagiroi.sql.AlterTable
import dev.quwac.kagiroi.sql.AlterTableOperation
import dev.quwac.kagiroi.sql.CreateIndex
import dev.quwac.kagiroi.sql.CreateSequence
import dev.quwac.kagiroi.sql.CreateTable
import dev.quwac.kagiroi.sql.DdlStatement
import dev.quwac.kagiroi.sql.DropIndex
import dev.quwac.kagiroi.sql.DropSequence
import dev.quwac.kagiroi.sql.DropTable
import dev.quwac.kagiroi.sql.JoinType
import dev.quwac.kagiroi.sql.RenderedDdl
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SetOperation
import dev.quwac.kagiroi.sql.SqlDdlDialect
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlGroupingCapability
import dev.quwac.kagiroi.sql.SqlInsertConflictDialect
import dev.quwac.kagiroi.sql.SqlProposedInsertReferenceStyle
import dev.quwac.kagiroi.sql.SqlReplaceDialect
import dev.quwac.kagiroi.sql.SqlStatementKind
import dev.quwac.kagiroi.sql.SqlWindowCapability
import dev.quwac.kagiroi.sql.SqlWithClausePlacement
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.renderCheckConstraintExpression
import dev.quwac.kagiroi.sql.renderDefaultExpression
import dev.quwac.kagiroi.sql.renderForeignKeyConstraint
import dev.quwac.kagiroi.sql.renderGeneratedColumnExpression
import dev.quwac.kagiroi.sql.renderPrimaryKeyConstraint
import dev.quwac.kagiroi.sql.renderSchemaIndex
import dev.quwac.kagiroi.sql.renderTableConstraints
import dev.quwac.kagiroi.sql.renderUniqueConstraint
import dev.quwac.kagiroi.sql.toNonEmptyListOrNull

/** MySQL syntax for the SQL query models supported by kagiroi-sql. */
@Suppress("TooManyFunctions") // A dialect owns all render hooks of the public SQL contract.
object MySqlDialect : SqlDdlDialect, SqlReplaceDialect, SqlInsertConflictDialect {
    private val ddlRenderer = MySqlCompatibleDdlRenderer(MySqlTypeRegistry())
    private val types = MySqlTypeRegistry()

    override fun quoteIdentifier(identifier: String): String = quoteMySqlCompatibleIdentifier(identifier)

    override fun parameterMarker(position: Int): String = "?"

    override fun supportsSetOperation(operation: SetOperation): Boolean = true

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = capability in setOf(
        SqlWindowCapability.Ntile,
        SqlWindowCapability.NthValue,
        SqlWindowCapability.PercentRank,
        SqlWindowCapability.CumeDist,
        SqlWindowCapability.NamedWindow,
    )

    override fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean =
        treatment == WindowNullTreatment.RespectNulls && function in setOf(
            SqlFunction.Lag,
            SqlFunction.Lead,
            SqlFunction.FirstValue,
            SqlFunction.LastValue,
            SqlFunction.NthValue,
        )

    override fun withClausePlacement(statement: SqlStatementKind): SqlWithClausePlacement = when (statement) {
        SqlStatementKind.Insert -> SqlWithClausePlacement.BeforeInsertSelect
        else -> SqlWithClausePlacement.BeforeStatement
    }

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun renderNullSafeComparison(distinct: Boolean, left: String, right: String): String =
        if (distinct) "NOT ($left <=> $right)" else "($left <=> $right)"

    override fun renderRecursiveCteAnchorProjection(projection: SqlExpression<*>, reference: String): String =
        types.renderRecursiveCteAnchorProjection(projection, reference)

    override fun supportsJoin(type: JoinType): Boolean = type != JoinType.Full

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = capability in setOf(
        SqlGroupingCapability.Rollup,
        SqlGroupingCapability.GroupingFunction,
    )

    override fun renderGroupBy(
        elements: List<GroupByElement>,
        renderExpression: (SqlExpression<*>) -> String,
    ): String {
        if (elements.all { it is SqlExpression<*> }) {
            return super<SqlDdlDialect>.renderGroupBy(
                elements,
                renderExpression,
            )
        }
        if (elements.any { it !is SqlExpression<*> && it !is dev.quwac.kagiroi.sql.Rollup }) {
            return super<SqlDdlDialect>.renderGroupBy(elements, renderExpression)
        }
        if (elements.size != 1 || elements.single() !is dev.quwac.kagiroi.sql.Rollup) {
            throw UnsupportedCapability(SqlGroupingCapability.Rollup.unsupportedCapability)
        }
        return super<SqlDdlDialect>.renderGroupBy(elements, renderExpression)
    }

    override fun renderFunctionName(function: SqlFunction): String = when (function) {
        SqlFunction.Length -> "CHAR_LENGTH"
        else -> super<SqlDdlDialect>.renderFunctionName(function)
    }

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String = when (function) {
        SqlFunction.CurrentTimestamp -> {
            require(arguments.isEmpty()) { "currentTimestamp does not accept arguments" }
            "UTC_TIMESTAMP(6)"
        }

        SqlFunction.Year,
        SqlFunction.Month,
        SqlFunction.Day,
        SqlFunction.Hour,
        SqlFunction.Minute,
        SqlFunction.Second,
        -> {
            require(arguments.size == 1) { "$function requires exactly one expression" }
            "${renderFunctionName(function)}(${arguments.single()})"
        }

        else -> super<SqlDdlDialect>.renderFunction(function, arguments)
    }

    override fun proposedInsertReferenceStyle(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): SqlProposedInsertReferenceStyle = mySqlConflictReferenceStyle(behavior)

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = renderMySqlDuplicateKeyUpdate(behavior, renderAssignments)

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = ddlRenderer.renderDdl(this, statement)

    /** Provides the configured result. */
    fun configured(extensions: Iterable<MySqlExtension>): SqlDdlDialect {
        val installed = extensions.toList()
        return if (installed.isEmpty()) this else ConfiguredMySqlDialect(installed)
    }
}

@Suppress("TooManyFunctions") // Keeps extension configuration delegating through every dialect hook.
private class ConfiguredMySqlDialect(extensions: List<MySqlExtension>) :
    SqlDdlDialect,
    SqlReplaceDialect,
    SqlInsertConflictDialect {
    private val ddlRenderer = MySqlCompatibleDdlRenderer(MySqlTypeRegistry(extensions))
    private val types = MySqlTypeRegistry(extensions)

    override fun quoteIdentifier(identifier: String): String = MySqlDialect.quoteIdentifier(identifier)

    override fun parameterMarker(position: Int): String = MySqlDialect.parameterMarker(position)

    override fun supportsSetOperation(operation: SetOperation): Boolean = MySqlDialect.supportsSetOperation(operation)

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = MySqlDialect.supportsWindow(capability)

    override fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean =
        MySqlDialect.supportsWindowNullTreatment(function, treatment)

    override fun withClausePlacement(statement: SqlStatementKind): SqlWithClausePlacement =
        MySqlDialect.withClausePlacement(statement)

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun renderRecursiveCteAnchorProjection(projection: SqlExpression<*>, reference: String): String =
        types.renderRecursiveCteAnchorProjection(projection, reference)

    override fun supportsJoin(type: JoinType): Boolean = MySqlDialect.supportsJoin(type)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = MySqlDialect.supportsGrouping(
        capability,
    )

    override fun renderGroupBy(
        elements: List<GroupByElement>,
        renderExpression: (SqlExpression<*>) -> String,
    ): String = MySqlDialect.renderGroupBy(elements, renderExpression)

    override fun renderFunctionName(function: SqlFunction): String = MySqlDialect.renderFunctionName(function)

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String =
        MySqlDialect.renderFunction(function, arguments)

    override fun proposedInsertReferenceStyle(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): SqlProposedInsertReferenceStyle = MySqlDialect.proposedInsertReferenceStyle(behavior)

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = MySqlDialect.renderInsertConflict(behavior, renderAssignments, renderPredicate)

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = ddlRenderer.renderDdl(this, statement)
}

private fun mySqlConflictReferenceStyle(
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
): SqlProposedInsertReferenceStyle {
    if (behavior !is dev.quwac.kagiroi.sql.DuplicateKeyUpdateBehavior) {
        throw UnsupportedCapability("on-conflict")
    }
    return SqlProposedInsertReferenceStyle.RowAlias
}

private fun renderMySqlDuplicateKeyUpdate(
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
): String {
    val duplicate = behavior as? dev.quwac.kagiroi.sql.DuplicateKeyUpdateBehavior
        ?: throw UnsupportedCapability("on-conflict")
    return "ON DUPLICATE KEY UPDATE ${renderAssignments(duplicate.update.assignments)}"
}

/** Backtick quoting shared by SQL dialects that require the MySQL identifier rules. */
fun quoteMySqlCompatibleIdentifier(identifier: String): String = "`${identifier.replace("`", "``")}`"

private fun MySqlTypeRegistry.renderRecursiveCteAnchorProjection(
    projection: SqlExpression<*>,
    reference: String,
): String {
    val expression = (projection as? SqlExpressionAlias<*>)?.expression ?: projection
    if (expression !is SqlLiteral<*>) return reference
    return "CAST($reference AS ${castTarget(expression).let(::castTargetName)})"
}

/**
 * Renders the generic DDL model for engines with MySQL-compatible table DDL.
 *
 * Dialects remain responsible for selecting this component: sharing the renderer does not
 * imply that their broader SQL semantics are the same.
 */
@Suppress("TooManyFunctions") // Keeps the complete MySQL-compatible DDL grammar in one reusable renderer.
class MySqlCompatibleDdlRenderer(
    private val typeNameOf: (Column<*>) -> String?,
    private val generatedStorageKeyword: (GeneratedColumnStorage) -> String = ::mySqlGeneratedStorageKeyword,
    private val supportsGeneratedNotNull: Boolean = true,
    private val supportsSequences: Boolean = false,
    private val supportsExpressionIndexes: Boolean = true,
    private val functionalIndexTypeNameOf: (SqlExpression<*>) -> String? = { null },
    private val renderCurrentInstant: () -> String = {
        throw UnsupportedDdlOperation(
            "MySQL current Instant DEFAULT",
            "MySQL TIMESTAMP defaults depend on the session time zone",
        )
    },
) {
    constructor(types: MySqlTypeRegistry) : this(
        { column -> types.sqlNameOf(column.storageType) },
        functionalIndexTypeNameOf = { expression -> types.sqlNameOf(expression.storageType) },
    )

    /** Renders the render result. */
    fun render(dialect: SqlDialect, statement: DdlStatement): RenderedSql = RenderedSql(
        when (statement) {
            is CreateTable -> renderCreateTable(dialect, statement)

            is CreateSequence -> renderMySqlCompatibleCreateSequence(dialect, statement, supportsSequences)

            is DropTable -> "DROP TABLE ${if (statement.ifExists) "IF EXISTS " else ""}${dialect.quoteTable(
                statement.table,
            )}"

            is DropSequence -> renderMySqlCompatibleDropSequence(dialect, statement, supportsSequences)

            is AlterTable -> renderAlterTable(dialect, statement)

            is CreateIndex -> renderCreateIndex(dialect, statement)

            is DropIndex ->
                "DROP INDEX ${dialect.quoteIdentifier(statement.name)} ON ${dialect.quoteTable(statement.table)}"
        },
        emptyList(),
    )

    /** Renders the render ddl result. */
    fun renderDdl(dialect: SqlDialect, statement: DdlStatement): RenderedDdl = when (statement) {
        is AlterTable -> renderAlterPlan(dialect, statement)
        else -> RenderedDdl.of(render(dialect, statement))
    }

    private fun renderCreateTable(dialect: SqlDialect, statement: CreateTable): String {
        val identity = requireMySqlCompatibleIdentity(statement)
        statement.primaryKey?.columns?.forEach(::requireGenericIndexable)
        statement.uniqueConstraints.forEach { unique -> unique.columns.forEach(::requireGenericIndexable) }
        val definitions = statement.columns.map { renderColumn(dialect, it) } + renderTableConstraints(
            statement,
            dialect::quoteIdentifier,
            dialect::quoteTable,
            { value -> if (value) "TRUE" else "FALSE" },
            ::stringLiteral,
            renderForeignKeyAction = { operation, action ->
                if (action == dev.quwac.kagiroi.schema.ForeignKeyAction.SetDefault) {
                    throw UnsupportedDdlOperation(
                        "MySQL-compatible foreign-key action",
                        "SET DEFAULT is not supported by the default InnoDB engine",
                    )
                }
                " ON $operation ${action.sql}"
            },
            renderUniqueComment = { unique ->
                unique.comment?.let { comment -> " COMMENT ${stringLiteral(comment)}" }.orEmpty()
            },
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
            identity?.identity?.startWith?.let { startWith ->
                append(" AUTO_INCREMENT = ")
                append(startWith)
            }
            statement.tableComment?.let { comment ->
                append(" COMMENT = ")
                append(stringLiteral(comment))
            }
        }
    }

    private fun renderAlterPlan(dialect: SqlDialect, statement: AlterTable): RenderedDdl {
        val operations = statement.operations.toList()
        val terminalRename = operations.lastOrNull()?.takeIf {
            it is AlterTableOperation.RenameColumn || it is AlterTableOperation.RenameTable
        }
        val precedingOperations = if (terminalRename == null) operations else operations.dropLast(1)
        val statements = buildList {
            if (precedingOperations.isNotEmpty()) {
                val actions = precedingOperations.map { operation ->
                    renderAlterAction(dialect, statement.singleOperation(operation))
                }
                add(
                    RenderedSql(
                        "ALTER TABLE ${dialect.quoteTable(statement.table)} ${actions.joinToString(", ")}",
                        emptyList(),
                    ),
                )
            }
            terminalRename?.let { operation ->
                add(RenderedSql(renderAlterTable(dialect, statement.singleOperation(operation)), emptyList()))
            }
        }
        return RenderedDdl(requireNotNull(statements.toNonEmptyListOrNull()))
    }

    private fun renderAlterTable(dialect: SqlDialect, statement: AlterTable): String =
        "ALTER TABLE ${dialect.quoteTable(statement.table)} ${renderAlterAction(dialect, statement)}"

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Exhaustive mapping of the typed ALTER operation grammar.
    private fun renderAlterAction(dialect: SqlDialect, statement: AlterTable): String =
        when (val operation = statement.operation) {
            is AlterTableOperation.AddColumn -> {
                requireMySqlCompatibleIdentityOptionsForAddColumn(operation.column)
                "ADD COLUMN ${renderColumn(dialect, operation.column)}"
            }

            is AlterTableOperation.DropColumn -> "DROP COLUMN ${dialect.quoteIdentifier(operation.column.name)}"

            is AlterTableOperation.RenameColumn ->
                "RENAME COLUMN ${dialect.quoteIdentifier(operation.column.name)} TO " +
                    dialect.quoteIdentifier(operation.newName)

            is AlterTableOperation.RenameTable ->
                "RENAME TO ${dialect.quoteQualifiedIdentifier(statement.table.schemaName, operation.newName)}"

            is AlterTableOperation.AddPrimaryKey ->
                "ADD ${renderPrimaryKeyConstraint(operation.constraint, dialect::quoteIdentifier)}"

            is AlterTableOperation.DropPrimaryKey -> "DROP PRIMARY KEY"

            is AlterTableOperation.AddUnique ->
                "ADD ${renderUniqueConstraint(operation.constraint, dialect::quoteIdentifier, "")}"

            is AlterTableOperation.DropUnique -> "DROP INDEX ${dialect.quoteIdentifier(operation.name)}"

            is AlterTableOperation.AddForeignKey -> "ADD ${renderForeignKeyConstraint(
                operation.constraint,
                dialect::quoteIdentifier,
                dialect::quoteTable,
            ) { kind, action ->
                if (action == dev.quwac.kagiroi.schema.ForeignKeyAction.SetDefault) {
                    throw UnsupportedDdlOperation(
                        "MySQL-compatible foreign-key action",
                        "SET DEFAULT is not supported by the default InnoDB engine",
                    )
                }
                " ON $kind ${action.sql}"
            }}"

            is AlterTableOperation.DropForeignKey -> "DROP FOREIGN KEY ${dialect.quoteIdentifier(operation.name)}"

            is AlterTableOperation.AddCheck -> buildString {
                append("ADD ")
                operation.constraint.name?.let { append("CONSTRAINT ").append(dialect.quoteIdentifier(it)).append(' ') }
                append("CHECK (")
                append(
                    renderCheckConstraintExpression(
                        operation.constraint.expression,
                        dialect::quoteIdentifier,
                        { value -> if (value) "TRUE" else "FALSE" },
                        ::stringLiteral,
                        dialect::renderFunction,
                        dialect::renderCastTarget,
                    ),
                )
                append(')')
            }

            is AlterTableOperation.DropCheck -> "DROP CHECK ${dialect.quoteIdentifier(operation.name)}"

            is AlterTableOperation.SetDefault -> "ALTER COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )} SET DEFAULT " +
                renderDefaultExpression(
                    operation.column,
                    operation.default,
                    { value -> if (value) "TRUE" else "FALSE" },
                    ::stringLiteral,
                    { sequence ->
                        requireMySqlCompatibleSequences(supportsSequences, "sequence DEFAULT expression")
                        "nextval(${dialect.quoteQualifiedIdentifier(sequence.schemaName, sequence.name)})"
                    },
                    renderCurrentInstant,
                    dialect::renderFunction,
                    dialect::renderCastTarget,
                    dialect::renderNumericBinary,
                )

            is AlterTableOperation.DropDefault -> "ALTER COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )} DROP DEFAULT"

            is AlterTableOperation.AlterColumnType,
            is AlterTableOperation.SetNotNull,
            is AlterTableOperation.DropNotNull,
            -> throw UnsupportedDdlOperation(
                "MySQL-compatible ALTER TABLE column definition",
                "changing type or nullability requires a complete column definition and is not inferred",
            )
        }

    private fun renderCreateIndex(dialect: SqlDialect, statement: CreateIndex): String = buildString {
        statement.keys.filterIsInstance<ColumnIndexKey>().forEach { key ->
            requireGenericIndexable(key.column)
        }
        statement.keys.filterIsInstance<ExpressionIndexKey>().forEach { key ->
            requireFunctionalIndexable(key.expression)
        }
        append(
            renderSchemaIndex(
                statement.index,
                dialect::quoteIdentifier,
                dialect::quoteTable,
                { index -> dialect.quoteIdentifier(index.name) },
                { value -> if (value) "TRUE" else "FALSE" },
                ::stringLiteral,
                dialect::renderFunction,
                dialect::renderCastTarget,
                dialect::renderNumericBinary,
                supportsExpressionKeys = supportsExpressionIndexes,
                supportsPredicate = false,
                supportsIncludedColumns = false,
                expressionKeyNeedsParentheses = true,
            ),
        )
        statement.comment?.let { comment -> append(" COMMENT ").append(stringLiteral(comment)) }
    }

    private fun renderColumn(dialect: SqlDialect, column: Column<*>): String = buildString {
        val type = typeNameOf(column)
            ?: throw UnsupportedDdlOperation(
                "MySQL-compatible column type",
                "No physical type mapping for ${column.storageType.name}",
            )
        append(dialect.quoteIdentifier(column.name))
        append(' ')
        append(type)
        when {
            column.isIdentity -> appendIdentityColumn(column)
            column.isGenerated -> appendGeneratedColumn(dialect, column)
            else -> appendOrdinaryColumnConstraints(dialect, column)
        }
        column.comment?.let { comment ->
            append(" COMMENT ")
            append(stringLiteral(comment))
        }
    }

    private fun StringBuilder.appendIdentityColumn(column: Column<*>) {
        if (column.isGenerated) {
            throw UnsupportedDdlOperation("MySQL-compatible identity column", "identity cannot be generated")
        }
        if (!column.isPrimaryKey && !column.isUnique) {
            throw UnsupportedDdlOperation("MySQL-compatible identity column", "AUTO_INCREMENT requires a key")
        }
        if (!column.isNullable) append(" NOT NULL")
        append(" AUTO_INCREMENT")
        appendKeyConstraint(column)
    }

    private fun requireMySqlCompatibleIdentity(statement: CreateTable): Column<*>? {
        val identities = statement.columns.filter(Column<*>::isIdentity)
        if (identities.size > 1) {
            throw UnsupportedDdlOperation(
                "MySQL-compatible identity columns",
                "AUTO_INCREMENT permits only one identity column per table",
            )
        }
        return identities.singleOrNull()?.also(::requireMySqlCompatibleIdentityOptions)
    }

    private fun requireMySqlCompatibleIdentityOptions(column: Column<*>) {
        val identity = requireNotNull(column.identity)
        if (identity.incrementBy != null) {
            throw UnsupportedDdlOperation(
                "MySQL-compatible identity increment",
                "AUTO_INCREMENT increment is controlled by server or session configuration, not a column definition",
            )
        }
        val startWith = identity.startWith
        if (startWith != null && startWith <= 0L) {
            throw UnsupportedDdlOperation(
                "MySQL-compatible identity start",
                "AUTO_INCREMENT start values must be positive",
            )
        }
    }

    private fun requireMySqlCompatibleIdentityOptionsForAddColumn(column: Column<*>) {
        if (column.identity?.startWith != null || column.identity?.incrementBy != null) {
            throw UnsupportedDdlOperation(
                "MySQL-compatible ADD COLUMN identity options",
                "identity start is a CREATE TABLE option and identity increment is not a column definition",
            )
        }
    }

    private fun StringBuilder.appendGeneratedColumn(dialect: SqlDialect, column: Column<*>) {
        val expression = requireNotNull(column.generatedExpression)
        if (!supportsGeneratedNotNull && !column.isNullable && expression.referencedColumns.any { it.isNullable }) {
            throw UnsupportedDdlOperation(
                "MySQL-compatible generated column",
                "a non-null generated column cannot reference nullable columns without native NOT NULL support",
            )
        }
        append(" GENERATED ALWAYS AS ")
        append(
            renderGeneratedColumnExpression(
                expression,
                dialect::quoteIdentifier,
                dialect::renderFunction,
                dialect::renderCastTarget,
            ),
        )
        append(' ')
        append(generatedStorageKeyword(requireNotNull(column.generatedColumnStorage)))
        if (supportsGeneratedNotNull) {
            appendOrdinaryColumnConstraints(dialect, column)
        } else {
            appendKeyConstraint(column)
        }
    }

    private fun StringBuilder.appendOrdinaryColumnConstraints(dialect: SqlDialect, column: Column<*>) {
        column.defaultExpression?.let {
            append(" DEFAULT ")
            append(
                renderDefaultExpression(
                    column,
                    { value -> if (value) "TRUE" else "FALSE" },
                    ::stringLiteral,
                    { sequence ->
                        requireMySqlCompatibleSequences(supportsSequences, "sequence DEFAULT expression")
                        "nextval(${dialect.quoteQualifiedIdentifier(sequence.schemaName, sequence.name)})"
                    },
                    renderCurrentInstant,
                    dialect::renderFunction,
                    dialect::renderCastTarget,
                    dialect::renderNumericBinary,
                    parenthesizeFunctionOrCast = true,
                ),
            )
        }
        if (!column.isNullable) append(" NOT NULL")
        appendKeyConstraint(column)
    }

    private fun StringBuilder.appendKeyConstraint(column: Column<*>) {
        when {
            column.isPrimaryKey -> {
                requireGenericIndexable(column)
                append(" PRIMARY KEY")
            }

            column.isUnique -> {
                requireGenericIndexable(column)
                append(" UNIQUE")
            }
        }
    }

    private fun requireGenericIndexable(column: Column<*>) {
        val physicalType = typeNameOf(column) ?: return
        val reason = physicalType.genericIndexConstraintReason()
        if (reason != null) {
            throw UnsupportedDdlOperation(
                "MySQL-compatible index",
                "$physicalType for ${column.storageType.name} $reason",
            )
        }
    }

    private fun requireFunctionalIndexable(expression: SqlExpression<*>) {
        val physicalType = functionalIndexTypeNameOf(expression) ?: throw UnsupportedDdlOperation(
            "MySQL-compatible functional index",
            "No physical type mapping for ${expression.storageType.name}",
        )
        val reason = physicalType.genericIndexConstraintReason()
        if (reason != null) {
            throw UnsupportedDdlOperation(
                "MySQL-compatible functional index",
                "$physicalType for ${expression.storageType.name} $reason",
            )
        }
    }

    private fun stringLiteral(value: String): String = "'${value.replace("\\", "\\\\").replace("'", "''")}'"
}

private fun renderMySqlCompatibleCreateSequence(
    dialect: SqlDialect,
    statement: CreateSequence,
    supportsSequences: Boolean,
): String {
    requireMySqlCompatibleSequences(supportsSequences, "CREATE SEQUENCE")
    return "CREATE SEQUENCE ${if (statement.ifNotExists) "IF NOT EXISTS " else ""}" +
        dialect.quoteQualifiedIdentifier(statement.sequence.schemaName, statement.sequence.name)
}

private fun renderMySqlCompatibleDropSequence(
    dialect: SqlDialect,
    statement: DropSequence,
    supportsSequences: Boolean,
): String {
    requireMySqlCompatibleSequences(supportsSequences, "DROP SEQUENCE")
    return "DROP SEQUENCE ${if (statement.ifExists) "IF EXISTS " else ""}" +
        dialect.quoteQualifiedIdentifier(statement.sequence.schemaName, statement.sequence.name)
}

private fun requireMySqlCompatibleSequences(supportsSequences: Boolean, operation: String) {
    if (!supportsSequences) {
        throw UnsupportedDdlOperation(operation, "MySQL has no independent sequence objects")
    }
}

private fun mySqlGeneratedStorageKeyword(storage: GeneratedColumnStorage): String = when (storage) {
    GeneratedColumnStorage.Virtual -> "VIRTUAL"
    GeneratedColumnStorage.Stored -> "STORED"
}

private fun String.genericIndexConstraintReason(): String? =
    when (substringBefore(' ').substringBefore('(').uppercase()) {
        "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT" ->
            "requires an explicit index prefix length"

        "JSON" -> "requires a generated or functional index that generic Typed DDL does not model"

        "GEOMETRY", "POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON",
        "GEOMETRYCOLLECTION", "VECTOR",
        -> "requires a dialect-specific spatial or vector index"

        "XMLTYPE" -> "requires a dialect-specific index definition"

        else -> null
    }
