package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.mysql.MySqlBinaryUuidStorageType
import dev.quwac.kagiroi.mysql.MySqlColumnType
import dev.quwac.kagiroi.mysql.MySqlCompatibleDdlRenderer
import dev.quwac.kagiroi.mysql.MySqlExtension
import dev.quwac.kagiroi.mysql.MySqlType
import dev.quwac.kagiroi.mysql.quoteMySqlCompatibleIdentifier
import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ByteColumnType
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.DoubleColumnType
import dev.quwac.kagiroi.schema.DurationColumnType
import dev.quwac.kagiroi.schema.FloatColumnType
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.InstantColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.MappedColumnType
import dev.quwac.kagiroi.schema.ShortColumnType
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlExpressionAlias
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlLiteral
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.UByteColumnType
import dev.quwac.kagiroi.schema.UIntColumnType
import dev.quwac.kagiroi.schema.ULongColumnType
import dev.quwac.kagiroi.schema.UShortColumnType
import dev.quwac.kagiroi.schema.UuidColumnType
import dev.quwac.kagiroi.schema.castTarget
import dev.quwac.kagiroi.sql.DdlStatement
import dev.quwac.kagiroi.sql.JoinType
import dev.quwac.kagiroi.sql.LateralJoinType
import dev.quwac.kagiroi.sql.RenderedDdl
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SetOperation
import dev.quwac.kagiroi.sql.SqlDdlDialect
import dev.quwac.kagiroi.sql.SqlGroupingCapability
import dev.quwac.kagiroi.sql.SqlInsertConflictDialect
import dev.quwac.kagiroi.sql.SqlProposedInsertReferenceStyle
import dev.quwac.kagiroi.sql.SqlReplaceDialect
import dev.quwac.kagiroi.sql.SqlSequenceDialect
import dev.quwac.kagiroi.sql.SqlStatementKind
import dev.quwac.kagiroi.sql.SqlWindowCapability
import dev.quwac.kagiroi.sql.SqlWithClausePlacement
import dev.quwac.kagiroi.sql.UnsupportedCapability

private const val NAVIGATION_DEFAULT_ARGUMENT_COUNT = 3

/**
 * MariaDB syntax for the SQL query models supported by kagiroi-sql.
 *
 * This is deliberately a distinct dialect rather than a subtype or alias of MySQL. It selects
 * MariaDB's own physical-type registry while composing the MySQL-compatible DDL renderer only
 * for the generic operations whose syntax is shared.
 */
@Suppress("TooManyFunctions") // One dialect contract intentionally owns all portable rendering decisions.
object MariaDbDialect : SqlDdlDialect, SqlReplaceDialect, SqlInsertConflictDialect, SqlSequenceDialect {
    private val ddlRenderer = MySqlCompatibleDdlRenderer(
        MariaDbTypeRegistry()::sqlNameOf,
        ::mariaDbGeneratedStorageKeyword,
        supportsGeneratedNotNull = false,
        supportsSequences = true,
        supportsExpressionIndexes = false,
        renderCurrentInstant = { "(UTC_TIMESTAMP(6))" },
    )
    private val types = MariaDbTypeRegistry()

    override fun quoteIdentifier(identifier: String): String = quoteMySqlCompatibleIdentifier(identifier)

    override fun parameterMarker(position: Int): String = "?"

    override fun supportsSetOperation(operation: SetOperation): Boolean = true

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = capability in setOf(
        SqlWindowCapability.Ntile,
        SqlWindowCapability.NthValue,
        SqlWindowCapability.PercentRank,
        SqlWindowCapability.CumeDist,
    )

    override fun withClausePlacement(statement: SqlStatementKind): SqlWithClausePlacement = when (statement) {
        SqlStatementKind.Select -> SqlWithClausePlacement.BeforeStatement

        SqlStatementKind.Insert,
        SqlStatementKind.Update,
        SqlStatementKind.Delete,
        -> SqlWithClausePlacement.Unsupported
    }

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun renderNullSafeComparison(distinct: Boolean, left: String, right: String): String =
        if (distinct) "NOT ($left <=> $right)" else "($left <=> $right)"

    override fun renderRecursiveCteAnchorProjection(projection: SqlExpression<*>, reference: String): String =
        types.renderRecursiveCteAnchorProjection(projection, reference)

    override fun supportsJoin(type: JoinType): Boolean = type != JoinType.Full

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String =
        throw UnsupportedCapability(type.unsupportedCapability)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean =
        capability == SqlGroupingCapability.Rollup

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
        val rollup = elements.singleOrNull() as? dev.quwac.kagiroi.sql.Rollup
            ?: throw UnsupportedCapability(SqlGroupingCapability.Rollup.unsupportedCapability)
        return rollup.items.joinToString(", ") { item ->
            val expression = item as? SqlExpression<*>
                ?: throw UnsupportedCapability(SqlGroupingCapability.Composite.unsupportedCapability)
            renderExpression(expression)
        } + " WITH ROLLUP"
    }

    override fun validateGroupingOrder(elements: List<GroupByElement>, order: List<dev.quwac.kagiroi.sql.Order<*>>) {
        if (elements.any { it is dev.quwac.kagiroi.sql.Rollup } && order.isNotEmpty()) {
            throw UnsupportedCapability("grouping-order-by")
        }
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
    ): SqlProposedInsertReferenceStyle = mariaDbConflictReferenceStyle(behavior)

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = renderMariaDbDuplicateKeyUpdate(behavior, renderAssignments)

    override fun validateWindowFunction(function: SqlFunction, argumentCount: Int) {
        if (
            (function == SqlFunction.Lag || function == SqlFunction.Lead) &&
            argumentCount == NAVIGATION_DEFAULT_ARGUMENT_COUNT
        ) {
            throw UnsupportedCapability("window-navigation-default")
        }
    }

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = ddlRenderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT NEXT VALUE FOR ${quoteQualifiedIdentifier(
            sequence.schemaName,
            sequence.name,
        )} AS kagiroi_sequence_value",
        emptyList(),
    )

    /**
     * Adds explicitly selected MySQL-protocol-compatible physical mappings to MariaDB DDL.
     *
     * The optional datetime and BigNumber mappings share transport shapes with MySQL; this
     * method keeps that reuse at the physical-mapping boundary rather than aliasing dialects.
     */
    fun configured(extensions: Iterable<MySqlExtension>): SqlDdlDialect {
        val installed = extensions.toList()
        return if (installed.isEmpty()) this else ConfiguredMariaDbDialect(installed)
    }
}

@Suppress("TooManyFunctions") // Delegates each dialect concern while replacing extension-owned type mappings.
private class ConfiguredMariaDbDialect(extensions: List<MySqlExtension>) :
    SqlDdlDialect,
    SqlReplaceDialect,
    SqlInsertConflictDialect,
    SqlSequenceDialect {
    private val ddlRenderer = MySqlCompatibleDdlRenderer(
        MariaDbTypeRegistry(extensions)::sqlNameOf,
        ::mariaDbGeneratedStorageKeyword,
        supportsGeneratedNotNull = false,
        supportsSequences = true,
        supportsExpressionIndexes = false,
        renderCurrentInstant = { "(UTC_TIMESTAMP(6))" },
    )
    private val types = MariaDbTypeRegistry(extensions)

    override fun quoteIdentifier(identifier: String): String = MariaDbDialect.quoteIdentifier(identifier)

    override fun parameterMarker(position: Int): String = MariaDbDialect.parameterMarker(position)

    override fun supportsSetOperation(operation: SetOperation): Boolean = MariaDbDialect.supportsSetOperation(operation)

    override fun supportsWindow(capability: SqlWindowCapability): Boolean = MariaDbDialect.supportsWindow(capability)

    override fun withClausePlacement(statement: SqlStatementKind): SqlWithClausePlacement =
        MariaDbDialect.withClausePlacement(statement)

    override fun renderCastTarget(target: SqlCastTarget<*>): String = types.castTargetName(target)

    override fun renderRecursiveCteAnchorProjection(projection: SqlExpression<*>, reference: String): String =
        types.renderRecursiveCteAnchorProjection(projection, reference)

    override fun supportsJoin(type: JoinType): Boolean = MariaDbDialect.supportsJoin(type)

    override fun renderLateralJoin(type: LateralJoinType, relation: String): String =
        MariaDbDialect.renderLateralJoin(type, relation)

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean =
        MariaDbDialect.supportsGrouping(capability)

    override fun renderGroupBy(
        elements: List<GroupByElement>,
        renderExpression: (SqlExpression<*>) -> String,
    ): String = MariaDbDialect.renderGroupBy(elements, renderExpression)

    override fun validateGroupingOrder(elements: List<GroupByElement>, order: List<dev.quwac.kagiroi.sql.Order<*>>) =
        MariaDbDialect.validateGroupingOrder(elements, order)

    override fun renderFunctionName(function: SqlFunction): String = MariaDbDialect.renderFunctionName(function)

    override fun renderFunction(function: SqlFunction, arguments: List<String>): String =
        MariaDbDialect.renderFunction(function, arguments)

    override fun proposedInsertReferenceStyle(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    ): SqlProposedInsertReferenceStyle = MariaDbDialect.proposedInsertReferenceStyle(behavior)

    override fun renderInsertConflict(
        behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
        renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
        renderPredicate: (dev.quwac.kagiroi.sql.SqlPredicate) -> String,
    ): String = MariaDbDialect.renderInsertConflict(behavior, renderAssignments, renderPredicate)

    override fun validateWindowFunction(function: SqlFunction, argumentCount: Int) =
        MariaDbDialect.validateWindowFunction(function, argumentCount)

    override fun render(statement: DdlStatement): RenderedSql = ddlRenderer.render(this, statement)

    override fun renderDdl(statement: DdlStatement): RenderedDdl = ddlRenderer.renderDdl(this, statement)

    override fun renderNextValue(sequence: dev.quwac.kagiroi.schema.Sequence): RenderedSql = RenderedSql(
        "SELECT NEXT VALUE FOR ${quoteQualifiedIdentifier(
            sequence.schemaName,
            sequence.name,
        )} AS kagiroi_sequence_value",
        emptyList(),
    )
}

private fun mariaDbConflictReferenceStyle(
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
): SqlProposedInsertReferenceStyle {
    if (behavior !is dev.quwac.kagiroi.sql.DuplicateKeyUpdateBehavior) {
        throw UnsupportedCapability("on-conflict")
    }
    return SqlProposedInsertReferenceStyle.ValuesFunction
}

private fun renderMariaDbDuplicateKeyUpdate(
    behavior: dev.quwac.kagiroi.sql.InsertConflictBehavior,
    renderAssignments: (List<dev.quwac.kagiroi.sql.WriteAssignment>) -> String,
): String {
    val duplicate = behavior as? dev.quwac.kagiroi.sql.DuplicateKeyUpdateBehavior
        ?: throw UnsupportedCapability("on-conflict")
    return "ON DUPLICATE KEY UPDATE ${renderAssignments(duplicate.update.assignments)}"
}

private fun mariaDbGeneratedStorageKeyword(storage: GeneratedColumnStorage): String = when (storage) {
    GeneratedColumnStorage.Virtual -> "VIRTUAL"
    GeneratedColumnStorage.Stored -> "PERSISTENT"
}

private fun MariaDbTypeRegistry.renderRecursiveCteAnchorProjection(
    projection: SqlExpression<*>,
    reference: String,
): String {
    val expression = (projection as? SqlExpressionAlias<*>)?.expression ?: projection
    if (expression !is SqlLiteral<*>) return reference
    return "CAST($reference AS ${castTarget(expression).let(::castTargetName)})"
}

/** MariaDB physical column types used by the built-in Kagiroi mappings. */
enum class MariaDbType(val sqlName: String) {
    BOOLEAN("BOOLEAN"),
    TINYINT("TINYINT"),
    TINYINT_UNSIGNED("TINYINT UNSIGNED"),
    SMALLINT("SMALLINT"),
    SMALLINT_UNSIGNED("SMALLINT UNSIGNED"),
    INTEGER("INT"),
    INTEGER_UNSIGNED("INT UNSIGNED"),
    FLOAT("FLOAT"),
    DOUBLE("DOUBLE"),
    TIMESTAMP("TIMESTAMP(6)"),
    DATE("DATE"),
    TIME("TIME(6)"),
    DATETIME("DATETIME(6)"),
    BIGINT("BIGINT"),
    BIGINT_UNSIGNED("BIGINT UNSIGNED"),
    TEXT("TEXT"),
    DECIMAL("DECIMAL"),
    BLOB("BLOB"),
    UUID("UUID"),
    CHAR_UUID("CHAR(36)"),
    BINARY_UUID("BINARY(16)"),
}

/** Resolves MariaDB physical mappings without declaring a MySQL dialect dependency in the API. */
class MariaDbTypeRegistry(extensions: Iterable<MySqlExtension> = emptyList()) {
    private val extensionMappings = extensions.toList().flatMap(MySqlExtension::typeMappings)

    init {
        extensionMappings.forEachIndexed { index, mapping ->
            require(extensionMappings.take(index).none { it.type === mapping.type }) {
                "MariaDB type mapping is registered more than once: ${mapping.type.name}"
            }
        }
    }

    fun typeOf(type: ColumnType<*>): MariaDbType? = builtInType(type)
        ?: extensionMappings.firstOrNull { it.type === type }?.mySqlType?.toMariaDbCompatibleType()
        ?: (type as? MappedColumnType<*, *>)?.underlyingType?.let(::typeOf)

    internal fun sqlNameOf(column: Column<*>): String? = sqlNameOf(column.storageType)

    private fun sqlNameOf(type: ColumnType<*>): String? = when (type) {
        is MariaDbColumnType<*, *> -> type.sqlDeclaration
        is MySqlColumnType<*, *> -> type.sqlDeclaration.takeIf { type.isMariaDbCompatible }
        is MappedColumnType<*, *> -> sqlNameOf(type.underlyingType)
        else -> typeOf(type)?.sqlName
    }

    /** MariaDB CAST target syntax is intentionally separate from its column-declaration catalog. */
    internal fun castTargetName(target: SqlCastTarget<*>): String {
        val type = typeOf(target.type) ?: typeOf(target.storageType)
        return when (type) {
            MariaDbType.BOOLEAN,
            MariaDbType.TINYINT,
            MariaDbType.SMALLINT,
            MariaDbType.INTEGER,
            MariaDbType.BIGINT,
            -> "SIGNED"

            MariaDbType.TINYINT_UNSIGNED,
            MariaDbType.SMALLINT_UNSIGNED,
            MariaDbType.INTEGER_UNSIGNED,
            MariaDbType.BIGINT_UNSIGNED,
            -> "UNSIGNED"

            MariaDbType.FLOAT -> "FLOAT"

            MariaDbType.DOUBLE -> "DOUBLE"

            MariaDbType.DECIMAL -> "DECIMAL"

            MariaDbType.BLOB, MariaDbType.BINARY_UUID -> "BINARY"

            MariaDbType.TEXT, MariaDbType.UUID, MariaDbType.CHAR_UUID -> "CHAR"

            MariaDbType.DATE -> "DATE"

            MariaDbType.TIME -> "TIME"

            MariaDbType.TIMESTAMP, MariaDbType.DATETIME -> "DATETIME"

            null -> throw UnsupportedCapability("cast-target:${target.type.name}")
        }
    }
}

/** MariaDB maps Instant as a UTC civil value to avoid TIMESTAMP's session-dependent semantics. */
@Suppress("CyclomaticComplexMethod")
private fun builtInType(type: ColumnType<*>): MariaDbType? = when (type) {
    StringColumnType -> MariaDbType.TEXT
    BooleanColumnType -> MariaDbType.BOOLEAN
    ByteColumnType -> MariaDbType.TINYINT
    UByteColumnType -> MariaDbType.TINYINT_UNSIGNED
    ShortColumnType -> MariaDbType.SMALLINT
    UShortColumnType -> MariaDbType.SMALLINT_UNSIGNED
    IntColumnType -> MariaDbType.INTEGER
    UIntColumnType -> MariaDbType.INTEGER_UNSIGNED
    LongColumnType -> MariaDbType.BIGINT
    ULongColumnType -> MariaDbType.BIGINT_UNSIGNED
    FloatColumnType -> MariaDbType.FLOAT
    DoubleColumnType -> MariaDbType.DOUBLE
    ByteArrayColumnType -> MariaDbType.BLOB
    InstantColumnType -> MariaDbType.DATETIME
    DurationColumnType -> MariaDbType.TEXT
    UuidColumnType -> MariaDbType.UUID
    MariaDbCharUuidStorageType -> MariaDbType.CHAR_UUID
    MySqlBinaryUuidStorageType -> MariaDbType.BINARY_UUID
    else -> null
}

private fun MySqlType.toMariaDbCompatibleType(): MariaDbType =
    requireNotNull(MYSQL_COMPATIBLE_TYPE_MAP[this]) { "No MariaDB type for MySQL type $this" }

private val MYSQL_COMPATIBLE_TYPE_MAP: Map<MySqlType, MariaDbType> = mapOf(
    MySqlType.BOOLEAN to MariaDbType.BOOLEAN,
    MySqlType.TINYINT to MariaDbType.TINYINT,
    MySqlType.TINYINT_UNSIGNED to MariaDbType.TINYINT_UNSIGNED,
    MySqlType.SMALLINT to MariaDbType.SMALLINT,
    MySqlType.SMALLINT_UNSIGNED to MariaDbType.SMALLINT_UNSIGNED,
    MySqlType.INTEGER to MariaDbType.INTEGER,
    MySqlType.INTEGER_UNSIGNED to MariaDbType.INTEGER_UNSIGNED,
    MySqlType.FLOAT to MariaDbType.FLOAT,
    MySqlType.DOUBLE to MariaDbType.DOUBLE,
    MySqlType.TIMESTAMP to MariaDbType.TIMESTAMP,
    MySqlType.BIGINT to MariaDbType.BIGINT,
    MySqlType.BIGINT_UNSIGNED to MariaDbType.BIGINT_UNSIGNED,
    MySqlType.DATE to MariaDbType.DATE,
    MySqlType.TIME to MariaDbType.TIME,
    MySqlType.DATETIME to MariaDbType.DATETIME,
    MySqlType.DECIMAL to MariaDbType.DECIMAL,
    MySqlType.BLOB to MariaDbType.BLOB,
    MySqlType.TEXT to MariaDbType.TEXT,
    MySqlType.CHAR_UUID to MariaDbType.CHAR_UUID,
    MySqlType.BINARY_UUID to MariaDbType.BINARY_UUID,
)
