@file:Suppress("MagicNumber", "TooManyFunctions")

package dev.quwac.kagiroi.oracle

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
import kotlin.uuid.Uuid

/** Oracle Database 23ai native types used by the Oracle dialect and execution adapter. */
enum class OracleType(internal val sqlName: String) {
    VARCHAR2("VARCHAR2(4000 CHAR)"),
    NVARCHAR2("NVARCHAR2(2000)"),
    CHAR("CHAR(1 CHAR)"),
    NCHAR("NCHAR(1)"),
    CLOB("CLOB"),
    NCLOB("NCLOB"),
    BOOLEAN("BOOLEAN"),
    NUMBER_3("NUMBER(3,0)"),
    NUMBER_5("NUMBER(5,0)"),
    NUMBER_10("NUMBER(10,0)"),
    NUMBER_19("NUMBER(19,0)"),
    NUMBER_20("NUMBER(20,0)"),
    NUMBER("NUMBER"),
    BINARY_FLOAT("BINARY_FLOAT"),
    BINARY_DOUBLE("BINARY_DOUBLE"),
    RAW("RAW(2000)"),
    BLOB("BLOB"),
    RAW_UUID("RAW(16)"),
    DATE("DATE"),
    TIMESTAMP("TIMESTAMP(9)"),
    TIMESTAMP_WITH_TIME_ZONE("TIMESTAMP(9) WITH TIME ZONE"),
    TIMESTAMP_WITH_LOCAL_TIME_ZONE("TIMESTAMP(9) WITH LOCAL TIME ZONE"),
}

/** An explicit mapping from an application type to an Oracle physical type. */
data class OracleTypeMapping<T : Any>(val type: ColumnType<T>, val oracleType: OracleType)

/** A set of optional Oracle type and scalar-codec extensions. */
interface OracleExtension {
    val typeMappings: List<OracleTypeMapping<*>>
    val typeCodecs: List<SqlTypeCodec<*, *>>
}

/** Oracle mappings that have a lossless core scalar transport. */
object OracleStandardTypes : OracleExtension {
    override val typeMappings: List<OracleTypeMapping<*>> = listOf(
        OracleTypeMapping(UuidColumnType, OracleType.RAW_UUID),
        OracleTypeMapping(InstantColumnType, OracleType.TIMESTAMP_WITH_TIME_ZONE),
        OracleTypeMapping(DurationColumnType, OracleType.VARCHAR2),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(InstantColumnType, StringColumnType, Instant::toString, Instant::parse),
        SqlTypeCodec(DurationColumnType, StringColumnType, Duration::toIsoString, Duration::parseIsoString),
    )
}

/** A validated Oracle physical column type. It never changes the generic schema type catalog. */
class OracleColumnType<T : Any, S : Any> internal constructor(
    name: String,
    storageType: ColumnType<S>,
    mapping: ColumnMapping<T, S>,
    internal val oracleType: OracleType,
    internal val sqlDeclaration: String,
) : ScalarMappedColumnType<T, S>(name, storageType, mapping),
    StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor(
        id = "kagiroi.oracle.declared",
        arguments = listOf(SchemaTypeArgument("declaration", sqlDeclaration)),
    )
}

/** Oracle Database 23ai storage declarations. */
object OracleTypes {
    val boolean = scalar("Oracle BOOLEAN", BooleanColumnType, OracleType.BOOLEAN)
    val number3 = scalar("Oracle NUMBER(3,0)", ByteColumnType, OracleType.NUMBER_3)
    val number5 = scalar("Oracle NUMBER(5,0)", ShortColumnType, OracleType.NUMBER_5)
    val number10 = scalar("Oracle NUMBER(10,0)", IntColumnType, OracleType.NUMBER_10)
    val number19 = scalar("Oracle NUMBER(19,0)", LongColumnType, OracleType.NUMBER_19)
    val number20 = scalar("Oracle NUMBER(20,0)", ULongColumnType, OracleType.NUMBER_20)
    val binaryFloat = scalar("Oracle BINARY_FLOAT", FloatColumnType, OracleType.BINARY_FLOAT)
    val binaryDouble = scalar("Oracle BINARY_DOUBLE", DoubleColumnType, OracleType.BINARY_DOUBLE)
    val varchar2 = scalar("Oracle VARCHAR2(4000 CHAR)", StringColumnType, OracleType.VARCHAR2)
    val nVarchar2 = scalar("Oracle NVARCHAR2(2000)", StringColumnType, OracleType.NVARCHAR2)
    val clob = scalar("Oracle CLOB", StringColumnType, OracleType.CLOB)
    val nClob = scalar("Oracle NCLOB", StringColumnType, OracleType.NCLOB)
    val raw = scalar("Oracle RAW(2000)", ByteArrayColumnType, OracleType.RAW)
    val blob = scalar("Oracle BLOB", ByteArrayColumnType, OracleType.BLOB)
    val rawUuid = OracleColumnType(
        name = "Oracle RAW(16) UUID",
        storageType = ByteArrayColumnType,
        mapping = columnMapping(fromStorage = ByteArray::toOracleUuid, toStorage = Uuid::toOracleRaw),
        oracleType = OracleType.RAW_UUID,
        sqlDeclaration = OracleType.RAW_UUID.sqlName,
    )
    val date = scalar("Oracle DATE", StringColumnType, OracleType.DATE)
    val timestamp = scalar("Oracle TIMESTAMP(9)", StringColumnType, OracleType.TIMESTAMP)
    val timestampWithTimeZone = scalar(
        "Oracle TIMESTAMP(9) WITH TIME ZONE",
        StringColumnType,
        OracleType.TIMESTAMP_WITH_TIME_ZONE,
    )
    val timestampWithLocalTimeZone = scalar(
        "Oracle TIMESTAMP(9) WITH LOCAL TIME ZONE",
        StringColumnType,
        OracleType.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
    )

    fun varchar2(maxLength: Int): OracleColumnType<String, String> = text(
        "VARCHAR2(${varchar2Length(maxLength)} CHAR)",
        OracleType.VARCHAR2,
    )

    fun nVarchar2(maxLength: Int): OracleColumnType<String, String> = text(
        "NVARCHAR2(${nVarchar2Length(maxLength)})",
        OracleType.NVARCHAR2,
    )

    fun char(length: Int): OracleColumnType<String, String> = text("CHAR(${charLength(length)} CHAR)", OracleType.CHAR)

    fun nChar(length: Int): OracleColumnType<String, String> = text("NCHAR(${nCharLength(length)})", OracleType.NCHAR)

    fun raw(maxLength: Int): OracleColumnType<ByteArray, ByteArray> = bytes(
        "RAW(${rawLength(maxLength)})",
        OracleType.RAW,
    )

    fun number(precision: Int, scale: Int): OracleColumnType<String, String> {
        require(precision in 1..ORACLE_MAX_NUMBER_PRECISION) {
            "Oracle NUMBER precision must be between 1 and $ORACLE_MAX_NUMBER_PRECISION: $precision"
        }
        require(scale in -ORACLE_MAX_NUMBER_PRECISION..ORACLE_MAX_NUMBER_PRECISION) {
            "Oracle NUMBER scale must be between -$ORACLE_MAX_NUMBER_PRECISION and $ORACLE_MAX_NUMBER_PRECISION: $scale"
        }
        return text("NUMBER($precision,$scale)", OracleType.NUMBER)
    }

    fun <T : Any> number(precision: Int, scale: Int, mapping: ColumnMapping<T, String>): OracleColumnType<T, String> =
        OracleColumnType(
            name = "Oracle ${number(precision, scale).sqlDeclaration}",
            storageType = StringColumnType,
            mapping = mapping,
            oracleType = OracleType.NUMBER,
            sqlDeclaration = number(precision, scale).sqlDeclaration,
        )

    private fun text(declaration: String, type: OracleType) = scalar(
        "Oracle $declaration",
        StringColumnType,
        type,
        declaration,
    )

    private fun bytes(declaration: String, type: OracleType) = scalar(
        "Oracle $declaration",
        ByteArrayColumnType,
        type,
        declaration,
    )

    private fun <T : Any> scalar(
        name: String,
        storageType: ColumnType<T>,
        type: OracleType,
        declaration: String = type.sqlName,
    ): OracleColumnType<T, T> = OracleColumnType(
        name,
        storageType,
        columnMapping(fromStorage = { it }, toStorage = { it }),
        type,
        declaration,
    )

    private fun varchar2Length(value: Int): Int {
        require(value in 1..ORACLE_MAX_VARCHAR2_CHARACTERS) { "Oracle VARCHAR2 length must be 1..4000: $value" }
        return value
    }

    private fun nVarchar2Length(value: Int): Int {
        require(value in 1..ORACLE_MAX_NVARCHAR2_CHARACTERS) { "Oracle NVARCHAR2 length must be 1..2000: $value" }
        return value
    }

    private fun charLength(value: Int): Int {
        require(value in 1..ORACLE_MAX_CHAR_CHARACTERS) { "Oracle CHAR length must be 1..2000: $value" }
        return value
    }

    private fun nCharLength(value: Int): Int {
        require(value in 1..ORACLE_MAX_NCHAR_CHARACTERS) { "Oracle NCHAR length must be 1..1000: $value" }
        return value
    }

    private fun rawLength(value: Int): Int {
        require(value in 1..ORACLE_MAX_RAW_BYTES) { "Oracle RAW length must be 1..2000: $value" }
        return value
    }
}

/** Finds Oracle physical declarations without exposing them through generic schema APIs. */
class OracleTypeRegistry(extensions: Iterable<OracleExtension> = emptyList()) {
    private val mappings = (listOf(OracleStandardTypes) + extensions).flatMap(OracleExtension::typeMappings)

    init {
        mappings.forEachIndexed { index, mapping ->
            require(mappings.take(index).none { it.type === mapping.type }) {
                "Oracle type mapping is registered more than once: ${mapping.type.name}"
            }
        }
    }

    fun typeOf(type: ColumnType<*>): OracleType? = when (type) {
        is OracleColumnType<*, *> -> type.oracleType
        is MappedColumnType<*, *> -> typeOf(type.underlyingType)
        else -> builtInType(type) ?: mappings.firstOrNull { it.type === type }?.oracleType
    }

    internal fun sqlNameOf(type: ColumnType<*>): String? = when (type) {
        is OracleColumnType<*, *> -> type.sqlDeclaration
        is MappedColumnType<*, *> -> sqlNameOf(type.underlyingType)
        else -> typeOf(type)?.sqlName
    }

    /** Oracle CAST uses native data type names selected by its own type registry. */
    internal fun castTargetName(target: SqlCastTarget<*>): String =
        (typeOf(target.type) ?: typeOf(target.storageType))?.sqlName
            ?: throw UnsupportedCapability("cast-target:${target.type.name}")
}

@Suppress("CyclomaticComplexMethod")
private fun builtInType(type: ColumnType<*>): OracleType? = when (type) {
    StringColumnType -> OracleType.VARCHAR2
    BooleanColumnType -> OracleType.BOOLEAN
    ByteColumnType, UByteColumnType -> OracleType.NUMBER_3
    ShortColumnType, UShortColumnType -> OracleType.NUMBER_5
    IntColumnType, UIntColumnType -> OracleType.NUMBER_10
    LongColumnType -> OracleType.NUMBER_19
    ULongColumnType -> OracleType.NUMBER_20
    FloatColumnType -> OracleType.BINARY_FLOAT
    DoubleColumnType -> OracleType.BINARY_DOUBLE
    ByteArrayColumnType -> OracleType.RAW
    else -> null
}

internal fun Uuid.toOracleRaw(): ByteArray {
    val hexadecimal = toString().filterNot { it == '-' }
    return ByteArray(ORACLE_UUID_BYTES) { index ->
        hexadecimal.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toOracleUuid(): Uuid {
    require(size == ORACLE_UUID_BYTES) { "Oracle RAW UUID must contain 16 bytes" }
    val hexadecimal = joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return Uuid.parse(
        "${hexadecimal.substring(0, 8)}-${hexadecimal.substring(8, 12)}-" +
            "${hexadecimal.substring(12, 16)}-${hexadecimal.substring(16, 20)}-${hexadecimal.substring(20)}",
    )
}

private const val ORACLE_MAX_NUMBER_PRECISION = 38
private const val ORACLE_MAX_VARCHAR2_CHARACTERS = 4_000
private const val ORACLE_MAX_NVARCHAR2_CHARACTERS = 2_000
private const val ORACLE_MAX_CHAR_CHARACTERS = 2_000
private const val ORACLE_MAX_NCHAR_CHARACTERS = 1_000
private const val ORACLE_MAX_RAW_BYTES = 2_000
private const val ORACLE_UUID_BYTES = 16
