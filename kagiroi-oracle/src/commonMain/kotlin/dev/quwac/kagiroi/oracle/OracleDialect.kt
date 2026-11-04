@file:Suppress("Indentation", "TooManyFunctions")

package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnIndexKey
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.NumericBinaryOperator
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlFunction
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
import dev.quwac.kagiroi.sql.NullOrdering
import dev.quwac.kagiroi.sql.RenderedDdl
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SetOperation
import dev.quwac.kagiroi.sql.SortDirection
import dev.quwac.kagiroi.sql.SqlDdlDialect
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlDmlReturningInto
import dev.quwac.kagiroi.sql.SqlDmlReturningIntoDialect
import dev.quwac.kagiroi.sql.SqlGroupingCapability
import dev.quwac.kagiroi.sql.SqlMergeDialect
import dev.quwac.kagiroi.sql.SqlMergeKind
import dev.quwac.kagiroi.sql.SqlSelectPagination
import dev.quwac.kagiroi.sql.SqlSequenceDialect
import dev.quwac.kagiroi.sql.SqlStatementKind
import dev.quwac.kagiroi.sql.SqlWindowCapability
import dev.quwac.kagiroi.sql.SqlWithClausePlacement
import dev.quwac.kagiroi.sql.SqlWithinGroupCapability
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

/** Oracle Database 23ai SQL syntax and physical type rules. */
object OracleDialect : SqlDdlDialect, SqlMergeDialect, SqlDmlReturningIntoDialect, SqlSequenceDialect {
    override val mergeKind: SqlMergeKind = SqlMergeKind.ORACLE

    private val types = OracleTypeRegistry()
    private val renderer = OracleDdlRenderer(types)

    /**
     * Always quoting keeps a Kotlin table or column name exact. Oracle would uppercase
     * an unquoted identifier, so callers must use the same case when issuing external raw SQL.
     */
    override fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    /** Ordered renderer parameters become deterministic Oracle bind names p1, p2, and so on. */
    override fun parameterMarker(position: Int): String = ":p$position"

    override fun supportsSetOperation(operation: SetOperation): Boolean = true

    override fun renderOrder(direction: SortDirection, nullOrdering: NullOrdering?): String =
        direction.sql + nullOrdering?.let { " ${it.sql}" }.orEmpty()

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = true

    override fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean =
        function in setOf(
        SqlFunction.Lag,
        SqlFunction.Lead,
        SqlFunction.FirstValue,
        SqlFunction.LastValue,
        SqlFunction.NthValue,
    )

    override fun supportsWithinGroup(capability: SqlWithinGroupCapability): Boolean =
        capability != SqlWithinGroupCapability.Mode

    override fun renderWithKeyword(hasRecursiveCte: Boolean): String = "WITH"

    override fun withClausePlacement(statement: SqlStatementKind): SqlWithClausePlacement = when (statement) {
        SqlStatementKind.Select -> SqlWithClausePlacement.BeforeStatement

        SqlStatementKind.Insert -> SqlWithClausePlacement.BeforeInsertSelect

        SqlStatementKind.Update,
        SqlStatementKind.Delete,
        -> SqlWithClausePlacement.Unsupported
    }

    override fun validateDmlReturningInto(statement: SqlStatementKind, insertUsesQuerySource: Boolean) {
        if (statement == SqlStatementKind.Insert && insertUsesQuerySource) {
            throw UnsupportedCapability("returning-into-insert-select")
        }
    }

    override fun sourceLessSelectRelation(): String = "DUAL"

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = true

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String = when (type) {
        LateralJoinType.Cross -> "CROSS APPLY $relation"
        LateralJoinType.Left -> "OUTER APPLY $relation"
    }

    /** Oracle's long-established spelling for the standard EXCEPT semantics. */
    override fun renderSetOperation(operation: SetOperation): String = when (operation) {
        SetOperation.Except -> "MINUS"
        SetOperation.ExceptAll -> "MINUS ALL"
        else -> super<SqlDdlDialect>.renderSetOperation(operation)
    }

    override fun renderRelationAlias(alias: String): String = " ${quoteIdentifier(alias)}"

    override fun selectPagination(limit: Int?, offset: Int?, hasOrderBy: Boolean): SqlSelectPagination =
        OraclePagination(limit, offset)

    override fun renderSelectColumn(column: Column<*>, reference: String): String = types.renderSelectColumn(
        column,
        reference,
    )

    override fun renderNumericBinary(operator: NumericBinaryOperator, left: String, right: String): String =
        if (operator == NumericBinaryOperator.Modulo) {
            "MOD($left, $right)"
        } else {
            super<SqlDdlDialect>.renderNumericBinary(operator, left, right)
        }

    @Suppress("MagicNumber") // Closed arity of the SQL substring grammar.
    override fun renderFunction(function: SqlFunction, arguments: List<String>): String = when (function) {
        SqlFunction.Substring -> {
            require(arguments.size in 2..3) { "substring requires a string, one-based start, and optional length" }
            "SUBSTR(${arguments.joinToString(", ")})"
        }

        SqlFunction.Concat -> {
            require(arguments.size >= 2) { "concat requires at least two expressions" }
            arguments.joinToString(" || ", prefix = "(", postfix = ")")
        }

        SqlFunction.CurrentDate -> {
            require(arguments.isEmpty()) { "currentDate does not accept arguments" }
            "TO_CHAR(CURRENT_DATE, 'YYYY-MM-DD')"
        }

        SqlFunction.CurrentTime -> {
            require(arguments.isEmpty()) { "currentTime does not accept arguments" }
            "TO_CHAR(CURRENT_TIMESTAMP, 'HH24:MI:SS.FF9')"
        }

        SqlFunction.CurrentTimestamp -> {
            require(arguments.isEmpty()) { "currentTimestamp does not accept arguments" }
            "TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9') || 'Z'"
        }

        else -> super<SqlDdlDialect>.renderFunction(function, arguments)
    }

    override fun renderDmlReturningInto(returning: SqlDmlReturningInto): String = buildString {
        append("RETURNING ")
        append(returning.columns.joinToString(", ") { quoteIdentifier(it.name) })
        append(" INTO ")
        append(returning.parameterNames.joinToString(", ") { name -> ":$name" })
    }

    override fun render(statement: DdlStatement): RenderedSql = renderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = renderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT ${quoteQualifiedIdentifier(
            sequence.schemaName,
            sequence.name,
        )}.NEXTVAL AS kagiroi_sequence_value FROM DUAL",
        emptyList(),
    )

    fun configured(extensions: Iterable<OracleExtension>): SqlDdlDialect {
        val installed = extensions.toList()
        return if (installed.isEmpty()) this else ConfiguredOracleDialect(installed)
    }
}

private class ConfiguredOracleDialect(extensions: List<OracleExtension>) :
    SqlDdlDialect,
    SqlMergeDialect,
    SqlDmlReturningIntoDialect,
    SqlSequenceDialect {
    override val mergeKind: SqlMergeKind = SqlMergeKind.ORACLE

    private val types = OracleTypeRegistry(extensions)
    private val renderer = OracleDdlRenderer(types)

    override fun quoteIdentifier(identifier: String): String = OracleDialect.quoteIdentifier(identifier)

    override fun parameterMarker(position: Int): String = OracleDialect.parameterMarker(position)

    override fun supportsSetOperation(operation: SetOperation): Boolean = OracleDialect.supportsSetOperation(operation)

    override fun renderOrder(direction: SortDirection, nullOrdering: NullOrdering?): String =
        OracleDialect.renderOrder(direction, nullOrdering)

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = OracleDialect.supportsWindow(capability)

    override fun supportsWindowNullTreatment(function: SqlFunction, treatment: WindowNullTreatment): Boolean =
        OracleDialect.supportsWindowNullTreatment(function, treatment)

    override fun supportsWithinGroup(capability: SqlWithinGroupCapability): Boolean =
        OracleDialect.supportsWithinGroup(capability)

    override fun renderWithKeyword(hasRecursiveCte: Boolean): String = OracleDialect.renderWithKeyword(hasRecursiveCte)

    override fun withClausePlacement(statement: SqlStatementKind): SqlWithClausePlacement =
        OracleDialect.withClausePlacement(statement)

    override fun validateDmlReturningInto(statement: SqlStatementKind, insertUsesQuerySource: Boolean) =
        OracleDialect.validateDmlReturningInto(statement, insertUsesQuerySource)

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = OracleDialect.supportsGrouping(
        capability,
    )

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String =
        OracleDialect.renderLateralJoin(type, relation)

    override fun renderSetOperation(operation: SetOperation): String = OracleDialect.renderSetOperation(operation)

    override fun renderRelationAlias(alias: String): String = OracleDialect.renderRelationAlias(alias)

    override fun selectPagination(limit: Int?, offset: Int?, hasOrderBy: Boolean): SqlSelectPagination =
        OracleDialect.selectPagination(limit, offset, hasOrderBy)

    override fun renderSelectColumn(column: Column<*>, reference: String): String = types.renderSelectColumn(
        column,
        reference,
    )

    override fun renderNumericBinary(operator: NumericBinaryOperator, left: String, right: String): String =
        OracleDialect.renderNumericBinary(operator, left, right)

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String =
        OracleDialect.renderFunction(function, arguments)

    override fun renderDmlReturningInto(returning: SqlDmlReturningInto): String =
        OracleDialect.renderDmlReturningInto(returning)

    override fun render(statement: DdlStatement): RenderedSql = renderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = renderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT ${quoteQualifiedIdentifier(
            sequence.schemaName,
            sequence.name,
        )}.NEXTVAL AS kagiroi_sequence_value FROM DUAL",
        emptyList(),
    )
}

private fun OracleTypeRegistry.renderSelectColumn(column: Column<*>, reference: String): String = when (
    typeOf(
    column.storageType,
)
) {
    OracleType.DATE -> "TO_CHAR($reference, 'YYYY-MM-DD HH24:MI:SS')"

    OracleType.TIMESTAMP -> "TO_CHAR($reference, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9')"

    OracleType.TIMESTAMP_WITH_TIME_ZONE,
    OracleType.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
    -> "TO_CHAR($reference, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9TZH:TZM')"

    else -> reference
}

private class OraclePagination(private val limit: Int?, private val offset: Int?) : SqlSelectPagination {
    override val selectParameters: List<Int> = emptyList()
    override val trailingParameters: List<Int> = listOfNotNull(offset, limit)

    override fun renderAfterSelect(markers: List<String>): String = ""

    override fun renderAfterOrderBy(markers: List<String>): String = buildString {
        var index = 0
        if (offset != null) append(" OFFSET ").append(markers[index++]).append(" ROWS")
        if (limit != null) append(" FETCH NEXT ").append(markers[index]).append(" ROWS ONLY")
    }
}

private class OracleDdlRenderer(private val types: OracleTypeRegistry) {
    fun render(dialect: SqlDialect, statement: DdlStatement): RenderedSql = RenderedSql(
        when (statement) {
            is CreateTable -> renderCreateTable(dialect, statement)
            is CreateSequence -> renderOracleCreateSequence(dialect, statement)
            is DropTable -> renderDropTable(dialect, statement)
            is DropSequence -> renderOracleDropSequence(dialect, statement)
            is AlterTable -> renderAlterTable(dialect, statement)
            is CreateIndex -> renderCreateIndex(dialect, statement)
            is DropIndex -> "DROP INDEX ${dialect.quoteQualifiedIdentifier(statement.table.schemaName, statement.name)}"
        },
        emptyList(),
    )

    fun renderDdl(dialect: SqlDialect, statement: DdlStatement): RenderedDdl {
        val primary = render(dialect, statement)
        if (statement is AlterTable) {
            return RenderedDdl(
                requireNotNull(
                    statement.operations.map { render(dialect, statement.singleOperation(it)) }.toNonEmptyListOrNull(),
                ),
            )
        }
        if (statement !is CreateTable) return RenderedDdl.of(primary)
        return RenderedDdl.of(
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
            },
        )
    }

    private fun renderCreateTable(dialect: SqlDialect, statement: CreateTable): String {
        requireAtMostOneIdentityColumn(statement)
        statement.primaryKey?.columns?.forEach(::requireIndexable)
        statement.uniqueConstraints.forEach { unique -> unique.columns.forEach(::requireIndexable) }
        val definitions = statement.columns.map { renderColumn(dialect, it) } + renderTableConstraints(
            statement,
            dialect::quoteIdentifier,
            dialect::quoteTable,
            { value -> if (value) "TRUE" else "FALSE" },
            ::stringLiteral,
            renderForeignKeyAction = { operation, action ->
                when {
                    operation == "UPDATE" -> throw UnsupportedDdlOperation(
                        "Oracle foreign-key ON UPDATE",
                        "Oracle does not support ON UPDATE referential actions",
                    )

                    action == ForeignKeyAction.Cascade || action == ForeignKeyAction.SetNull -> {
                        " ON $operation ${action.sql}"
                    }

                    else -> throw UnsupportedDdlOperation(
                        "Oracle foreign-key action",
                        "$operation ${action.sql} is not supported by Oracle constraint syntax",
                    )
                }
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
        }
    }

    private fun renderDropTable(dialect: SqlDialect, statement: DropTable): String =
        "DROP TABLE ${if (statement.ifExists) "IF EXISTS " else ""}${dialect.quoteTable(statement.table)}"

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "ThrowsCount",
    ) // Exhaustive Oracle ALTER grammar validates unsupported actions.
    private fun renderAlterTable(dialect: SqlDialect, statement: AlterTable): String {
        val table = dialect.quoteTable(statement.table)
        return when (val operation = statement.operation) {
            is AlterTableOperation.AddColumn -> {
                if (operation.column.isIdentity) {
                    throw UnsupportedDdlOperation(
                        "Oracle ADD COLUMN identity",
                        "identity alteration options are intentionally not part of generic ALTER TABLE",
                    )
                }
                "ALTER TABLE $table ADD ${renderColumn(dialect, operation.column)}"
            }

            is AlterTableOperation.DropColumn -> "ALTER TABLE $table DROP COLUMN ${dialect.quoteIdentifier(
                operation.column.name,
            )}"

            is AlterTableOperation.RenameColumn ->
                "ALTER TABLE $table RENAME COLUMN ${dialect.quoteIdentifier(operation.column.name)} TO " +
                    dialect.quoteIdentifier(operation.newName)

            is AlterTableOperation.RenameTable -> "ALTER TABLE $table RENAME TO ${dialect.quoteIdentifier(
                operation.newName,
            )}"

            is AlterTableOperation.AddPrimaryKey -> "ALTER TABLE $table ADD ${renderPrimaryKeyConstraint(
                operation.constraint,
                dialect::quoteIdentifier,
            )}"

            is AlterTableOperation.DropPrimaryKey -> "ALTER TABLE $table DROP CONSTRAINT ${dialect.quoteIdentifier(
                requireNotNull(operation.name) { "Oracle DROP PRIMARY KEY requires the constraint name" },
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
                when {
                    kind == "UPDATE" -> throw UnsupportedDdlOperation(
                        "Oracle foreign-key ON UPDATE",
                        "Oracle does not support ON UPDATE referential actions",
                    )

                    action == dev.quwac.kagiroi.schema.ForeignKeyAction.Cascade ||
                        action == dev.quwac.kagiroi.schema.ForeignKeyAction.SetNull -> " ON $kind ${action.sql}"

                    else -> throw UnsupportedDdlOperation(
                        "Oracle foreign-key action",
                        "$kind ${action.sql} is not supported",
                    )
                }
            }}"

            is AlterTableOperation.AddCheck -> buildString {
                append("ALTER TABLE ").append(table).append(" ADD ")
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

            is AlterTableOperation.AlterColumnType -> "ALTER TABLE $table MODIFY (${dialect.quoteIdentifier(
                operation.column.name,
            )} ${requireNotNull(
                types.sqlNameOf(operation.storageType),
            ) { "Oracle has no physical type mapping for ${operation.storageType.name}" }})"

            is AlterTableOperation.SetDefault -> "ALTER TABLE $table MODIFY (${dialect.quoteIdentifier(
                operation.column.name,
            )} DEFAULT ${renderDefaultExpression(
                operation.column,
                operation.default,
                { value -> if (value) "TRUE" else "FALSE" },
                ::stringLiteral,
                { sequence -> "${dialect.quoteQualifiedIdentifier(sequence.schemaName, sequence.name)}.NEXTVAL" },
                { "SYSTIMESTAMP" },
                dialect::renderFunction,
                dialect::renderCastTarget,
                dialect::renderNumericBinary,
            )})"

            is AlterTableOperation.DropDefault -> "ALTER TABLE $table MODIFY (${dialect.quoteIdentifier(
                operation.column.name,
            )} DEFAULT NULL)"

            is AlterTableOperation.SetNotNull -> "ALTER TABLE $table MODIFY (${dialect.quoteIdentifier(
                operation.column.name,
            )} NOT NULL)"

            is AlterTableOperation.DropNotNull -> "ALTER TABLE $table MODIFY (${dialect.quoteIdentifier(
                operation.column.name,
            )} NULL)"
        }
    }

    private fun renderCreateIndex(dialect: SqlDialect, statement: CreateIndex): String {
        statement.keys.filterIsInstance<ColumnIndexKey>().forEach { key ->
            requireIndexable(key.column)
        }
        return renderSchemaIndex(
            statement.index,
            dialect::quoteIdentifier,
            dialect::quoteTable,
            { index -> dialect.quoteQualifiedIdentifier(index.table.schemaName, index.name) },
            { value -> if (value) "1" else "0" },
            ::stringLiteral,
            dialect::renderFunction,
            dialect::renderCastTarget,
            dialect::renderNumericBinary,
            supportsExpressionKeys = true,
            supportsPredicate = false,
            supportsIncludedColumns = false,
            expressionKeyNeedsParentheses = false,
        )
    }

    private fun renderColumn(dialect: SqlDialect, column: Column<*>): String = buildString {
        val declaration = types.sqlNameOf(column.storageType) ?: throw UnsupportedDdlOperation(
            "Oracle column type",
            "No physical type mapping for ${column.storageType.name}",
        )
        append(dialect.quoteIdentifier(column.name))
        append(' ')
        append(declaration)
        when {
            column.isIdentity -> appendIdentityColumn(dialect, column)
            column.isGenerated -> appendGeneratedColumn(dialect, column)
            else -> appendOracleColumnConstraints(dialect, column, ::requireIndexable)
        }
    }

    private fun StringBuilder.appendIdentityColumn(dialect: SqlDialect, column: Column<*>) {
        if (column.isGenerated) {
            throw UnsupportedDdlOperation("Oracle identity column", "identity cannot be a generated column")
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
        appendOracleColumnConstraints(dialect, column, ::requireIndexable)
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
        append(if (column.generatedColumnStorage == GeneratedColumnStorage.Virtual) "VIRTUAL" else "MATERIALIZED")
        appendOracleColumnConstraints(dialect, column, ::requireIndexable)
    }

    private fun requireIndexable(column: Column<*>) {
        when (types.typeOf(column.storageType)) {
            OracleType.BLOB, OracleType.CLOB, OracleType.NCLOB -> throw UnsupportedDdlOperation(
                "Oracle index",
                "${column.storageType.name} needs an Oracle-specific domain or function-based index",
            )

            OracleType.BOOLEAN -> throw UnsupportedDdlOperation(
                "Oracle BOOLEAN index",
                "Oracle Database 23ai BOOLEAN columns are not indexable",
            )

            else -> Unit
        }
    }

    private fun stringLiteral(value: String): String = "'${value.replace("'", "''")}'"
}

private fun requireAtMostOneIdentityColumn(statement: CreateTable) {
    if (statement.columns.count(Column<*>::isIdentity) > 1) {
        throw UnsupportedDdlOperation(
            "Oracle identity columns",
            "Oracle permits only one identity column per table",
        )
    }
}

private fun renderOracleCreateSequence(dialect: SqlDialect, statement: CreateSequence): String {
    if (statement.ifNotExists) {
        throw UnsupportedDdlOperation("Oracle CREATE SEQUENCE IF NOT EXISTS", "Oracle has no portable equivalent")
    }
    return "CREATE SEQUENCE ${dialect.quoteQualifiedIdentifier(
        statement.sequence.schemaName,
        statement.sequence.name,
    )}"
}

private fun renderOracleDropSequence(dialect: SqlDialect, statement: DropSequence): String {
    if (statement.ifExists) {
        throw UnsupportedDdlOperation("Oracle DROP SEQUENCE IF EXISTS", "Oracle has no portable equivalent")
    }
    return "DROP SEQUENCE ${dialect.quoteQualifiedIdentifier(
        statement.sequence.schemaName,
        statement.sequence.name,
    )}"
}

private fun StringBuilder.appendOracleColumnConstraints(
    dialect: SqlDialect,
    column: Column<*>,
    requireIndexable: (Column<*>) -> Unit,
) {
    column.defaultExpression?.let {
        append(" DEFAULT ")
        append(
            renderDefaultExpression(
                column,
                { value -> if (value) "TRUE" else "FALSE" },
                { value -> "'${value.replace("'", "''")}'" },
                { sequence -> "${dialect.quoteQualifiedIdentifier(sequence.schemaName, sequence.name)}.NEXTVAL" },
                { "(SYSTIMESTAMP)" },
                dialect::renderFunction,
                dialect::renderCastTarget,
                dialect::renderNumericBinary,
            ),
        )
    }
    if (!column.isNullable) append(" NOT NULL")
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
