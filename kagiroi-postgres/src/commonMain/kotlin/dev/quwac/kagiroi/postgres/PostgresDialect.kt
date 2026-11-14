package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.LikeOperator
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlExpressionAlias
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlLiteral
import dev.quwac.kagiroi.sql.AlterTable
import dev.quwac.kagiroi.sql.AlterTableOperation
import dev.quwac.kagiroi.sql.CreateIndex
import dev.quwac.kagiroi.sql.CreateSequence
import dev.quwac.kagiroi.sql.CreateTable
import dev.quwac.kagiroi.sql.DdlStatement
import dev.quwac.kagiroi.sql.DropIndex
import dev.quwac.kagiroi.sql.DropSequence
import dev.quwac.kagiroi.sql.DropTable
import dev.quwac.kagiroi.sql.NullOrdering
import dev.quwac.kagiroi.sql.RenderedDdl
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SetOperation
import dev.quwac.kagiroi.sql.SortDirection
import dev.quwac.kagiroi.sql.SqlDdlDialect
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlGroupingCapability
import dev.quwac.kagiroi.sql.SqlInsertConflictDialect
import dev.quwac.kagiroi.sql.SqlProposedInsertReferenceStyle
import dev.quwac.kagiroi.sql.SqlReturningDialect
import dev.quwac.kagiroi.sql.SqlSequenceDialect
import dev.quwac.kagiroi.sql.SqlWindowCapability
import dev.quwac.kagiroi.sql.SqlWithinGroupCapability
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.renderCheckConstraintExpression
import dev.quwac.kagiroi.sql.renderDefaultExpression
import dev.quwac.kagiroi.sql.renderForeignKeyConstraint
import dev.quwac.kagiroi.sql.renderGeneratedColumnExpression
import dev.quwac.kagiroi.sql.renderPrimaryKeyConstraint
import dev.quwac.kagiroi.sql.renderSchemaIndex
import dev.quwac.kagiroi.sql.renderTableConstraints
import dev.quwac.kagiroi.sql.renderUniqueConstraint

/** PostgreSQL syntax for the SQL query models supported by kagiroi-sql. */
@Suppress("TooManyFunctions")
object PostgresDialect :
    SqlDdlDialect,
    SqlInsertConflictDialect,
    SqlReturningDialect,
    SqlSequenceDialect,
    dev.quwac.kagiroi.sql.SqlRlsContextDialect {
    override val rlsContextAdapter: dev.quwac.kagiroi.sql.RlsContextAdapter get() = PostgresRlsContext
    private val ddlRenderer = PostgresDdlRenderer(PostgresTypeRegistry())
    private val types = PostgresTypeRegistry()

    override fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    override fun parameterMarker(position: Int): String = "$$position"

    override fun supportsSetOperation(operation: SetOperation): Boolean = true

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun renderLikeOperator(operator: LikeOperator): String = operator.sql

    override fun renderNullSafeComparison(distinct: Boolean, left: String, right: String): String =
        "$left IS ${if (distinct) "" else "NOT "}DISTINCT FROM $right"

    override fun renderSelectDistinct(distinct: Boolean, distinctOn: List<String>): String = when {
        distinctOn.isNotEmpty() -> "DISTINCT ON (${distinctOn.joinToString(", ")}) "
        distinct -> "DISTINCT "
        else -> ""
    }

    override fun renderUpdateFrom(sources: List<String>): String =
        sources.joinToString(", ", prefix = " FROM ").takeIf { sources.isNotEmpty() }.orEmpty()

    override fun renderOrder(direction: SortDirection, nullOrdering: NullOrdering?): String =
        direction.sql + nullOrdering?.let { " ${it.sql}" }.orEmpty()

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = true

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = true

    override fun supportsWithinGroup(capability: SqlWithinGroupCapability): Boolean = true

    override fun renderRecursiveCteAnchorProjection(projection: SqlExpression<*>, reference: String): String =
        types.renderRecursiveCteAnchorProjection(projection, reference)

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String = when (function) {
        SqlFunction.PostgresAnyEquals -> {
            require(arguments.size == 2) { "Array ANY requires a value and an array" }
            "(${arguments[0]} = ANY (${arguments[1]}))"
        }

        SqlFunction.PostgresAtTimeZone -> {
            require(arguments.size == 2) { "AT TIME ZONE requires a value and time zone" }
            "(${arguments[0]} AT TIME ZONE ${arguments[1]})"
        }

        SqlFunction.PostgresCurrentSetting -> "current_setting(${arguments.joinToString(", ")})"

        SqlFunction.Substring -> postgresSubstring(arguments)

        SqlFunction.Concat -> postgresConcat(arguments)

        SqlFunction.Round -> postgresRound(arguments)

        else -> super<SqlDdlDialect>.renderFunction(function, arguments)
    }

    override fun proposedInsertReferenceStyle(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): SqlProposedInsertReferenceStyle = postgresConflictReferenceStyle(behavior)

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = renderPostgresConflict(this, behavior, renderAssignments, renderPredicate)

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = ddlRenderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT ${renderPostgresSequenceNextValue(this, sequence)} AS kagiroi_sequence_value",
        emptyList(),
    )

    /** Provides the configured result. */
    fun configured(extensions: Iterable<PostgresExtension>): SqlDdlDialect {
        val installed = extensions.toList()
        return if (installed.isEmpty()) this else ConfiguredPostgresDialect(installed)
    }
}

@Suppress("TooManyFunctions") // Delegation keeps configured extensions on the same dialect contract.
private class ConfiguredPostgresDialect(extensions: List<PostgresExtension>) :
    SqlDdlDialect,
    SqlInsertConflictDialect,
    SqlReturningDialect,
    SqlSequenceDialect,
    dev.quwac.kagiroi.sql.SqlRlsContextDialect {
    override val rlsContextAdapter: dev.quwac.kagiroi.sql.RlsContextAdapter get() = PostgresRlsContext
    private val ddlRenderer = PostgresDdlRenderer(PostgresTypeRegistry(extensions))
    private val types = PostgresTypeRegistry(extensions)

    override fun quoteIdentifier(identifier: String): String = PostgresDialect.quoteIdentifier(identifier)

    override fun parameterMarker(position: Int): String = PostgresDialect.parameterMarker(position)

    override fun supportsSetOperation(operation: SetOperation): Boolean = PostgresDialect.supportsSetOperation(
        operation,
    )

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun renderLikeOperator(operator: LikeOperator): String = PostgresDialect.renderLikeOperator(operator)

    override fun renderNullSafeComparison(distinct: Boolean, left: String, right: String): String =
        PostgresDialect.renderNullSafeComparison(distinct, left, right)

    override fun renderSelectDistinct(distinct: Boolean, distinctOn: List<String>): String =
        PostgresDialect.renderSelectDistinct(distinct, distinctOn)

    override fun renderUpdateFrom(sources: List<String>): String = PostgresDialect.renderUpdateFrom(sources)

    override fun renderOrder(direction: SortDirection, nullOrdering: NullOrdering?): String =
        PostgresDialect.renderOrder(direction, nullOrdering)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = PostgresDialect.supportsGrouping(
        capability,
    )

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = PostgresDialect.supportsWindow(capability)

    override fun supportsWithinGroup(capability: SqlWithinGroupCapability): Boolean =
        PostgresDialect.supportsWithinGroup(capability)

    override fun renderRecursiveCteAnchorProjection(projection: SqlExpression<*>, reference: String): String =
        types.renderRecursiveCteAnchorProjection(projection, reference)

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String =
        PostgresDialect.renderFunction(function, arguments)

    override fun proposedInsertReferenceStyle(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): SqlProposedInsertReferenceStyle = PostgresDialect.proposedInsertReferenceStyle(behavior)

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = PostgresDialect.renderInsertConflict(behavior, renderAssignments, renderPredicate)

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = ddlRenderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT ${renderPostgresSequenceNextValue(this, sequence)} AS kagiroi_sequence_value",
        emptyList(),
    )
}

private fun postgresConflictReferenceStyle(
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
): SqlProposedInsertReferenceStyle {
    if (behavior !is dev.quwac.kagiroi.sql.TargetedInsertConflictBehavior) {
        throw dev.quwac.kagiroi.sql.UnsupportedCapability("on-duplicate-key-update")
    }
    return SqlProposedInsertReferenceStyle.Excluded
}

private fun renderPostgresConflict(
    dialect: SqlDialect,
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
    renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
): String {
    val targeted = behavior as? dev.quwac.kagiroi.sql.TargetedInsertConflictBehavior
        ?: throw dev.quwac.kagiroi.sql.UnsupportedCapability("on-duplicate-key-update")
    val target = when (val target = targeted.target) {
        null -> ""

        is dev.quwac.kagiroi.sql.InsertConflictTarget.Columns -> buildString {
            append(" (")
            append(target.columns.joinToString(", ") { dialect.quoteIdentifier(it.name) })
            append(')')
            target.predicate?.let { append(" WHERE ").append(renderPredicate(it)) }
        }

        is dev.quwac.kagiroi.sql.InsertConflictTarget.Constraint ->
            " ON CONSTRAINT ${dialect.quoteIdentifier(target.name)}"
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

private fun postgresSubstring(arguments: List<String>): String {
    require(arguments.size in REQUIRED_SUBSTRING_ARGUMENTS..MAXIMUM_SUBSTRING_ARGUMENTS) {
        "substring requires a string, one-based start, and optional length"
    }
    return buildString {
        append("SUBSTRING(")
        append(arguments[0])
        append(" FROM (")
        append(arguments[1])
        append(")::integer")
        if (arguments.size == MAXIMUM_SUBSTRING_ARGUMENTS) append(" FOR (").append(arguments[2]).append(")::integer")
        append(')')
    }
}

private fun postgresConcat(arguments: List<String>): String {
    require(arguments.size >= 2) { "concat requires at least two expressions" }
    return arguments.joinToString(" || ", prefix = "(", postfix = ")")
}

private fun postgresRound(arguments: List<String>): String = when (arguments.size) {
    ONE_ARGUMENT -> "ROUND(${arguments.single()})"
    TWO_ARGUMENTS -> "ROUND((${arguments[0]})::numeric, (${arguments[1]})::integer)"
    else -> throw IllegalArgumentException("round requires a numeric expression and optional digits")
}

private const val ONE_ARGUMENT = 1
private const val TWO_ARGUMENTS = 2
private const val REQUIRED_SUBSTRING_ARGUMENTS = 2
private const val MAXIMUM_SUBSTRING_ARGUMENTS = 3

private fun PostgresTypeRegistry.renderRecursiveCteAnchorProjection(
    projection: SqlExpression<*>,
    reference: String,
): String {
    val expression = (projection as? SqlExpressionAlias<*>)?.expression ?: projection
    if (expression !is SqlLiteral<*>) return reference
    val sqlType = sqlNameOf(expression.type) ?: sqlNameOf(expression.storageType) ?: return reference
    return "CAST($reference AS $sqlType)"
}

private fun renderPostgresSequenceNextValue(dialect: SqlDialect, sequence: dev.quwac.kagiroi.schema.Sequence): String {
    val qualifiedName = dialect.quoteQualifiedIdentifier(sequence.schemaName, sequence.name)
    return "nextval('${qualifiedName.replace("'", "''")}'::regclass)"
}

@Suppress("TooManyFunctions") // One renderer owns the complete PostgreSQL DDL grammar.
private class PostgresDdlRenderer(private val types: PostgresTypeRegistry) {
    fun render(dialect: SqlDialect, statement: DdlStatement): RenderedSql = RenderedSql(
        when (statement) {
            is dev.quwac.kagiroi.sql.SecurityDdlStatement -> renderPostgresSecurity(dialect, statement)

            is CreateTable -> renderCreateTable(dialect, statement)

            is CreateSequence -> renderCreateSequence(dialect, statement)

            is DropTable -> "DROP TABLE ${if (statement.ifExists) "IF EXISTS " else ""}${dialect.quoteTable(
                statement.table,
            )}"

            is DropSequence -> "DROP SEQUENCE ${if (statement.ifExists) "IF EXISTS " else ""}" +
                dialect.quoteQualifiedIdentifier(statement.sequence.schemaName, statement.sequence.name)

            is AlterTable -> renderAlterTable(dialect, statement)

            is CreateIndex -> renderCreateIndex(dialect, statement)

            is DropIndex -> "DROP INDEX ${dialect.quoteQualifiedIdentifier(statement.table.schemaName, statement.name)}"
        },
        emptyList(),
    )

    fun renderDdl(dialect: SqlDialect, statement: DdlStatement): RenderedDdl {
        val primary = render(dialect, statement)
        return when (statement) {
            is CreateTable -> RenderedDdl.of(
                primary,
                buildList {
                    statement.tableComment?.let { comment ->
                        add(
                            RenderedSql(
                                "COMMENT ON TABLE ${dialect.quoteTable(statement.table)} IS ${stringLiteral(comment)}",
                                emptyList(),
                            ),
                        )
                    }
                    statement.columns.forEach { column ->
                        column.comment?.let { comment ->
                            add(
                                RenderedSql(
                                    "COMMENT ON COLUMN ${dialect.quoteTable(statement.table)}." +
                                        "${dialect.quoteIdentifier(column.name)} IS ${stringLiteral(comment)}",
                                    emptyList(),
                                ),
                            )
                        }
                    }
                    statement.uniqueConstraints.forEach { unique ->
                        unique.comment?.let { comment ->
                            val name = requireNotNull(unique.name) { "UNIQUE constraint comment requires a name" }
                            add(
                                RenderedSql(
                                    "COMMENT ON CONSTRAINT ${dialect.quoteIdentifier(name)} ON " +
                                        "${dialect.quoteTable(statement.table)} IS ${stringLiteral(comment)}",
                                    emptyList(),
                                ),
                            )
                        }
                    }
                },
            )

            is CreateIndex -> RenderedDdl.of(
                primary,
                buildList {
                    statement.comment?.let { comment ->
                        add(
                            RenderedSql(
                                "COMMENT ON INDEX ${dialect.quoteQualifiedIdentifier(
                                    statement.table.schemaName,
                                    statement.name,
                                )} IS ${stringLiteral(comment)}",
                                emptyList(),
                            ),
                        )
                    }
                },
            )

            is AlterTable -> renderAlterPlan(dialect, statement)

            else -> RenderedDdl.of(primary)
        }
    }

    private fun renderAlterPlan(dialect: SqlDialect, statement: AlterTable): RenderedDdl {
        val prefix = "ALTER TABLE ${dialect.quoteTable(statement.table)} "
        val actions = statement.operations.map { operation ->
            require(renderAlterTable(dialect, statement.singleOperation(operation)).startsWith(prefix))
            renderAlterTable(dialect, statement.singleOperation(operation)).removePrefix(prefix)
        }
        val renameLast = statement.operations.last() is AlterTableOperation.RenameColumn ||
            statement.operations.last() is AlterTableOperation.RenameTable
        return if (renameLast && actions.size > 1) {
            RenderedDdl.of(
                RenderedSql(prefix + actions.dropLast(1).joinToString(", "), emptyList()),
                listOf(RenderedSql(prefix + actions.last(), emptyList())),
            )
        } else {
            RenderedDdl.of(RenderedSql(prefix + actions.joinToString(", "), emptyList()))
        }
    }

    private fun renderCreateTable(dialect: SqlDialect, statement: CreateTable): String {
        val definitions = statement.columns.map { renderColumn(dialect, it) } + renderTableConstraints(
            statement,
            dialect::quoteIdentifier,
            dialect::quoteTable,
            { value -> if (value) "TRUE" else "FALSE" },
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

    private fun renderCreateSequence(dialect: SqlDialect, statement: CreateSequence): String =
        "CREATE SEQUENCE ${if (statement.ifNotExists) "IF NOT EXISTS " else ""}" +
            dialect.quoteQualifiedIdentifier(statement.sequence.schemaName, statement.sequence.name)

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Exhaustive mapping of the typed ALTER operation grammar.
    private fun renderAlterTable(dialect: SqlDialect, statement: AlterTable): String {
        val prefix = "ALTER TABLE ${dialect.quoteTable(statement.table)} "
        return prefix + when (val operation = statement.operation) {
            is AlterTableOperation.AddColumn -> "ADD COLUMN ${renderColumn(dialect, operation.column)}"

            is AlterTableOperation.DropColumn -> "DROP COLUMN ${dialect.quoteIdentifier(operation.column.name)}"

            is AlterTableOperation.RenameColumn ->
                "RENAME COLUMN ${dialect.quoteIdentifier(operation.column.name)} TO " +
                    dialect.quoteIdentifier(operation.newName)

            is AlterTableOperation.RenameTable -> "RENAME TO ${dialect.quoteIdentifier(operation.newName)}"

            is AlterTableOperation.AddPrimaryKey ->
                "ADD ${renderPrimaryKeyConstraint(operation.constraint, dialect::quoteIdentifier)}"

            is AlterTableOperation.DropPrimaryKey -> "DROP CONSTRAINT ${dialect.quoteIdentifier(
                requireNotNull(operation.name) { "PostgreSQL DROP PRIMARY KEY requires the constraint name" },
            )}"

            is AlterTableOperation.AddUnique ->
                "ADD ${renderUniqueConstraint(operation.constraint, dialect::quoteIdentifier, "")}"

            is AlterTableOperation.DropUnique,
            is AlterTableOperation.DropForeignKey,
            is AlterTableOperation.DropCheck,
            -> "DROP CONSTRAINT ${dialect.quoteIdentifier(
                when (operation) {
                    is AlterTableOperation.DropUnique -> operation.name
                    is AlterTableOperation.DropForeignKey -> operation.name
                    is AlterTableOperation.DropCheck -> operation.name
                },
            )}"

            is AlterTableOperation.AddForeignKey -> "ADD ${renderForeignKeyConstraint(
                operation.constraint,
                dialect::quoteIdentifier,
                dialect::quoteTable,
            ) { kind, action -> " ON $kind ${action.sql}" }}"

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

            is AlterTableOperation.AlterColumnType -> "ALTER COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )} TYPE " +
                requireNotNull(types.sqlNameOf(operation.storageType)) {
                    "PostgreSQL has no physical type mapping for ${operation.storageType.name}"
                }

            is AlterTableOperation.SetDefault -> "ALTER COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )} SET DEFAULT " +
                renderDefaultExpression(
                    operation.column,
                    operation.default,
                    { value -> if (value) "TRUE" else "FALSE" },
                    ::stringLiteral,
                    { sequence -> renderPostgresSequenceNextValue(dialect, sequence) },
                    { "(CURRENT_TIMESTAMP)" },
                    dialect::renderFunction,
                    dialect::renderCastTarget,
                    dialect::renderNumericBinary,
                )

            is AlterTableOperation.DropDefault -> "ALTER COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )} DROP DEFAULT"

            is AlterTableOperation.SetNotNull -> "ALTER COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )} SET NOT NULL"

            is AlterTableOperation.DropNotNull -> "ALTER COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )} DROP NOT NULL"
        }
    }

    private fun renderCreateIndex(dialect: SqlDialect, statement: CreateIndex): String = renderSchemaIndex(
        statement.index,
        dialect::quoteIdentifier,
        dialect::quoteTable,
        { index -> dialect.quoteIdentifier(index.name) },
        { value -> if (value) "TRUE" else "FALSE" },
        ::stringLiteral,
        dialect::renderFunction,
        dialect::renderCastTarget,
        dialect::renderNumericBinary,
        supportsExpressionKeys = true,
        supportsPredicate = true,
        supportsIncludedColumns = true,
        expressionKeyNeedsParentheses = true,
    )

    private fun renderColumn(dialect: SqlDialect, column: Column<*>): String = buildString {
        val type = types.sqlNameOf(column.storageType)
            ?: throw UnsupportedDdlOperation(
                "PostgreSQL column type",
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
    }

    private fun StringBuilder.appendIdentityColumn(column: Column<*>) {
        if (column.isGenerated) {
            throw UnsupportedDdlOperation("PostgreSQL identity column", "identity cannot be a generated column")
        }
        append(" GENERATED BY DEFAULT AS IDENTITY")
        column.identity?.let { identity ->
            if (identity.startWith != null || identity.incrementBy != null) {
                append(" (")
                identity.startWith?.let { append("START WITH ").append(it) }
                if (identity.startWith != null && identity.incrementBy != null) append(' ')
                identity.incrementBy?.let { append("INCREMENT BY ").append(it) }
                append(')')
            }
        }
        appendOrdinaryColumnConstraints(PostgresDialect, column)
    }

    private fun StringBuilder.appendGeneratedColumn(dialect: SqlDialect, column: Column<*>) {
        if (column.generatedColumnStorage != GeneratedColumnStorage.Stored) {
            throw UnsupportedDdlOperation(
                "PostgreSQL generated column storage",
                "the supported PostgreSQL and PGlite baseline accepts STORED generated columns only",
            )
        }
        append(" GENERATED ALWAYS AS ")
        append(
            renderGeneratedColumnExpression(
                requireNotNull(column.generatedExpression),
                dialect::quoteIdentifier,
                dialect::renderFunction,
                dialect::renderCastTarget,
            ),
        )
        append(" STORED")
        appendOrdinaryColumnConstraints(dialect, column)
    }

    private fun StringBuilder.appendOrdinaryColumnConstraints(dialect: SqlDialect, column: Column<*>) {
        column.defaultExpression?.let {
            append(" DEFAULT ")
            append(
                renderDefaultExpression(
                    column,
                    { value -> if (value) "TRUE" else "FALSE" },
                    ::stringLiteral,
                    { sequence -> renderPostgresSequenceNextValue(dialect, sequence) },
                    { "(CURRENT_TIMESTAMP)" },
                    dialect::renderFunction,
                    dialect::renderCastTarget,
                    dialect::renderNumericBinary,
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
