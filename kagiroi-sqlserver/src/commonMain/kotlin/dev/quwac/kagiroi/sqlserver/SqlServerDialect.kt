package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnIndexKey
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WindowNullTreatment
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
import dev.quwac.kagiroi.sql.RenderedDdl
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SetOperation
import dev.quwac.kagiroi.sql.SqlDdlDialect
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlDmlOutput
import dev.quwac.kagiroi.sql.SqlDmlOutputDialect
import dev.quwac.kagiroi.sql.SqlDmlOutputImage
import dev.quwac.kagiroi.sql.SqlGroupingCapability
import dev.quwac.kagiroi.sql.SqlJoinCapability
import dev.quwac.kagiroi.sql.SqlSelectPagination
import dev.quwac.kagiroi.sql.SqlSequenceDialect
import dev.quwac.kagiroi.sql.SqlWindowCapability
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

/** T-SQL syntax and physical type rules for Microsoft SQL Server. */
@Suppress("TooManyFunctions") // SQL Server owns each dialect rendering variation in one public contract.
object SqlServerDialect : SqlDdlDialect, SqlDmlOutputDialect, SqlSequenceDialect {
    private val types = SqlServerTypeRegistry()
    private val renderer = SqlServerDdlRenderer(types)

    override fun quoteIdentifier(identifier: String): String = "[" + identifier.replace("]", "]]") + "]"

    /** mssql receives the same `kbN` names through Request.input; it never interpolates values. */
    override fun parameterMarker(position: Int): String = "@kb$position"

    override fun supportsSetOperation(operation: SetOperation): Boolean = operation !in setOf(
        SetOperation.IntersectAll,
        SetOperation.ExceptAll,
    )

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = capability in setOf(
        SqlWindowCapability.Ntile,
        SqlWindowCapability.PercentRank,
        SqlWindowCapability.CumeDist,
    )

    override fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean =
        function in setOf(
            SqlFunction.Lag,
            SqlFunction.Lead,
            SqlFunction.FirstValue,
            SqlFunction.LastValue,
        )

    override fun supportsJoinCapability(capability: SqlJoinCapability): Boolean = false

    override fun renderWithKeyword(hasRecursiveCte: Boolean): String = "WITH"

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = true

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String = when (type) {
        LateralJoinType.Cross -> "CROSS APPLY $relation"
        LateralJoinType.Left -> "OUTER APPLY $relation"
    }

    override fun selectPagination(limit: Int?, offset: Int?, hasOrderBy: Boolean): SqlSelectPagination =
        SqlServerPagination(limit, offset, hasOrderBy)

    override fun supportsOrderByInSubquery(limit: Int?, offset: Int?): Boolean = limit != null || offset != null

    override fun renderSelectColumn(column: Column<*>, reference: String): String =
        if (types.requiresTextResult(column.storageType)) "CONVERT(NVARCHAR(4000), $reference)" else reference

    override fun renderFunctionName(function: SqlFunction): String = when (function) {
        SqlFunction.Count -> "COUNT_BIG"
        else -> super<SqlDdlDialect>.renderFunctionName(function)
    }

    @Suppress("MagicNumber") // Closed arity of the SQL substring grammar.
    override fun renderFunction(function: SqlFunction, arguments: List<String>): String = when (function) {
        SqlFunction.Length -> {
            require(arguments.size == 1) { "length requires exactly one expression" }
            "LEN(${arguments.single()} + N'.') - 1"
        }

        SqlFunction.Substring -> {
            require(arguments.size in 2..3) { "substring requires a string, one-based start, and optional length" }
            val length = arguments.getOrElse(2) { "LEN(${arguments[0]} + N'.') - 1" }
            "SUBSTRING(${arguments[0]}, ${arguments[1]}, $length)"
        }

        SqlFunction.Ceiling -> {
            require(arguments.size == 1) { "ceil requires exactly one expression" }
            "CEILING(${arguments.single()})"
        }

        SqlFunction.CurrentDate -> {
            require(arguments.isEmpty()) { "currentDate does not accept arguments" }
            "CONVERT(NVARCHAR(10), SYSDATETIME(), 23)"
        }

        SqlFunction.CurrentTime -> {
            require(arguments.isEmpty()) { "currentTime does not accept arguments" }
            "CONVERT(NVARCHAR(8), SYSDATETIME(), 108) + N'.' + " +
                "RIGHT(CONVERT(NVARCHAR(23), SYSDATETIME(), 121), 3)"
        }

        SqlFunction.CurrentTimestamp -> {
            require(arguments.isEmpty()) { "currentTimestamp does not accept arguments" }
            "CONVERT(NVARCHAR(33), SYSUTCDATETIME(), 127) + N'Z'"
        }

        SqlFunction.Year,
        SqlFunction.Month,
        SqlFunction.Day,
        SqlFunction.Hour,
        SqlFunction.Minute,
        SqlFunction.Second,
        -> {
            require(arguments.size == 1) { "$function requires exactly one expression" }
            "DATEPART(${renderFunctionName(function)}, ${arguments.single()})"
        }

        else -> super<SqlDdlDialect>.renderFunction(function, arguments)
    }

    override fun renderDmlOutput(output: SqlDmlOutput): String = "OUTPUT " + output.columns.joinToString(", ") {
        val image = if (it.image == SqlDmlOutputImage.AFTER) "INSERTED" else "DELETED"
        "$image.${quoteIdentifier(it.column.name)}"
    }

    override fun render(statement: DdlStatement): RenderedSql = renderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = renderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT NEXT VALUE FOR ${quoteQualifiedIdentifier(
            sequence.schemaName,
            sequence.name,
        )} AS kagiroi_sequence_value",
        emptyList(),
    )

    fun configured(extensions: Iterable<SqlServerExtension>): SqlDdlDialect {
        val installed = extensions.toList()
        return if (installed.isEmpty()) this else ConfiguredSqlServerDialect(installed)
    }
}

@Suppress("TooManyFunctions")
private class ConfiguredSqlServerDialect(extensions: List<SqlServerExtension>) :
    SqlDdlDialect,
    SqlDmlOutputDialect,
    SqlSequenceDialect {
    private val types = SqlServerTypeRegistry(extensions)
    private val renderer = SqlServerDdlRenderer(types)

    override fun quoteIdentifier(identifier: String): String = SqlServerDialect.quoteIdentifier(identifier)

    override fun parameterMarker(position: Int): String = SqlServerDialect.parameterMarker(position)

    override fun supportsSetOperation(operation: SetOperation): Boolean = SqlServerDialect.supportsSetOperation(
        operation,
    )

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = SqlServerDialect.supportsWindow(capability)

    override fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean =
        SqlServerDialect.supportsWindowNullTreatment(function, treatment)

    override fun renderWithKeyword(hasRecursiveCte: Boolean): String = SqlServerDialect.renderWithKeyword(
        hasRecursiveCte,
    )

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = SqlServerDialect.supportsGrouping(
        capability,
    )

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String =
        SqlServerDialect.renderLateralJoin(type, relation)

    override fun selectPagination(limit: Int?, offset: Int?, hasOrderBy: Boolean): SqlSelectPagination =
        SqlServerDialect.selectPagination(limit, offset, hasOrderBy)

    override fun supportsOrderByInSubquery(limit: Int?, offset: Int?): Boolean =
        SqlServerDialect.supportsOrderByInSubquery(limit, offset)

    override fun renderSelectColumn(column: Column<*>, reference: String): String =
        if (types.requiresTextResult(column.storageType)) "CONVERT(NVARCHAR(4000), $reference)" else reference

    override fun renderFunctionName(function: SqlFunction): String = SqlServerDialect.renderFunctionName(function)

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String =
        SqlServerDialect.renderFunction(function, arguments)

    override fun renderDmlOutput(output: SqlDmlOutput): String = SqlServerDialect.renderDmlOutput(output)

    override fun render(statement: DdlStatement): RenderedSql = renderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = renderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT NEXT VALUE FOR ${quoteQualifiedIdentifier(
            sequence.schemaName,
            sequence.name,
        )} AS kagiroi_sequence_value",
        emptyList(),
    )
}

private class SqlServerPagination(private val limit: Int?, private val offset: Int?, hasOrderBy: Boolean) :
    SqlSelectPagination {
    private val useOffsetFetch = offset != null

    init {
        require(!useOffsetFetch || hasOrderBy) {
            "SQL Server requires ORDER BY when SelectQuery uses offset(); add orderBy(...) before offset()"
        }
    }

    override val selectParameters: List<Int> = if (limit != null && !useOffsetFetch) listOf(limit) else emptyList()
    override val trailingParameters: List<Int> = if (useOffsetFetch) listOfNotNull(offset, limit) else emptyList()

    override fun renderAfterSelect(markers: List<String>): String =
        if (markers.isEmpty()) "" else "TOP (${markers.single()}) "

    override fun renderAfterOrderBy(markers: List<String>): String = when {
        !useOffsetFetch -> ""
        limit == null -> " OFFSET ${markers.single()} ROWS"
        else -> " OFFSET ${markers[0]} ROWS FETCH NEXT ${markers[1]} ROWS ONLY"
    }
}

private class SqlServerDdlRenderer(private val types: SqlServerTypeRegistry) {
    fun render(dialect: SqlDialect, statement: DdlStatement): RenderedSql = RenderedSql(
        when (statement) {
            is CreateTable -> renderCreateTable(dialect, statement)

            is CreateSequence -> renderSqlServerCreateSequence(dialect, statement)

            is DropTable -> renderDropTable(dialect, statement)

            is DropSequence -> renderSqlServerDropSequence(dialect, statement)

            is AlterTable -> renderAlterTable(dialect, statement)

            is CreateIndex -> renderCreateIndex(dialect, statement)

            is DropIndex -> "DROP INDEX ${dialect.quoteIdentifier(
                statement.name,
            )} ON ${dialect.quoteTable(statement.table)}"
        },
        emptyList(),
    )

    fun renderDdl(dialect: SqlDialect, statement: DdlStatement): RenderedDdl = when (statement) {
        is AlterTable -> renderAlterPlan(dialect, statement)

        else -> {
            val primary = render(dialect, statement)
            when (statement) {
                is CreateTable -> RenderedDdl.of(
                    primary,
                    buildList {
                        statement.tableComment?.let { comment ->
                            add(renderExtendedProperty(statement.table, null, null, comment))
                        }
                        statement.columns.forEach { column ->
                            column.comment?.let { comment ->
                                add(renderExtendedProperty(statement.table, "COLUMN", column.name, comment))
                            }
                        }
                        statement.uniqueConstraints.forEach { unique ->
                            unique.comment?.let { comment ->
                                val name = requireNotNull(unique.name) { "UNIQUE constraint comment requires a name" }
                                add(renderExtendedProperty(statement.table, "CONSTRAINT", name, comment))
                            }
                        }
                    },
                )

                is CreateIndex -> RenderedDdl.of(
                    primary,
                    buildList {
                        statement.comment?.let { comment ->
                            add(renderExtendedProperty(statement.table, "INDEX", statement.name, comment))
                        }
                    },
                )

                else -> RenderedDdl.of(primary)
            }
        }
    }

    private fun renderAlterPlan(dialect: SqlDialect, statement: AlterTable): RenderedDdl {
        val nullability = mutableMapOf<Column<*>, Boolean>()
        val rendered = statement.operations.map { operation ->
            val sql = RenderedSql(
                renderAlterTable(dialect, statement.singleOperation(operation), nullability),
                emptyList(),
            )
            when (operation) {
                is AlterTableOperation.SetNotNull -> nullability[operation.column] = false
                is AlterTableOperation.DropNotNull -> nullability[operation.column] = true
                else -> Unit
            }
            sql
        }
        return RenderedDdl(requireNotNull(rendered.toNonEmptyListOrNull()))
    }

    private fun renderCreateTable(dialect: SqlDialect, statement: CreateTable): String {
        if (statement.ifNotExists) {
            throw UnsupportedDdlOperation("SQL Server CREATE TABLE IF NOT EXISTS", "T-SQL has no portable equivalent")
        }
        requireAtMostOneIdentityColumn(statement)
        statement.primaryKey?.columns?.forEach(::requireIndexable)
        statement.uniqueConstraints.forEach { unique -> unique.columns.forEach(::requireIndexable) }
        val definitions = statement.columns.map { column ->
            renderSqlServerColumn(
                dialect,
                column,
                { value -> types.sqlNameOf(value.storageType) },
                ::requireIndexable,
                ::unicodeStringLiteral,
            )
        } + renderTableConstraints(
            statement,
            dialect::quoteIdentifier,
            dialect::quoteTable,
            { value -> if (value) "1" else "0" },
            ::unicodeStringLiteral,
            renderForeignKeyAction = { operation, action ->
                if (action == ForeignKeyAction.Restrict) {
                    throw UnsupportedDdlOperation(
                        "SQL Server foreign-key action",
                        "RESTRICT is not supported; use NO ACTION when that behavior is intended",
                    )
                }
                " ON $operation ${action.sql}"
            },
            renderFunction = dialect::renderFunction,
            renderCastTarget = dialect::renderCastTarget,
        )
        return buildString {
            append("CREATE TABLE ")
            append(dialect.quoteTable(statement.table))
            append(" (")
            append(definitions.joinToString(", "))
            append(')')
        }
    }

    private fun renderDropTable(dialect: SqlDialect, statement: DropTable): String {
        if (statement.ifExists) {
            throw UnsupportedDdlOperation(
                "SQL Server DROP TABLE IF EXISTS",
                "minimum SQL Server compatibility is not modeled",
            )
        }
        return "DROP TABLE ${dialect.quoteTable(statement.table)}"
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "ThrowsCount",
    ) // Exhaustive SQL Server ALTER grammar preserves dialect boundaries.
    private fun renderAlterTable(
        dialect: SqlDialect,
        statement: AlterTable,
        nullability: Map<Column<*>, Boolean> = emptyMap(),
    ): String {
        val table = dialect.quoteTable(statement.table)
        return when (val operation = statement.operation) {
            is AlterTableOperation.AddColumn -> {
                if (operation.column.isIdentity) {
                    throw UnsupportedDdlOperation(
                        "SQL Server ADD COLUMN identity",
                        "SQL Server cannot add an IDENTITY property with generic ALTER TABLE ADD",
                    )
                }
                "ALTER TABLE $table ADD ${renderSqlServerColumn(
                    dialect,
                    operation.column,
                    { value -> types.sqlNameOf(value.storageType) },
                    ::requireIndexable,
                    ::unicodeStringLiteral,
                )}"
            }

            is AlterTableOperation.DropColumn -> "ALTER TABLE $table DROP COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )}"

            is AlterTableOperation.RenameColumn -> {
                val current = dialect.quoteQualifiedIdentifier(statement.table.schemaName, statement.table.tableName) +
                    ".${dialect.quoteIdentifier(operation.column.name)}"
                "EXEC sp_rename N'${current.replace("'", "''")}', N'${operation.newName.replace("'", "''")}', N'COLUMN'"
            }

            is AlterTableOperation.RenameTable -> {
                val current = dialect.quoteQualifiedIdentifier(statement.table.schemaName, statement.table.tableName)
                "EXEC sp_rename N'${current.replace("'", "''")}', N'${operation.newName.replace("'", "''")}'"
            }

            is AlterTableOperation.AddPrimaryKey -> "ALTER TABLE $table ADD ${renderPrimaryKeyConstraint(
                operation.constraint,
                dialect::quoteIdentifier,
            )}"

            is AlterTableOperation.DropPrimaryKey -> "ALTER TABLE $table DROP CONSTRAINT ${dialect.quoteIdentifier(
                requireNotNull(operation.name) { "SQL Server DROP PRIMARY KEY requires the constraint name" },
            )}"

            is AlterTableOperation.AddUnique -> "ALTER TABLE $table ADD ${renderUniqueConstraint(
                operation.constraint,
                dialect::quoteIdentifier,
                "",
            )}"

            is AlterTableOperation.DropUnique,
            is AlterTableOperation.DropForeignKey,
            is AlterTableOperation.DropCheck,
            -> "ALTER TABLE $table DROP CONSTRAINT ${dialect.quoteIdentifier(
                when (operation) {
                    is AlterTableOperation.DropUnique -> operation.name
                    is AlterTableOperation.DropForeignKey -> operation.name
                    is AlterTableOperation.DropCheck -> operation.name
                },
            )}"

            is AlterTableOperation.AddForeignKey -> "ALTER TABLE $table ADD ${renderForeignKeyConstraint(
                operation.constraint,
                dialect::quoteIdentifier,
                dialect::quoteTable,
            ) { kind, action ->
                if (action == ForeignKeyAction.Restrict) {
                    throw UnsupportedDdlOperation(
                        "SQL Server foreign-key action",
                        "RESTRICT is not supported; use NO ACTION",
                    )
                }
                " ON $kind ${action.sql}"
            }}"

            is AlterTableOperation.AddCheck -> buildString {
                append("ALTER TABLE ").append(table).append(" ADD ")
                operation.constraint.name?.let { append("CONSTRAINT ").append(dialect.quoteIdentifier(it)).append(' ') }
                append("CHECK (")
                append(
                    renderCheckConstraintExpression(
                        operation.constraint.expression,
                        dialect::quoteIdentifier,
                        { value -> if (value) "1" else "0" },
                        ::unicodeStringLiteral,
                        dialect::renderFunction,
                        dialect::renderCastTarget,
                    ),
                )
                append(')')
            }

            is AlterTableOperation.AlterColumnType ->
                "ALTER TABLE $table ALTER COLUMN " +
                    dialect.quoteIdentifier(
                        operation.column.name,
                    ) + " " + requireNotNull(types.sqlNameOf(operation.storageType)) {
                        "SQL Server has no physical type mapping for ${operation.storageType.name}"
                    } + if (nullability[operation.column] ?: operation.column.isNullable) " NULL" else " NOT NULL"

            is AlterTableOperation.SetNotNull -> renderSqlServerNullability(dialect, table, operation.column, false)

            is AlterTableOperation.DropNotNull -> renderSqlServerNullability(dialect, table, operation.column, true)

            is AlterTableOperation.SetDefault,
            is AlterTableOperation.DropDefault,
            -> throw UnsupportedDdlOperation(
                "SQL Server ALTER TABLE DEFAULT",
                "SQL Server defaults are named constraints; generic ALTER does not infer their names",
            )
        }
    }

    private fun renderSqlServerNullability(
        dialect: SqlDialect,
        table: String,
        column: Column<*>,
        nullable: Boolean,
    ): String {
        require(!column.isIdentity && !column.isGenerated) {
            "SQL Server generic ALTER COLUMN nullability does not support identity or generated columns"
        }
        val type = requireNotNull(types.sqlNameOf(column.storageType)) {
            "SQL Server has no physical type mapping for ${column.storageType.name}"
        }
        return "ALTER TABLE $table ALTER COLUMN ${dialect.quoteIdentifier(column.name)} $type " +
            if (nullable) "NULL" else "NOT NULL"
    }

    private fun renderCreateIndex(dialect: SqlDialect, statement: CreateIndex): String {
        statement.keys.filterIsInstance<ColumnIndexKey>().forEach { key ->
            requireIndexable(key.column)
        }
        return renderSchemaIndex(
            statement.index,
            dialect::quoteIdentifier,
            dialect::quoteTable,
            { index -> dialect.quoteIdentifier(index.name) },
            { value -> if (value) "1" else "0" },
            ::unicodeStringLiteral,
            dialect::renderFunction,
            dialect::renderCastTarget,
            dialect::renderNumericBinary,
            supportsExpressionKeys = false,
            supportsPredicate = true,
            supportsIncludedColumns = true,
            expressionKeyNeedsParentheses = false,
        )
    }

    private fun requireIndexable(column: Column<*>) {
        val declaration = types.sqlNameOf(column.storageType) ?: return
        if (declaration.endsWith("(MAX)")) {
            throw UnsupportedDdlOperation(
                "SQL Server index",
                "$declaration for ${column.storageType.name} requires an explicit bounded SQL Server column type",
            )
        }
    }

    private fun renderExtendedProperty(
        table: Table,
        level2Type: String?,
        level2Name: String?,
        comment: String,
    ): RenderedSql = RenderedSql(
        buildString {
            if (table.schemaName == null) {
                append("DECLARE @kagiroi_schema sysname = SCHEMA_NAME(); ")
            }
            append("EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = ")
            append(unicodeStringLiteral(comment))
            append(", @level0type = N'SCHEMA', @level0name = ")
            append(table.schemaName?.let(::unicodeStringLiteral) ?: "@kagiroi_schema")
            append(", @level1type = N'TABLE', @level1name = ")
            append(unicodeStringLiteral(table.tableName))
            level2Type?.let { type ->
                append(", @level2type = ")
                append(unicodeStringLiteral(type))
                append(", @level2name = ")
                append(unicodeStringLiteral(requireNotNull(level2Name)))
            }
        },
        emptyList(),
    )

    private fun unicodeStringLiteral(value: String): String = "N'${value.replace("'", "''")}'"
}

private fun requireAtMostOneIdentityColumn(statement: CreateTable) {
    if (statement.columns.count(Column<*>::isIdentity) > 1) {
        throw UnsupportedDdlOperation(
            "SQL Server identity columns",
            "SQL Server permits only one identity column per table",
        )
    }
}

private fun renderSqlServerCreateSequence(dialect: SqlDialect, statement: CreateSequence): String {
    if (statement.ifNotExists) {
        throw UnsupportedDdlOperation("SQL Server CREATE SEQUENCE IF NOT EXISTS", "T-SQL has no portable equivalent")
    }
    return "CREATE SEQUENCE ${dialect.quoteQualifiedIdentifier(
        statement.sequence.schemaName,
        statement.sequence.name,
    )}"
}

private fun renderSqlServerDropSequence(dialect: SqlDialect, statement: DropSequence): String {
    if (statement.ifExists) {
        throw UnsupportedDdlOperation(
            "SQL Server DROP SEQUENCE IF EXISTS",
            "minimum SQL Server compatibility is not modeled",
        )
    }
    return "DROP SEQUENCE ${dialect.quoteQualifiedIdentifier(
        statement.sequence.schemaName,
        statement.sequence.name,
    )}"
}

private fun renderSqlServerColumn(
    dialect: SqlDialect,
    column: Column<*>,
    typeNameOf: (Column<*>) -> String?,
    requireIndexable: (Column<*>) -> Unit,
    stringLiteral: (String) -> String,
): String = buildString {
    append(dialect.quoteIdentifier(column.name))
    if (column.isGenerated) {
        appendSqlServerGeneratedColumn(dialect, column, requireIndexable)
    } else {
        appendSqlServerOrdinaryColumn(dialect, column, typeNameOf, requireIndexable, stringLiteral)
    }
}

private fun StringBuilder.appendSqlServerGeneratedColumn(
    dialect: SqlDialect,
    column: Column<*>,
    requireIndexable: (Column<*>) -> Unit,
) {
    append(" AS ")
    append(
        renderGeneratedColumnExpression(
            requireNotNull(column.generatedExpression),
            dialect::quoteIdentifier,
            dialect::renderFunction,
            dialect::renderCastTarget,
        ),
    )
    if (column.generatedColumnStorage == GeneratedColumnStorage.Stored) append(" PERSISTED")
    if (!column.isNullable) {
        if (column.generatedColumnStorage != GeneratedColumnStorage.Stored) {
            throw UnsupportedDdlOperation(
                "SQL Server virtual computed column nullability",
                "NOT NULL requires a PERSISTED computed column",
            )
        }
        append(" NOT NULL")
    }
    appendSqlServerKeyConstraint(column, requireIndexable)
}

private fun StringBuilder.appendSqlServerOrdinaryColumn(
    dialect: SqlDialect,
    column: Column<*>,
    typeNameOf: (Column<*>) -> String?,
    requireIndexable: (Column<*>) -> Unit,
    stringLiteral: (String) -> String,
) {
    val type = typeNameOf(column) ?: throw UnsupportedDdlOperation(
        "SQL Server column type",
        "No physical type mapping for ${column.storageType.name}",
    )
    append(' ')
    append(type)
    column.identity?.let { identity ->
        append(" IDENTITY")
        if (identity.startWith != null || identity.incrementBy != null) {
            append('(')
            append(identity.startWith ?: 1L)
            append(", ")
            append(identity.incrementBy ?: 1L)
            append(')')
        }
    }
    column.defaultExpression?.let {
        append(" DEFAULT ")
        append(
            renderDefaultExpression(
                column,
                { value -> if (value) "1" else "0" },
                stringLiteral,
                { sequence ->
                    "NEXT VALUE FOR ${dialect.quoteQualifiedIdentifier(
                        sequence.schemaName,
                        sequence.name,
                    )}"
                },
                { "(SYSDATETIMEOFFSET())" },
                dialect::renderFunction,
                dialect::renderCastTarget,
                dialect::renderNumericBinary,
                parenthesizeFunctionOrCast = true,
            ),
        )
    }
    if (!column.isNullable) append(" NOT NULL")
    appendSqlServerKeyConstraint(column, requireIndexable)
}

private fun StringBuilder.appendSqlServerKeyConstraint(column: Column<*>, requireIndexable: (Column<*>) -> Unit) {
    when {
        column.isPrimaryKey -> {
            requireIndexable(column)
            append(" PRIMARY KEY")
        }

        column.isUnique -> {
            requireIndexable(column)
            append(" UNIQUE")
        }
    }
}
