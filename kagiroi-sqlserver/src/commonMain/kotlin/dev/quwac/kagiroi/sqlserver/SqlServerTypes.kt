@file:Suppress("TooManyFunctions")

package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ByteColumnType
import dev.quwac.kagiroi.schema.ColumnMapping
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.DoubleColumnType
import dev.quwac.kagiroi.schema.DurationColumnType
import dev.quwac.kagiroi.schema.FloatColumnType
import dev.quwac.kagiroi.schema.InstantColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.MappedColumnType
import dev.quwac.kagiroi.schema.ScalarMappedColumnType
import dev.quwac.kagiroi.schema.SchemaTypeArgument
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
import dev.quwac.kagiroi.schema.columnMapping
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sql.UnsupportedCapability
import kotlin.time.Duration
import kotlin.time.Instant

/** Physical T-SQL type declarations used by built-in and extension mappings.
 *
 * @property sqlName T-SQL type syntax used by DDL.
 * @property textResultTransport whether the driver returns values of this type as text.
 */
enum class SqlServerType(val sqlName: String, val textResultTransport: Boolean = false) {
    /** Value associated with bit. */
    BIT("BIT"),

    /** Value associated with tinyint. */
    TINYINT("TINYINT"),

    /** Value associated with smallint. */
    SMALLINT("SMALLINT"),

    /** Value associated with int. */
    INT("INT"),

    /** Value associated with bigint. */
    BIGINT("BIGINT", textResultTransport = true),

    /** Value associated with real. */
    REAL("REAL"),

    /** Value associated with float. */
    FLOAT("FLOAT"),

    /** Value associated with nvarchar. */
    NVARCHAR("NVARCHAR(MAX)"),

    /** Value associated with varbinary. */
    VARBINARY("VARBINARY(MAX)"),

    /** Value associated with uniqueidentifier. */
    UNIQUEIDENTIFIER("UNIQUEIDENTIFIER"),

    /** Value associated with decimal. */
    DECIMAL("DECIMAL(20,0)", textResultTransport = true),

    /** Value associated with date. */
    DATE("DATE", textResultTransport = true),

    /** Value associated with time. */
    TIME("TIME(7)", textResultTransport = true),

    /** Value associated with datetime2. */
    DATETIME2("DATETIME2(7)", textResultTransport = true),

    /** Value associated with datetimeoffset. */
    DATETIMEOFFSET("DATETIMEOFFSET(7)", textResultTransport = true),
}

/** Associates an application type with its SQL Server physical type and safe transport.
 *
 * @property type logical Kotlin column type.
 * @property sqlServerType corresponding T-SQL type.
 */
data class SqlServerTypeMapping<T : Any>(val type: ColumnType<T>, val sqlServerType: SqlServerType)

/** An explicit SQL Server extension set; optional Kotlin types remain outside kagiroi-core. */
interface SqlServerExtension {
    /** Value associated with type mappings. */
    val typeMappings: List<SqlServerTypeMapping<*>>

    /** Value associated with type codecs. */
    val typeCodecs: List<SqlTypeCodec<*, *>>
}

/** SQL Server mappings included with the dialect but not with Kagiroi core. */
object SqlServerStandardTypes : SqlServerExtension {
    override val typeMappings = listOf(
        SqlServerTypeMapping(InstantColumnType, SqlServerType.DATETIMEOFFSET),
        SqlServerTypeMapping(DurationColumnType, SqlServerType.NVARCHAR),
    )

    override val typeCodecs = listOf(
        SqlTypeCodec(InstantColumnType, StringColumnType, Instant::toString, Instant::parse),
        SqlTypeCodec(DurationColumnType, StringColumnType, Duration::toIsoString, Duration::parseIsoString),
    )
}

/** SQL Server-specific physical type whose scalar transport is still a Kagiroi type. */
class SqlServerColumnType<T : Any, S : Any> internal constructor(
    name: String,
    storageType: ColumnType<S>,
    mapping: ColumnMapping<T, S>,
    internal val sqlDeclaration: String,
    internal val textResultTransport: Boolean = false,
) : ScalarMappedColumnType<T, S>(name, storageType, mapping),
    StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor(
        id = "kagiroi.sqlserver.declared",
        arguments = listOf(SchemaTypeArgument("declaration", sqlDeclaration)),
    )
}

/** SQL Server type factories. String defaults to Unicode NVARCHAR rather than VARCHAR. */
object SqlServerTypes {
    /** Value associated with bit. */
    val bit = scalar("SQL Server BIT", BooleanColumnType, "BIT")

    /** Value associated with tiny int. */
    val tinyInt = scalar("SQL Server TINYINT", UByteColumnType, "TINYINT")

    /** Value associated with small int. */
    val smallInt = scalar("SQL Server SMALLINT", ShortColumnType, "SMALLINT")

    /** Value associated with integer. */
    val integer = scalar("SQL Server INT", IntColumnType, "INT")

    /** Value associated with big int. */
    val bigInt = scalar("SQL Server BIGINT", LongColumnType, "BIGINT", textResultTransport = true)

    /** Value associated with real. */
    val real = scalar("SQL Server REAL", FloatColumnType, "REAL")

    /** Value associated with float. */
    val float = scalar("SQL Server FLOAT", DoubleColumnType, "FLOAT")

    /** Value associated with n varchar. */
    val nVarchar = text("NVARCHAR(MAX)")

    /** Value associated with var binary. */
    val varBinary = scalar("SQL Server VARBINARY", ByteArrayColumnType, "VARBINARY(MAX)")

    /** Value associated with unique identifier. */
    val uniqueIdentifier = scalar("SQL Server UNIQUEIDENTIFIER", UuidColumnType, "UNIQUEIDENTIFIER")

    /** Value associated with date. */
    val date = text("DATE", textResultTransport = true)

    /** Value associated with time. */
    val time = text("TIME(7)", textResultTransport = true)

    /** Value associated with date time2. */
    val dateTime2 = text("DATETIME2(7)", textResultTransport = true)

    /** Value associated with date time offset. */
    val dateTimeOffset = text("DATETIMEOFFSET(7)", textResultTransport = true)

    /** Provides the n varchar result. */
    fun nVarchar(maxLength: Int): SqlServerColumnType<String, String> = text("NVARCHAR(${unicodeLength(maxLength)})")

    /** Provides the varchar result. */
    fun varchar(maxLength: Int): SqlServerColumnType<String, String> = text("VARCHAR(${length(maxLength)})")

    /** Provides the var binary result. */
    fun varBinary(maxLength: Int): SqlServerColumnType<ByteArray, ByteArray> =
        scalar("SQL Server VARBINARY", ByteArrayColumnType, "VARBINARY(${length(maxLength)})")

    /** Provides the time result. */
    fun time(fractionalSecondsPrecision: Int): SqlServerColumnType<String, String> =
        text("TIME(${fractionalSecondsPrecision(fractionalSecondsPrecision)})", textResultTransport = true)

    /** Provides the date time2 result. */
    fun dateTime2(fractionalSecondsPrecision: Int): SqlServerColumnType<String, String> =
        text("DATETIME2(${fractionalSecondsPrecision(fractionalSecondsPrecision)})", textResultTransport = true)

    /** Provides the date time offset result. */
    fun dateTimeOffset(fractionalSecondsPrecision: Int): SqlServerColumnType<String, String> =
        text("DATETIMEOFFSET(${fractionalSecondsPrecision(fractionalSecondsPrecision)})", textResultTransport = true)

    /** Provides the decimal result. */
    fun decimal(precision: Int, scale: Int): SqlServerColumnType<String, String> =
        text(decimalDeclaration(precision, scale), textResultTransport = true)

    /** Provides the numeric result. */
    fun numeric(precision: Int, scale: Int): SqlServerColumnType<String, String> =
        text(numericDeclaration(precision, scale), textResultTransport = true)

    /** Provides the decimal result. */
    fun <T : Any> decimal(
        precision: Int,
        scale: Int,
        mapping: ColumnMapping<T, String>,
    ): SqlServerColumnType<T, String> = mappedText(decimalDeclaration(precision, scale), mapping)

    /** Provides the numeric result. */
    fun <T : Any> numeric(
        precision: Int,
        scale: Int,
        mapping: ColumnMapping<T, String>,
    ): SqlServerColumnType<T, String> = mappedText(numericDeclaration(precision, scale), mapping)

    private fun decimalDeclaration(precision: Int, scale: Int): String =
        "DECIMAL(${decimalPrecision(precision, scale)},$scale)"

    private fun numericDeclaration(precision: Int, scale: Int): String =
        "NUMERIC(${decimalPrecision(precision, scale)},$scale)"

    private fun decimalPrecision(precision: Int, scale: Int): Int {
        require(precision in 1..MAX_DECIMAL_PRECISION) {
            "SQL Server DECIMAL precision must be between 1 and $MAX_DECIMAL_PRECISION: $precision"
        }
        require(scale in 0..precision) { "SQL Server DECIMAL scale must be between 0 and $precision: $scale" }
        return precision
    }

    private fun length(value: Int): Int {
        require(value in 1..MAX_BINARY_OR_NON_UNICODE_LENGTH) { "SQL Server length must be 1..8000: $value" }
        return value
    }

    private fun unicodeLength(value: Int): Int {
        require(value in 1..MAX_UNICODE_LENGTH) { "SQL Server NVARCHAR length must be 1..4000: $value" }
        return value
    }

    private fun fractionalSecondsPrecision(value: Int): Int {
        require(value in 0..MAX_FRACTIONAL_SECONDS_PRECISION) {
            "SQL Server fractional seconds precision must be 0..7: $value"
        }
        return value
    }

    private fun text(declaration: String, textResultTransport: Boolean = false): SqlServerColumnType<String, String> =
        scalar("SQL Server $declaration", StringColumnType, declaration, textResultTransport)

    private fun <T : Any> mappedText(
        declaration: String,
        mapping: ColumnMapping<T, String>,
    ): SqlServerColumnType<T, String> = SqlServerColumnType(
        name = "SQL Server $declaration",
        storageType = StringColumnType,
        mapping = mapping,
        sqlDeclaration = declaration,
        textResultTransport = true,
    )

    private fun <T : Any> scalar(
        name: String,
        storageType: ColumnType<T>,
        declaration: String,
        textResultTransport: Boolean = false,
    ): SqlServerColumnType<T, T> = SqlServerColumnType(
        name,
        storageType,
        columnMapping(fromStorage = { it }, toStorage = { it }),
        declaration,
        textResultTransport,
    )
}

/** Resolves T-SQL declarations without exposing storage transport strings as generic schema types. */
class SqlServerTypeRegistry(extensions: Iterable<SqlServerExtension> = emptyList()) {
    private val extensionMappings = (
        listOf(
            SqlServerStandardTypes,
        ) + extensions
        ).flatMap(SqlServerExtension::typeMappings)

    init {
        extensionMappings.forEachIndexed { index, mapping ->
            require(extensionMappings.take(index).none { it.type === mapping.type }) {
                "SQL Server type mapping is registered more than once: ${mapping.type.name}"
            }
        }
    }

    /** Provides the type of result. */
    fun typeOf(type: ColumnType<*>): SqlServerType? = builtInType(type)
        ?: extensionMappings.firstOrNull { it.type === type }?.sqlServerType
        ?: (type as? MappedColumnType<*, *>)?.underlyingType?.let(::typeOf)

    internal fun sqlNameOf(type: ColumnType<*>): String? = when (type) {
        is SqlServerColumnType<*, *> -> type.sqlDeclaration
        is MappedColumnType<*, *> -> sqlNameOf(type.underlyingType)
        else -> typeOf(type)?.sqlName
    }

    internal fun requiresTextResult(type: ColumnType<*>): Boolean = when (type) {
        is SqlServerColumnType<*, *> -> type.textResultTransport
        is MappedColumnType<*, *> -> requiresTextResult(type.underlyingType)
        else -> typeOf(type)?.textResultTransport == true
    }

    /** T-SQL accepts these native names in CAST; custom target declarations remain opt-in mappings. */
    internal fun castTargetName(target: SqlCastTarget<*>): String =
        sqlNameOf(target.type) ?: sqlNameOf(target.storageType)
            ?: throw UnsupportedCapability("cast-target:${target.type.name}")
}

private fun builtInType(type: ColumnType<*>): SqlServerType? = when (type) {
    StringColumnType -> SqlServerType.NVARCHAR
    BooleanColumnType -> SqlServerType.BIT
    ByteColumnType, ShortColumnType -> SqlServerType.SMALLINT
    UByteColumnType -> SqlServerType.TINYINT
    IntColumnType, UShortColumnType -> SqlServerType.INT
    LongColumnType, UIntColumnType -> SqlServerType.BIGINT
    ULongColumnType -> SqlServerType.DECIMAL
    FloatColumnType -> SqlServerType.REAL
    DoubleColumnType -> SqlServerType.FLOAT
    ByteArrayColumnType -> SqlServerType.VARBINARY
    UuidColumnType -> SqlServerType.UNIQUEIDENTIFIER
    else -> null
}

private const val MAX_DECIMAL_PRECISION = 38
private const val MAX_BINARY_OR_NON_UNICODE_LENGTH = 8000
private const val MAX_UNICODE_LENGTH = 4000
private const val MAX_FRACTIONAL_SECONDS_PRECISION = 7
