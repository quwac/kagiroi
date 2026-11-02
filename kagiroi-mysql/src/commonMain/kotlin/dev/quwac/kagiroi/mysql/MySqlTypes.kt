package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ByteColumnType
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.DoubleColumnType
import dev.quwac.kagiroi.schema.DurationColumnType
import dev.quwac.kagiroi.schema.FloatColumnType
import dev.quwac.kagiroi.schema.InstantColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.MappedColumnType
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.ShortColumnType
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.UByteColumnType
import dev.quwac.kagiroi.schema.UIntColumnType
import dev.quwac.kagiroi.schema.ULongColumnType
import dev.quwac.kagiroi.schema.UShortColumnType
import dev.quwac.kagiroi.schema.UuidColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sql.UnsupportedCapability
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Native MySQL column types used by Kagiroi mappings. */
enum class MySqlType(val sqlName: String, val protocolType: Int) {
    BOOLEAN("BOOLEAN", 1),
    TINYINT("TINYINT", 1),
    TINYINT_UNSIGNED("TINYINT UNSIGNED", 1),
    SMALLINT("SMALLINT", 2),
    SMALLINT_UNSIGNED("SMALLINT UNSIGNED", 2),
    INTEGER("INT", 3),
    INTEGER_UNSIGNED("INT UNSIGNED", 3),
    FLOAT("FLOAT", 4),
    DOUBLE("DOUBLE", 5),
    TIMESTAMP("TIMESTAMP(6)", 7),
    BIGINT("BIGINT", 8),
    BIGINT_UNSIGNED("BIGINT UNSIGNED", 8),
    DATE("DATE", 10),
    TIME("TIME(6)", 11),
    DATETIME("DATETIME(6)", 12),
    DECIMAL("DECIMAL", 246),
    BLOB("BLOB", 252),
    TEXT("TEXT", 252),
    CHAR_UUID("CHAR(36)", 254),
    BINARY_UUID("BINARY(16)", 254),
}

/** A BINARY(16) UUID storage scalar shared by MySQL-protocol-compatible dialects. */
object MySqlBinaryUuidStorageType : ColumnType<ByteArray>("MySQL-compatible BINARY(16)"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.mysql.binaryUuidStorage")
}

/** Associates a logical Kotlin column type with its native MySQL type. */
data class MySqlTypeMapping<T : Any>(val type: ColumnType<T>, val mySqlType: MySqlType)

/** An explicit set of MySQL-specific logical type mappings and transport codecs. */
interface MySqlExtension {
    val typeMappings: List<MySqlTypeMapping<*>>
    val typeCodecs: List<SqlTypeCodec<*, *>>
}

/** Standard MySQL mappings whose wire representation needs an explicit codec. */
object MySqlStandardTypes : MySqlExtension {
    override val typeMappings: List<MySqlTypeMapping<*>> = listOf(
        MySqlTypeMapping(InstantColumnType, MySqlType.TIMESTAMP),
        MySqlTypeMapping(DurationColumnType, MySqlType.TEXT),
        MySqlTypeMapping(UuidColumnType, MySqlType.CHAR_UUID),
        MySqlTypeMapping(MySqlBinaryUuidStorageType, MySqlType.BINARY_UUID),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(
            type = InstantColumnType,
            storageType = StringColumnType,
            encode = Instant::toMySqlTimestamp,
            decode = { Instant.parse(it.mySqlTimestampToIso()) },
        ),
        SqlTypeCodec(
            type = DurationColumnType,
            storageType = StringColumnType,
            encode = Duration::toIsoString,
            decode = Duration::parseIsoString,
        ),
        SqlTypeCodec(
            type = UuidColumnType,
            storageType = StringColumnType,
            encode = Uuid::toString,
            decode = Uuid::parse,
        ),
        SqlTypeCodec(
            type = MySqlBinaryUuidStorageType,
            storageType = ByteArrayColumnType,
            encode = ByteArray::copyOf,
            decode = ByteArray::copyOf,
        ),
    )
}

/** Resolves native MySQL types without treating transport strings as textual storage. */
class MySqlTypeRegistry(extensions: Iterable<MySqlExtension> = emptyList()) {
    private val extensionMappings = (listOf(MySqlStandardTypes) + extensions).flatMap(MySqlExtension::typeMappings)

    init {
        extensionMappings.forEachIndexed { index, mapping ->
            require(extensionMappings.take(index).none { it.type === mapping.type }) {
                "MySQL type mapping is registered more than once: ${mapping.type.name}"
            }
        }
    }

    fun typeOf(type: ColumnType<*>): MySqlType? = builtInType(type)
        ?: extensionMappings.firstOrNull { it.type === type }?.mySqlType
        ?: (type as? MappedColumnType<*, *>)?.underlyingType?.let(::typeOf)

    internal fun sqlNameOf(type: ColumnType<*>): String? = when (type) {
        is MySqlColumnType<*, *> -> type.sqlDeclaration
        is MappedColumnType<*, *> -> sqlNameOf(type.underlyingType)
        else -> typeOf(type)?.sqlName
    }

    /** MySQL CAST has a deliberately smaller target grammar than MySQL CREATE TABLE. */
    internal fun castTargetName(target: SqlCastTarget<*>): String {
        val type = typeOf(target.type) ?: typeOf(target.storageType)
        return when (type) {
            MySqlType.BOOLEAN,
            MySqlType.TINYINT,
            MySqlType.SMALLINT,
            MySqlType.INTEGER,
            MySqlType.BIGINT,
            -> "SIGNED"

            MySqlType.TINYINT_UNSIGNED,
            MySqlType.SMALLINT_UNSIGNED,
            MySqlType.INTEGER_UNSIGNED,
            MySqlType.BIGINT_UNSIGNED,
            -> "UNSIGNED"

            MySqlType.FLOAT -> "FLOAT"

            MySqlType.DOUBLE -> "DOUBLE"

            MySqlType.DECIMAL -> "DECIMAL"

            MySqlType.BLOB, MySqlType.BINARY_UUID -> "BINARY"

            MySqlType.TEXT, MySqlType.CHAR_UUID -> "CHAR"

            MySqlType.DATE -> "DATE"

            MySqlType.TIME -> "TIME"

            MySqlType.TIMESTAMP, MySqlType.DATETIME -> "DATETIME"

            null -> throw UnsupportedCapability("cast-target:${target.type.name}")
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun builtInType(type: ColumnType<*>): MySqlType? = when (type) {
    StringColumnType -> MySqlType.TEXT
    BooleanColumnType -> MySqlType.BOOLEAN
    ByteColumnType -> MySqlType.TINYINT
    UByteColumnType -> MySqlType.TINYINT_UNSIGNED
    ShortColumnType -> MySqlType.SMALLINT
    UShortColumnType -> MySqlType.SMALLINT_UNSIGNED
    IntColumnType -> MySqlType.INTEGER
    UIntColumnType -> MySqlType.INTEGER_UNSIGNED
    LongColumnType -> MySqlType.BIGINT
    ULongColumnType -> MySqlType.BIGINT_UNSIGNED
    FloatColumnType -> MySqlType.FLOAT
    DoubleColumnType -> MySqlType.DOUBLE
    ByteArrayColumnType -> MySqlType.BLOB
    else -> null
}

internal fun Instant.toMySqlTimestamp(): String =
    toString().removeSuffix("Z").requireMySqlCompatibleMicrosecondPrecision("TIMESTAMP(6)").replaceFirst('T', ' ')

internal fun String.mySqlTimestampToIso(): String = replaceFirst(' ', 'T').let { value ->
    if (value.endsWith("Z")) value else "${value}Z"
}

internal fun String.requireMySqlCompatibleMicrosecondPrecision(typeName: String): String {
    val fraction = substringAfterLast('.', missingDelimiterValue = "")
    require(fraction.length <= MICROSECOND_DIGITS) {
        "$typeName cannot represent fractional seconds beyond microseconds"
    }
    return this
}

private const val MICROSECOND_DIGITS = 6
