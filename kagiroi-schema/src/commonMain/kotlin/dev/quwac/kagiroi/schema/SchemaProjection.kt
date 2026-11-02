@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber", "TooManyFunctions")

package dev.quwac.kagiroi.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Version of the public Schema Projection document contract. */
const val SCHEMA_PROJECTION_FORMAT_VERSION: Int = 1

/**
 * A deterministic, machine-readable projection of an explicit [SchemaRegistry].
 *
 * The document describes physical database objects and separate evolution lineage. It deliberately
 * does not contain a physical-schema hash: consumers own the artifact format and integrity policy.
 * It is an in-memory export boundary, not a persisted schema snapshot.
 */
class SchemaProjection internal constructor(private val document: JsonObject) {
    /** Encodes the versioned projection as compact deterministic JSON. */
    fun encode(): String = document.toString()
}

/** Builds a public projection without exposing Kagiroi's runtime schema implementation objects. */
internal fun projectSchema(registry: SchemaRegistry): SchemaProjection {
    registry.validate()
    val tables = registry.tables.sortedWith(tableOrder)
    val sequences = registry.sequences.sortedWith(sequenceOrder)
    val indexes = registry.indexes.sortedWith(indexOrder)
    return SchemaProjection(
        buildJsonObject {
            put("formatVersion", JsonPrimitive(SCHEMA_PROJECTION_FORMAT_VERSION))
            put("schema", physicalSchemaJson(tables, sequences, indexes))
            put("evolution", evolutionJson(tables, indexes))
        },
    )
}

private fun physicalSchemaJson(
    tables: List<Table>,
    sequences: List<Sequence>,
    indexes: List<SchemaIndex>,
): JsonObject = buildJsonObject {
    put("tables", tables.jsonArray(Table::tableJson))
    put("sequences", sequences.jsonArray(Sequence::sequenceJson))
    put("indexes", indexes.jsonArray(SchemaIndex::indexJson))
}

private fun evolutionJson(tables: List<Table>, indexes: List<SchemaIndex>): JsonObject = buildJsonObject {
    put(
        "tableRenames",
        tables.mapNotNull { table ->
            table.renameHistory.takeIf(List<String>::isNotEmpty)?.let { previous ->
                buildJsonObject {
                    put("current", table.referenceJson())
                    put("previous", previous.jsonStrings())
                }
            }
        }.jsonElements(),
    )
    put(
        "columnRenames",
        tables.flatMap { table ->
            table.columns.filterNot(Column<*>::isComputed).mapNotNull { column ->
                column.renameHistory.takeIf(List<String>::isNotEmpty)?.let { previous ->
                    buildJsonObject {
                        put("table", table.referenceJson())
                        put("current", JsonPrimitive(column.name))
                        put("previous", previous.jsonStrings())
                    }
                }
            }
        }.jsonElements(),
    )
    put(
        "indexRenames",
        indexes.mapNotNull { index ->
            index.renameHistory.takeIf(List<String>::isNotEmpty)?.let { previous ->
                buildJsonObject {
                    put("current", index.referenceJson())
                    put("previous", previous.jsonStrings())
                }
            }
        }.jsonElements(),
    )
    put("constraintRenames", JsonArray(emptyList()))
    put("sequenceRenames", JsonArray(emptyList()))
}

private fun Table.tableJson(): JsonObject {
    val physicalColumns = columns.filterNot(Column<*>::isComputed)
    require(physicalColumns.isNotEmpty()) {
        "Application schema table $tableName has no physical columns; logical computed columns are not managed"
    }
    return buildJsonObject {
        putNullableString("schemaName", schemaName)
        put("name", JsonPrimitive(tableName))
        putNullableString("comment", comment)
        put("columns", physicalColumns.jsonArray(Column<*>::columnJson))
        primaryKey?.let { key ->
            put(
                "primaryKey",
                buildJsonObject {
                    putNullableString("name", key.name)
                    put("columns", key.columns.map(Column<*>::name).jsonStrings())
                },
            )
        }
        put(
            "uniqueConstraints",
            uniqueConstraints.map { unique ->
                buildJsonObject {
                    putNullableString("name", unique.name)
                    put("columns", unique.columns.map(Column<*>::name).jsonStrings())
                    putNullableString("comment", unique.comment)
                }
            }.jsonElements(),
        )
        put(
            "foreignKeys",
            foreignKeys.map { foreignKey ->
                buildJsonObject {
                    putNullableString("name", foreignKey.name)
                    put("sourceColumns", foreignKey.sourceColumns.map(Column<*>::name).jsonStrings())
                    put("targetTable", foreignKey.targetTable.referenceJson())
                    put("targetColumns", foreignKey.targetColumns.map(Column<*>::name).jsonStrings())
                    putNullableString("onDelete", foreignKey.onDelete?.projectionName())
                    putNullableString("onUpdate", foreignKey.onUpdate?.projectionName())
                }
            }.jsonElements(),
        )
        put(
            "checks",
            checks.map { check ->
                buildJsonObject {
                    putNullableString("name", check.name)
                    put("expression", check.expression.predicateJson())
                }
            }.jsonElements(),
        )
    }
}

private fun Column<*>.columnJson(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("type", storageType.typeJson())
    put("nullable", JsonPrimitive(isNullable))
    putNullableString("comment", comment)
    put("primaryKey", JsonPrimitive(isPrimaryKey))
    put("unique", JsonPrimitive(isUnique))
    defaultExpression?.let { default -> put("default", default.expression.expressionJson()) }
    if (isIdentity) put("identity", JsonObject(emptyMap()))
    generatedExpression?.let { generated ->
        put(
            "generated",
            buildJsonObject {
                put("expression", generated.expressionJson())
                put("storage", JsonPrimitive(requireNotNull(generatedColumnStorage).projectionName()))
            },
        )
    }
}

private fun Sequence.sequenceJson(): JsonObject = buildJsonObject {
    putNullableString("schemaName", schemaName)
    put("name", JsonPrimitive(name))
}

private fun SchemaIndex.indexJson(): JsonObject = buildJsonObject {
    putNullableString("schemaName", table.schemaName)
    put("name", JsonPrimitive(name))
    put("table", table.referenceJson())
    put("unique", JsonPrimitive(unique))
    put("keys", keys.jsonArray(IndexKey::indexKeyJson))
    predicate?.let { predicate -> put("predicate", predicate.predicateJson()) }
    put("includedColumns", includedColumns.jsonArray(Column<*>::referenceJson))
    putNullableString("comment", comment)
}

private fun IndexKey.indexKeyJson(): JsonObject = when (this) {
    is ColumnIndexKey -> buildJsonObject {
        put("kind", JsonPrimitive("column"))
        put("column", column.referenceJson())
        putNullableString("direction", direction?.projectionName())
    }

    is ExpressionIndexKey -> buildJsonObject {
        put("kind", JsonPrimitive("expression"))
        put("expression", expression.expressionJson())
        putNullableString("direction", direction?.projectionName())
    }
}

private fun BooleanExpression.predicateJson(): JsonObject = when (this) {
    is ValueComparison<*> -> buildJsonObject {
        put("kind", JsonPrimitive("valueComparison"))
        put("expression", column.expressionJson())
        put("operator", JsonPrimitive(operator.projectionName()))
        put("value", storageValue().literalJson())
        put("valueType", column.storageType.typeJson())
    }

    is ExpressionValueComparison<*> -> buildJsonObject {
        put("kind", JsonPrimitive("valueComparison"))
        put("expression", expression.expressionJson())
        put("operator", JsonPrimitive(operator.projectionName()))
        put("value", storageValue().literalJson())
        put("valueType", expression.storageType.typeJson())
    }

    is ExpressionComparison<*> -> buildJsonObject {
        put("kind", JsonPrimitive("expressionComparison"))
        put("left", left.expressionJson())
        put("operator", JsonPrimitive(operator.projectionName()))
        put("right", right.expressionJson())
    }

    is LikeValueComparison<*> -> buildJsonObject {
        put("kind", JsonPrimitive("likeValue"))
        put("expression", expression.expressionJson())
        put("operator", JsonPrimitive(operator.projectionName()))
        put("pattern", JsonPrimitive(pattern))
    }

    is LikeExpressionComparison<*> -> buildJsonObject {
        put("kind", JsonPrimitive("likeExpression"))
        put("expression", expression.expressionJson())
        put("operator", JsonPrimitive(operator.projectionName()))
        put("pattern", pattern.expressionJson())
    }

    is NullSafeComparison<*> -> buildJsonObject {
        put("kind", JsonPrimitive("nullSafeComparison"))
        put("left", left.expressionJson())
        put("distinct", JsonPrimitive(distinct))
        put("right", right.expressionJson())
    }

    is NullSafeValueComparison<*> -> buildJsonObject {
        put("kind", JsonPrimitive("nullSafeComparison"))
        put("left", expression.expressionJson())
        put("distinct", JsonPrimitive(distinct))
        put("right", literalExpressionJson(storageValue(), expression.storageType))
    }

    is NullComparison -> buildJsonObject {
        put("kind", JsonPrimitive("null"))
        put("expression", column.expressionJson())
        put("operator", JsonPrimitive(operator.projectionName()))
    }

    is ExpressionNullComparison -> buildJsonObject {
        put("kind", JsonPrimitive("null"))
        put("expression", expression.expressionJson())
        put("operator", JsonPrimitive(operator.projectionName()))
    }

    is LogicalPredicate -> buildJsonObject {
        put("kind", JsonPrimitive("logical"))
        put("left", left.predicateJson())
        put("operator", JsonPrimitive(operator.projectionName()))
        put("right", right.predicateJson())
    }

    else -> error("Schema predicate is not exportable through the Schema Projection contract: $this")
}

private fun SqlExpression<*>.expressionJson(): JsonObject = when (this) {
    is Column<*> -> expressionJson()

    is RelationColumn<*> -> column.expressionJson()

    is NullableRelationColumn<*> -> column.expressionJson()

    is SqlLiteral<*> -> {
        @Suppress("UNCHECKED_CAST")
        val literal = this as SqlLiteral<Any?>
        literalExpressionJson(literal.toStorageValue(literal.value), storageType)
    }

    is SequenceNextValue -> buildJsonObject {
        put("kind", JsonPrimitive("sequenceNextValue"))
        put("sequence", sequence.referenceJson())
    }

    is SqlFunctionExpression<*> -> buildJsonObject {
        put("kind", JsonPrimitive("function"))
        put("function", JsonPrimitive(function.projectionName()))
        put("arguments", arguments.jsonArray(SqlExpression<*>::expressionJson))
    }

    is NumericBinaryExpression<*> -> binaryExpressionJson(operator.projectionName(), left, right)

    is GeneratedColumnBinaryExpression<*> -> binaryExpressionJson(operator.projectionName(), left, right)

    is LegacyDefaultBinarySqlExpression<*> -> binaryExpressionJson(operator.projectionName(), left, right)

    is CastExpression<*> -> buildJsonObject {
        put("kind", JsonPrimitive("cast"))
        put("expression", expression.expressionJson())
        put("targetType", target.storageType.typeJson())
    }

    is CaseExpression<*> -> buildJsonObject {
        put("kind", JsonPrimitive("case"))
        put(
            "branches",
            branches.map { branch ->
                buildJsonObject {
                    put("condition", branch.condition.predicateJson())
                    put("result", branch.result.expressionJson())
                }
            }.jsonElements(),
        )
        put("elseResult", elseResult.expressionJson())
    }

    is SqlExpressionAlias<*> -> expression.expressionJson()

    is NullableSqlExpression<*> -> expression.expressionJson()

    else -> error("Schema expression is not exportable through the Schema Projection contract: $this")
}

private fun Column<*>.expressionJson(): JsonObject = buildJsonObject {
    put("kind", JsonPrimitive("column"))
    put("column", referenceJson())
}

private fun literalExpressionJson(value: Any?, type: ColumnType<*>): JsonObject = buildJsonObject {
    put("kind", JsonPrimitive("literal"))
    put("value", value.literalJson())
    put("type", type.typeJson())
}

private fun Column<*>.referenceJson(): JsonObject = buildJsonObject {
    put("table", table.referenceJson())
    put("name", JsonPrimitive(name))
}

private fun Table.referenceJson(): JsonObject = buildJsonObject {
    putNullableString("schemaName", schemaName)
    put("name", JsonPrimitive(tableName))
}

private fun Sequence.referenceJson(): JsonObject = buildJsonObject {
    putNullableString("schemaName", schemaName)
    put("name", JsonPrimitive(name))
}

private fun SchemaIndex.referenceJson(): JsonObject = buildJsonObject {
    putNullableString("schemaName", table.schemaName)
    put("name", JsonPrimitive(name))
}

private fun ColumnType<*>.typeJson(): JsonObject {
    val descriptor = schemaTypeDescriptor()
    return buildJsonObject {
        put("id", JsonPrimitive(descriptor.id))
        put(
            "arguments",
            descriptor.arguments.map { argument ->
                buildJsonObject {
                    put("name", JsonPrimitive(argument.name))
                    put("value", JsonPrimitive(argument.value))
                }
            }.jsonElements(),
        )
    }
}

private fun ColumnType<*>.schemaTypeDescriptor(): SchemaTypeDescriptor = when (this) {
    is StableSchemaColumnType -> schemaTypeDescriptor

    is StorageMappedColumnType -> storageType.schemaTypeDescriptor()

    is MappedColumnType<*, *> -> underlyingType.schemaTypeDescriptor()

    is EnumColumnType<*, *> -> underlyingType.schemaTypeDescriptor()

    else -> throw IllegalArgumentException(
        "Column type '$name' has no stable schema type identifier; implement StableSchemaColumnType",
    )
}

private fun Any?.literalJson(): JsonObject = when (this) {
    null -> kindJson("null")

    is Boolean -> kindJson("boolean", "value" to JsonPrimitive(this))

    is String, is Char, is Uuid, is Instant, is Duration -> kindJson(
        "string",
        "value" to JsonPrimitive(toString()),
    )

    is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt, is ULong ->
        kindJson("integer", "value" to JsonPrimitive(toString()))

    is Float, is Double -> kindJson("decimal", "value" to JsonPrimitive(toString()))

    is ByteArray -> kindJson("bytes", "hexadecimal" to JsonPrimitive(toHexadecimal()))

    else -> throw IllegalArgumentException(
        "Schema literal value ${this::class.simpleName} has no stable storage representation; " +
            "map it to a supported scalar before export",
    )
}

private fun binaryExpressionJson(operator: String, left: SqlExpression<*>, right: SqlExpression<*>): JsonObject =
    buildJsonObject {
        put("kind", JsonPrimitive("binary"))
        put("operator", JsonPrimitive(operator))
        put("left", left.expressionJson())
        put("right", right.expressionJson())
    }

private fun kindJson(kind: String, vararg properties: Pair<String, JsonPrimitive>): JsonObject = buildJsonObject {
    put("kind", JsonPrimitive(kind))
    properties.forEach { (name, value) -> put(name, value) }
}

private fun ForeignKeyAction.projectionName(): String = when (this) {
    ForeignKeyAction.Cascade -> "cascade"
    ForeignKeyAction.Restrict -> "restrict"
    ForeignKeyAction.NoAction -> "noAction"
    ForeignKeyAction.SetNull -> "setNull"
    ForeignKeyAction.SetDefault -> "setDefault"
}

private fun GeneratedColumnStorage.projectionName(): String = when (this) {
    GeneratedColumnStorage.Virtual -> "virtual"
    GeneratedColumnStorage.Stored -> "stored"
}

private fun IndexDirection.projectionName(): String = when (this) {
    IndexDirection.Ascending -> "asc"
    IndexDirection.Descending -> "desc"
}

private fun ComparisonOperator.projectionName(): String = when (this) {
    ComparisonOperator.EQUALS -> "eq"
    ComparisonOperator.NOT_EQUALS -> "neq"
    ComparisonOperator.LESS_THAN -> "lt"
    ComparisonOperator.LESS_THAN_OR_EQUALS -> "lte"
    ComparisonOperator.GREATER_THAN -> "gt"
    ComparisonOperator.GREATER_THAN_OR_EQUALS -> "gte"
}

private fun LikeOperator.projectionName(): String = when (this) {
    LikeOperator.LIKE -> "like"
    LikeOperator.NOT_LIKE -> "notLike"
    LikeOperator.ILIKE -> "iLike"
    LikeOperator.NOT_ILIKE -> "notILike"
}

private fun NullOperator.projectionName(): String = when (this) {
    NullOperator.IS_NULL -> "isNull"
    NullOperator.IS_NOT_NULL -> "isNotNull"
}

private fun LogicalOperator.projectionName(): String = when (this) {
    LogicalOperator.AND -> "and"
    LogicalOperator.OR -> "or"
}

private fun NumericBinaryOperator.projectionName(): String = when (this) {
    NumericBinaryOperator.Add -> "add"
    NumericBinaryOperator.Subtract -> "subtract"
    NumericBinaryOperator.Multiply -> "multiply"
    NumericBinaryOperator.Divide -> "divide"
    NumericBinaryOperator.Modulo -> "modulo"
}

private fun GeneratedColumnBinaryOperator.projectionName(): String = when (this) {
    GeneratedColumnBinaryOperator.Add -> "add"
    GeneratedColumnBinaryOperator.Subtract -> "subtract"
    GeneratedColumnBinaryOperator.Multiply -> "multiply"
    GeneratedColumnBinaryOperator.Divide -> "divide"
}

private fun DefaultBinaryOperator.projectionName(): String = when (this) {
    DefaultBinaryOperator.Add -> "add"
    DefaultBinaryOperator.Subtract -> "subtract"
    DefaultBinaryOperator.Multiply -> "multiply"
    DefaultBinaryOperator.Divide -> "divide"
}

private fun SqlFunction.projectionName(): String = when (this) {
    SqlFunction.Lower -> "lower"
    SqlFunction.Upper -> "upper"
    SqlFunction.Length -> "length"
    SqlFunction.Trim -> "trim"
    SqlFunction.LeftTrim -> "leftTrim"
    SqlFunction.RightTrim -> "rightTrim"
    SqlFunction.Substring -> "substring"
    SqlFunction.Replace -> "replace"
    SqlFunction.Concat -> "concat"
    SqlFunction.Coalesce -> "coalesce"
    SqlFunction.NullIf -> "nullIf"
    SqlFunction.Abs -> "abs"
    SqlFunction.Round -> "round"
    SqlFunction.Floor -> "floor"
    SqlFunction.Ceiling -> "ceiling"
    SqlFunction.SquareRoot -> "squareRoot"
    SqlFunction.Power -> "power"
    SqlFunction.CurrentDate -> "currentDate"
    SqlFunction.CurrentTime -> "currentTime"
    SqlFunction.CurrentTimestamp -> "currentTimestamp"
    SqlFunction.Year -> "year"
    SqlFunction.Month -> "month"
    SqlFunction.Day -> "day"
    SqlFunction.Hour -> "hour"
    SqlFunction.Minute -> "minute"
    SqlFunction.Second -> "second"
    SqlFunction.Count -> "count"
    SqlFunction.Sum -> "sum"
    SqlFunction.Average -> "average"
    SqlFunction.Minimum -> "minimum"
    SqlFunction.Maximum -> "maximum"
    SqlFunction.Grouping -> "grouping"
    SqlFunction.RowNumber -> "rowNumber"
    SqlFunction.Rank -> "rank"
    SqlFunction.DenseRank -> "denseRank"
    SqlFunction.PercentRank -> "percentRank"
    SqlFunction.CumeDist -> "cumeDist"
    SqlFunction.Lag -> "lag"
    SqlFunction.Lead -> "lead"
    SqlFunction.FirstValue -> "firstValue"
    SqlFunction.LastValue -> "lastValue"
    SqlFunction.Ntile -> "ntile"
    SqlFunction.NthValue -> "nthValue"
    is SqlFunction.Unsafe -> "unsafe:$name"
}

private fun List<String>.jsonStrings(): JsonArray = buildJsonArray {
    this@jsonStrings.forEach { value -> add(JsonPrimitive(value)) }
}

private fun <T> List<T>.jsonArray(transform: (T) -> JsonObject): JsonArray =
    buildJsonArray { this@jsonArray.forEach { value -> add(transform(value)) } }

private fun List<JsonObject>.jsonElements(): JsonArray = buildJsonArray { this@jsonElements.forEach(::add) }

private fun JsonObjectBuilder.putNullableString(name: String, value: String?) {
    value?.let { put(name, JsonPrimitive(it)) }
}

private fun ByteArray.toHexadecimal(): String = buildString(size * 2) {
    this@toHexadecimal.forEach { value ->
        append(HEX[(value.toInt() ushr 4) and 0x0f])
        append(HEX[value.toInt() and 0x0f])
    }
}

private val tableOrder = compareBy<Table>({ it.schemaName.orEmpty() }, Table::tableName)
private val sequenceOrder = compareBy<Sequence>({ it.schemaName.orEmpty() }, Sequence::name)
private val indexOrder = compareBy<SchemaIndex>({ it.table.schemaName.orEmpty() }, SchemaIndex::name)
private const val HEX = "0123456789abcdef"
