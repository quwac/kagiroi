package dev.quwac.kagiroi.postgres

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
import dev.quwac.kagiroi.schema.ShortColumnType
import dev.quwac.kagiroi.schema.SqlCastTarget
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

/** Native PostgreSQL column types used by Kagiroi mappings.
 *
 * @property sqlName SQL type name used in DDL.
 * @property objectId PostgreSQL type OID returned by the server.
 */
enum class PostgresType(val sqlName: String, val objectId: Int) {
    /** Value associated with boolean. */
    BOOLEAN("BOOLEAN", 16),

    /** Value associated with bytea. */
    BYTEA("BYTEA", 17),

    /** Value associated with bigint. */
    BIGINT("BIGINT", 20),

    /** Value associated with smallint. */
    SMALLINT("SMALLINT", 21),

    /** Value associated with integer. */
    INTEGER("INTEGER", 23),

    /** Value associated with text. */
    TEXT("TEXT", 25),

    /** Value associated with real. */
    REAL("REAL", 700),

    /** Value associated with double precision. */
    DOUBLE_PRECISION("DOUBLE PRECISION", 701),

    /** Value associated with character varying. */
    CHARACTER_VARYING("CHARACTER VARYING", 1043),

    /** Value associated with date. */
    DATE("DATE", 1082),

    /** Value associated with time. */
    TIME("TIME WITHOUT TIME ZONE", 1083),

    /** Value associated with timestamp. */
    TIMESTAMP("TIMESTAMP WITHOUT TIME ZONE", 1114),

    /** Value associated with timestamp with time zone. */
    TIMESTAMP_WITH_TIME_ZONE("TIMESTAMP WITH TIME ZONE", 1184),

    /** Value associated with numeric. */
    NUMERIC("NUMERIC", 1700),

    /** Value associated with uuid. */
    UUID("UUID", 2950),
}

/** Associates a logical Kotlin column type with its native PostgreSQL type.
 *
 * @property type logical Kotlin column type.
 * @property postgresType corresponding PostgreSQL type.
 */
data class PostgresTypeMapping<T : Any>(val type: ColumnType<T>, val postgresType: PostgresType)

/** An explicit set of PostgreSQL-specific logical type mappings and transport codecs. */
interface PostgresExtension {
    /** Value associated with type mappings. */
    val typeMappings: List<PostgresTypeMapping<*>>

    /** Value associated with type codecs. */
    val typeCodecs: List<SqlTypeCodec<*, *>>
}

/** PostgreSQL mappings for schema types whose wire representation needs an explicit codec. */
object PostgresStandardTypes : PostgresExtension {
    override val typeMappings: List<PostgresTypeMapping<*>> = listOf(
        PostgresTypeMapping(InstantColumnType, PostgresType.TIMESTAMP_WITH_TIME_ZONE),
        PostgresTypeMapping(DurationColumnType, PostgresType.TEXT),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(
            type = InstantColumnType,
            storageType = StringColumnType,
            encode = Instant::toPostgresTimestampWithTimeZone,
            decode = { Instant.parse(it.postgresTimestampWithTimeZoneToIso()) },
        ),
        SqlTypeCodec(
            type = DurationColumnType,
            storageType = StringColumnType,
            encode = Duration::toIsoString,
            decode = Duration::parseIsoString,
        ),
    )
}

/** Resolves native PostgreSQL types without treating transport strings as TEXT storage. */
class PostgresTypeRegistry(extensions: Iterable<PostgresExtension> = emptyList()) {
    private val extensionMappings = (
        listOf(
            PostgresStandardTypes,
        ) + extensions
        ).flatMap(PostgresExtension::typeMappings)

    init {
        extensionMappings.forEachIndexed { index, mapping ->
            require(extensionMappings.take(index).none { it.type === mapping.type }) {
                "PostgreSQL type mapping is registered more than once: ${mapping.type.name}"
            }
        }
    }

    /** Provides the type of result. */
    fun typeOf(type: ColumnType<*>): PostgresType? = builtInType(type)
        ?: extensionMappings.firstOrNull { it.type === type }?.postgresType
        ?: (type as? MappedColumnType<*, *>)?.underlyingType?.let(::typeOf)

    internal fun sqlNameOf(type: ColumnType<*>): String? = when (type) {
        is PostgresColumnType<*, *> -> type.sqlDeclaration
        is MappedColumnType<*, *> -> sqlNameOf(type.underlyingType)
        else -> typeOf(type)?.sqlName
    }

    /** PostgreSQL accepts these native type names in both DDL and CAST contexts. */
    internal fun castTargetName(target: SqlCastTarget<*>): String =
        (typeOf(target.type) ?: typeOf(target.storageType))?.sqlName
            ?: throw UnsupportedCapability("cast-target:${target.type.name}")
}

private fun builtInType(type: ColumnType<*>): PostgresType? = when (type) {
    StringColumnType -> PostgresType.TEXT
    BooleanColumnType -> PostgresType.BOOLEAN
    ByteColumnType, ShortColumnType, UByteColumnType -> PostgresType.SMALLINT
    IntColumnType, UShortColumnType -> PostgresType.INTEGER
    LongColumnType, UIntColumnType -> PostgresType.BIGINT
    ULongColumnType -> PostgresType.NUMERIC
    FloatColumnType -> PostgresType.REAL
    DoubleColumnType -> PostgresType.DOUBLE_PRECISION
    ByteArrayColumnType -> PostgresType.BYTEA
    UuidColumnType -> PostgresType.UUID
    else -> null
}

internal fun String.postgresTimestampWithTimeZoneToIso(): String {
    val dateTime = replaceFirst(' ', 'T')
    val offsetStart = dateTime.indexOfLast { it == '+' || it == '-' }.takeIf { it > ISO_DATE_LENGTH }
        ?: return dateTime
    val offset = dateTime.substring(offsetStart)
    return when {
        offset.length == OFFSET_HOUR_LENGTH -> dateTime + ":00"

        offset.length == COMPACT_OFFSET_LENGTH && ':' !in offset -> dateTime.substring(0, offsetStart) +
            offset.substring(0, OFFSET_HOUR_LENGTH) + ":" + offset.substring(OFFSET_HOUR_LENGTH)

        else -> dateTime
    }
}

internal fun Instant.toPostgresTimestampWithTimeZone(): String =
    toString().requirePostgresMicrosecondPrecision("TIMESTAMP WITH TIME ZONE")

private fun String.requirePostgresMicrosecondPrecision(typeName: String): String {
    val fraction = substringAfterLast('.', missingDelimiterValue = "").takeWhile(Char::isDigit)
    require(fraction.length <= MICROSECOND_DIGITS) {
        "$typeName cannot represent fractional seconds beyond microseconds"
    }
    return this
}

private const val ISO_DATE_LENGTH = 10
private const val OFFSET_HOUR_LENGTH = 3
private const val COMPACT_OFFSET_LENGTH = 5
private const val MICROSECOND_DIGITS = 6
