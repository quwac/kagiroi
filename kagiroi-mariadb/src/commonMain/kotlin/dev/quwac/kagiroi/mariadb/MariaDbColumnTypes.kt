package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.mysql.MySqlColumnType
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ColumnMapping
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.ScalarMappedColumnType
import dev.quwac.kagiroi.schema.SchemaTypeArgument
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.columnMapping
import dev.quwac.kagiroi.sql.NonEmptyList
import kotlin.uuid.Uuid

/** A MariaDB physical column type with an explicit scalar transport mapping. */
class MariaDbColumnType<T : Any, S : Any> internal constructor(
    name: String,
    storageType: ColumnType<S>,
    mapping: ColumnMapping<T, S>,
    internal val sqlDeclaration: String,
) : ScalarMappedColumnType<T, S>(name, storageType, mapping),
    StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor(
        id = "kagiroi.mariadb.declared",
        arguments = listOf(SchemaTypeArgument("declaration", sqlDeclaration)),
    )
}

/**
 * MariaDB physical column type factories.
 *
 * MySQL-compatible types deliberately reuse [MySqlTypes] only where their physical
 * declaration and scalar transport are shared. MariaDB-native JSON, UUID, network,
 * vector, and XML declarations remain separate here.
 */
@Suppress("TooManyFunctions") // Exposes the public MariaDB type catalog without hiding it behind raw SQL.
object MariaDbTypes {
    /** Value associated with boolean. */
    val boolean: MySqlColumnType<Boolean, Boolean> get() = MySqlTypes.boolean

    /** Value associated with tiny int. */
    val tinyInt: MySqlColumnType<Byte, Byte> get() = MySqlTypes.tinyInt

    /** Value associated with tiny int unsigned. */
    val tinyIntUnsigned: MySqlColumnType<UByte, UByte> get() = MySqlTypes.tinyIntUnsigned

    /** Value associated with small int. */
    val smallInt: MySqlColumnType<Short, Short> get() = MySqlTypes.smallInt

    /** Value associated with small int unsigned. */
    val smallIntUnsigned: MySqlColumnType<UShort, UShort> get() = MySqlTypes.smallIntUnsigned

    /** Value associated with medium int. */
    val mediumInt: MySqlColumnType<Int, Int> get() = MySqlTypes.mediumInt

    /** Value associated with medium int unsigned. */
    val mediumIntUnsigned: MySqlColumnType<UInt, UInt> get() = MySqlTypes.mediumIntUnsigned

    /** Value associated with integer. */
    val integer: MySqlColumnType<Int, Int> get() = MySqlTypes.integer

    /** Value associated with integer unsigned. */
    val integerUnsigned: MySqlColumnType<UInt, UInt> get() = MySqlTypes.integerUnsigned

    /** Value associated with big int. */
    val bigInt: MySqlColumnType<Long, Long> get() = MySqlTypes.bigInt

    /** Value associated with big int unsigned. */
    val bigIntUnsigned: MySqlColumnType<ULong, ULong> get() = MySqlTypes.bigIntUnsigned

    /** MariaDB preserves the MySQL-compatible `SERIAL` shorthand without making it a generic identity API. */
    val serial: MySqlColumnType<ULong, ULong> get() = MySqlTypes.serial

    /** Value associated with float. */
    val float: MySqlColumnType<Float, Float> get() = MySqlTypes.float

    /** Value associated with double. */
    val double: MySqlColumnType<Double, Double> get() = MySqlTypes.double

    /** Value associated with year. */
    val year: MySqlColumnType<Int, Int> get() = MySqlTypes.year

    /** Value associated with tiny text. */
    val tinyText: MySqlColumnType<String, String> get() = MySqlTypes.tinyText

    /** Value associated with text. */
    val text: MySqlColumnType<String, String> get() = MySqlTypes.text

    /** Value associated with medium text. */
    val mediumText: MySqlColumnType<String, String> get() = MySqlTypes.mediumText

    /** Value associated with long text. */
    val longText: MySqlColumnType<String, String> get() = MySqlTypes.longText

    /** Value associated with tiny blob. */
    val tinyBlob: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.tinyBlob

    /** Value associated with blob. */
    val blob: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.blob

    /** Value associated with medium blob. */
    val mediumBlob: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.mediumBlob

    /** Value associated with long blob. */
    val longBlob: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.longBlob

    /** Value associated with geometry. */
    val geometry: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.geometry

    /** Value associated with point. */
    val point: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.point

    /** Value associated with line string. */
    val lineString: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.lineString

    /** Value associated with polygon. */
    val polygon: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.polygon

    /** Value associated with multi point. */
    val multiPoint: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.multiPoint

    /** Value associated with multi line string. */
    val multiLineString: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.multiLineString

    /** Value associated with multi polygon. */
    val multiPolygon: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.multiPolygon

    /** Value associated with geometry collection. */
    val geometryCollection: MySqlColumnType<ByteArray, ByteArray> get() = MySqlTypes.geometryCollection

    /** A MariaDB-compatible `BINARY(16)` UUID using RFC 4122 network-byte-order bytes. */
    val binaryUuid: MySqlColumnType<Uuid, ByteArray> get() = MySqlTypes.binaryUuid

    /** Value associated with uuid. */
    val uuid: MariaDbColumnType<Uuid, String> = MariaDbColumnType(
        name = "MariaDB UUID",
        storageType = StringColumnType,
        mapping = columnMapping(fromStorage = Uuid::parse, toStorage = Uuid::toString),
        sqlDeclaration = "UUID",
    )

    /** Value associated with char uuid. */
    val charUuid: MariaDbColumnType<Uuid, String> = MariaDbColumnType(
        name = "MariaDB CHAR(36) UUID",
        storageType = StringColumnType,
        mapping = columnMapping(fromStorage = Uuid::parse, toStorage = Uuid::toString),
        sqlDeclaration = "CHAR(36)",
    )

    /**
     * MariaDB `JSON` is validated `LONGTEXT` with text comparison semantics, unlike
     * MySQL's native binary JSON implementation.
     */
    val json: MariaDbColumnType<String, String> = text("JSON")

    /** MariaDB 10.10+ stores and retrieves IPv4 addresses as their canonical text form. */
    val inet4: MariaDbColumnType<String, String> = text("INET4")

    /** MariaDB 10.5+ stores INET6 internally as BINARY(16) but exposes text to clients. */
    val inet6: MariaDbColumnType<String, String> = text("INET6")

    /** MariaDB 12.3+ XML storage is transported as text; it does not validate XML content. */
    val xmlType: MariaDbColumnType<String, String> = text("XMLTYPE")

    /** Provides the bit result. */
    fun bit(length: Int): MySqlColumnType<ByteArray, ByteArray> = MySqlTypes.bit(length)

    /** Provides the decimal result. */
    fun decimal(precision: Int, scale: Int): MariaDbColumnType<String, String> {
        require(precision in 1..MARIADB_MAX_DECIMAL_PRECISION) {
            "MariaDB DECIMAL precision must be between 1 and $MARIADB_MAX_DECIMAL_PRECISION: $precision"
        }
        require(scale in 0..minOf(precision, MARIADB_MAX_DECIMAL_SCALE)) {
            "MariaDB DECIMAL scale must be between 0 and ${minOf(precision, MARIADB_MAX_DECIMAL_SCALE)}: $scale"
        }
        return text("DECIMAL($precision,$scale)")
    }

    /** `NUMERIC` is a MariaDB synonym for `DECIMAL` with the same precision rules. */
    fun numeric(precision: Int, scale: Int): MariaDbColumnType<String, String> = decimal(precision, scale)

    /** Creates a constrained DECIMAL declaration with an explicit lossless application mapping. */
    fun <T : Any> decimal(
        precision: Int,
        scale: Int,
        mapping: ColumnMapping<T, String>,
    ): MariaDbColumnType<T, String> = mappedText(decimal(precision, scale).sqlDeclaration, mapping)

    /** Creates a constrained NUMERIC declaration with an explicit lossless application mapping. */
    fun <T : Any> numeric(
        precision: Int,
        scale: Int,
        mapping: ColumnMapping<T, String>,
    ): MariaDbColumnType<T, String> = decimal(precision, scale, mapping)

    /** `DATE` reuses the MySQL-compatible declaration and textual transport. */
    fun date(): MySqlColumnType<String, String> = MySqlTypes.date()

    /** `DATE` with an explicit application mapping over its shared textual transport. */
    fun <T : Any> date(mapping: ColumnMapping<T, String>): MySqlColumnType<T, String> = MySqlTypes.date(mapping)

    /** Provides the time result. */
    fun time(fractionalSecondsPrecision: Int = 0): MySqlColumnType<String, String> =
        MySqlTypes.time(fractionalSecondsPrecision)

    /** `TIME(fsp)` with an explicit application mapping over its shared textual transport. */
    fun <T : Any> time(
        fractionalSecondsPrecision: Int,
        mapping: ColumnMapping<T, String>,
    ): MySqlColumnType<T, String> = MySqlTypes.time(fractionalSecondsPrecision, mapping)

    /** Provides the date time result. */
    fun dateTime(fractionalSecondsPrecision: Int = 0): MySqlColumnType<String, String> =
        MySqlTypes.dateTime(fractionalSecondsPrecision)

    /** `DATETIME(fsp)` with an explicit application mapping over its shared textual transport. */
    fun <T : Any> dateTime(
        fractionalSecondsPrecision: Int,
        mapping: ColumnMapping<T, String>,
    ): MySqlColumnType<T, String> = MySqlTypes.dateTime(fractionalSecondsPrecision, mapping)

    /** `TIMESTAMP` retains raw text because session-time-zone conversion is server configuration. */
    fun timestamp(fractionalSecondsPrecision: Int = 0): MySqlColumnType<String, String> =
        MySqlTypes.timestamp(fractionalSecondsPrecision)

    /** Provides the char result. */
    fun char(length: Int): MySqlColumnType<String, String> = MySqlTypes.char(length)

    /** Provides the varchar result. */
    fun varchar(maxLength: Int): MySqlColumnType<String, String> = MySqlTypes.varchar(maxLength)

    /** Provides the binary result. */
    fun binary(length: Int): MySqlColumnType<ByteArray, ByteArray> = MySqlTypes.binary(length)

    /** Provides the varbinary result. */
    fun varbinary(maxLength: Int): MySqlColumnType<ByteArray, ByteArray> = MySqlTypes.varbinary(maxLength)

    /** Provides the enum result. */
    fun enum(first: String, vararg remaining: String): MySqlColumnType<String, String> =
        MySqlTypes.enum(first, *remaining)

    /** Provides the enum result. */
    fun enum(labels: NonEmptyList<String>): MySqlColumnType<String, String> = MySqlTypes.enum(labels)

    /** Provides the set result. */
    fun set(first: String, vararg remaining: String): MySqlColumnType<String, String> =
        MySqlTypes.set(first, *remaining)

    /** Provides the set result. */
    fun set(labels: NonEmptyList<String>): MySqlColumnType<String, String> = MySqlTypes.set(labels)

    /**
     * MariaDB 11.7+ vector values are the server's little-endian Float32 bytes.
     * The vector dimension remains part of the DDL declaration and is verified by
     * MariaDB when a parameter is bound.
     */
    fun vector(dimensions: Int): MariaDbColumnType<ByteArray, ByteArray> {
        require(dimensions > 0) { "MariaDB VECTOR dimensions must be positive: $dimensions" }
        return bytes("VECTOR($dimensions)")
    }

    private fun text(declaration: String): MariaDbColumnType<String, String> =
        scalar("MariaDB $declaration", StringColumnType, declaration)

    private fun <T : Any> mappedText(
        declaration: String,
        mapping: ColumnMapping<T, String>,
    ): MariaDbColumnType<T, String> = MariaDbColumnType(
        name = "MariaDB $declaration",
        storageType = StringColumnType,
        mapping = mapping,
        sqlDeclaration = declaration,
    )

    private fun bytes(declaration: String): MariaDbColumnType<ByteArray, ByteArray> =
        scalar("MariaDB $declaration", ByteArrayColumnType, declaration)

    private fun <T : Any> scalar(
        name: String,
        storageType: ColumnType<T>,
        declaration: String,
    ): MariaDbColumnType<T, T> = MariaDbColumnType(
        name = name,
        storageType = storageType,
        mapping = columnMapping(fromStorage = { it }, toStorage = { it }),
        sqlDeclaration = declaration,
    )
}

private const val MARIADB_MAX_DECIMAL_PRECISION = 65
private const val MARIADB_MAX_DECIMAL_SCALE = 38
