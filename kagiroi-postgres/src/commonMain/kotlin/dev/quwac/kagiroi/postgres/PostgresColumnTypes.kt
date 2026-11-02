package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ColumnMapping
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.DoubleColumnType
import dev.quwac.kagiroi.schema.FloatColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.ScalarMappedColumnType
import dev.quwac.kagiroi.schema.SchemaTypeArgument
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.ShortColumnType
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.UIntColumnType
import dev.quwac.kagiroi.schema.ULongColumnType
import dev.quwac.kagiroi.schema.UuidColumnType
import dev.quwac.kagiroi.schema.columnMapping

/**
 * A PostgreSQL physical column type with an explicit scalar transport mapping.
 *
 * The declaration is created only by [PostgresTypes], whose factories validate every
 * parameter and quote user-defined type names before the DDL renderer receives them.
 */
class PostgresColumnType<T : Any, S : Any> internal constructor(
    name: String,
    storageType: ColumnType<S>,
    mapping: ColumnMapping<T, S>,
    internal val sqlDeclaration: String,
) : ScalarMappedColumnType<T, S>(name, storageType, mapping),
    StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor(
        id = "kagiroi.postgres.declared",
        arguments = listOf(SchemaTypeArgument("declaration", sqlDeclaration)),
    )
}

/** PostgreSQL column type factories for native built-ins and named schema types. */
@Suppress("TooManyFunctions") // Mirrors PostgreSQL's public built-in type catalog.
object PostgresTypes {
    val boolean: PostgresColumnType<Boolean, Boolean> = scalar("PostgreSQL BOOLEAN", BooleanColumnType, "BOOLEAN")
    val smallInt: PostgresColumnType<Short, Short> = scalar("PostgreSQL SMALLINT", ShortColumnType, "SMALLINT")
    val integer: PostgresColumnType<Int, Int> = scalar("PostgreSQL INTEGER", IntColumnType, "INTEGER")
    val bigInt: PostgresColumnType<Long, Long> = scalar("PostgreSQL BIGINT", LongColumnType, "BIGINT")

    /** PostgreSQL `SMALLSERIAL`; it creates a sequence-backed default without adding a generic key API. */
    val smallSerial: PostgresColumnType<Short, Short> = scalar("PostgreSQL SMALLSERIAL", ShortColumnType, "SMALLSERIAL")

    /** PostgreSQL `SERIAL`; it creates a sequence-backed default without adding a generic key API. */
    val serial: PostgresColumnType<Int, Int> = scalar("PostgreSQL SERIAL", IntColumnType, "SERIAL")

    /** PostgreSQL `BIGSERIAL`; it creates a sequence-backed default without adding a generic key API. */
    val bigSerial: PostgresColumnType<Long, Long> = scalar("PostgreSQL BIGSERIAL", LongColumnType, "BIGSERIAL")
    val real: PostgresColumnType<Float, Float> = scalar("PostgreSQL REAL", FloatColumnType, "REAL")
    val doublePrecision: PostgresColumnType<Double, Double> = scalar(
        "PostgreSQL DOUBLE PRECISION",
        DoubleColumnType,
        "DOUBLE PRECISION",
    )
    val text: PostgresColumnType<String, String> = text("TEXT")

    /** PostgreSQL's internal single-byte catalog character type, distinct from `CHAR(1)`. */
    val catalogCharacter: PostgresColumnType<String, String> = text("\"char\"")

    /** PostgreSQL's internal object-name type; its server maximum is configuration-dependent. */
    val name: PostgresColumnType<String, String> = text("NAME")
    val bytea: PostgresColumnType<ByteArray, ByteArray> = scalar("PostgreSQL BYTEA", ByteArrayColumnType, "BYTEA")
    val uuid: PostgresColumnType<kotlin.uuid.Uuid, kotlin.uuid.Uuid> = scalar(
        "PostgreSQL UUID",
        UuidColumnType,
        "UUID",
    )

    val date: PostgresColumnType<String, String> = text("DATE")
    val time: PostgresColumnType<String, String> = text("TIME WITHOUT TIME ZONE")
    val timeWithTimeZone: PostgresColumnType<String, String> = text("TIME WITH TIME ZONE")
    val timestamp: PostgresColumnType<String, String> = text("TIMESTAMP WITHOUT TIME ZONE")
    val timestampWithTimeZone: PostgresColumnType<String, String> = text("TIMESTAMP WITH TIME ZONE")
    val interval: PostgresColumnType<String, String> = text("INTERVAL")

    val money: PostgresColumnType<String, String> = text("MONEY")
    val json: PostgresColumnType<String, String> = text("JSON")
    val jsonb: PostgresColumnType<String, String> = text("JSONB")
    val jsonPath: PostgresColumnType<String, String> = text("JSONPATH")
    val inet: PostgresColumnType<String, String> = text("INET")
    val cidr: PostgresColumnType<String, String> = text("CIDR")
    val macaddr: PostgresColumnType<String, String> = text("MACADDR")
    val macaddr8: PostgresColumnType<String, String> = text("MACADDR8")
    val point: PostgresColumnType<String, String> = text("POINT")
    val line: PostgresColumnType<String, String> = text("LINE")
    val lineSegment: PostgresColumnType<String, String> = text("LSEG")
    val box: PostgresColumnType<String, String> = text("BOX")
    val path: PostgresColumnType<String, String> = text("PATH")
    val polygon: PostgresColumnType<String, String> = text("POLYGON")
    val circle: PostgresColumnType<String, String> = text("CIRCLE")
    val textSearchQuery: PostgresColumnType<String, String> = text("TSQUERY")
    val textSearchVector: PostgresColumnType<String, String> = text("TSVECTOR")
    val xml: PostgresColumnType<String, String> = text("XML")
    val logSequenceNumber: PostgresColumnType<String, String> = text("PG_LSN")
    val transactionSnapshot: PostgresColumnType<String, String> = text("PG_SNAPSHOT")

    /** Deprecated PostgreSQL snapshot type retained for existing schemas. Prefer [transactionSnapshot]. */
    val txidSnapshot: PostgresColumnType<String, String> = text("TXID_SNAPSHOT")

    /** PostgreSQL object and transaction identifier types retain their exact unsigned textual transport. */
    val oid: PostgresColumnType<UInt, UInt> = scalar("PostgreSQL OID", UIntColumnType, "OID")
    val xid: PostgresColumnType<UInt, UInt> = scalar("PostgreSQL XID", UIntColumnType, "XID")
    val cid: PostgresColumnType<UInt, UInt> = scalar("PostgreSQL CID", UIntColumnType, "CID")
    val xid8: PostgresColumnType<ULong, ULong> = scalar("PostgreSQL XID8", ULongColumnType, "XID8")
    val tupleId: PostgresColumnType<String, String> = text("TID")
    val int2Vector: PostgresColumnType<String, String> = text("INT2VECTOR")
    val oidVector: PostgresColumnType<String, String> = text("OIDVECTOR")
    val aclItem: PostgresColumnType<String, String> = text("ACLITEM")
    val regClass: PostgresColumnType<String, String> = text("REGCLASS")
    val regCollation: PostgresColumnType<String, String> = text("REGCOLLATION")
    val regConfig: PostgresColumnType<String, String> = text("REGCONFIG")
    val regDictionary: PostgresColumnType<String, String> = text("REGDICTIONARY")
    val regNamespace: PostgresColumnType<String, String> = text("REGNAMESPACE")
    val regOper: PostgresColumnType<String, String> = text("REGOPER")
    val regOperator: PostgresColumnType<String, String> = text("REGOPERATOR")
    val regProcedure: PostgresColumnType<String, String> = text("REGPROCEDURE")
    val regProc: PostgresColumnType<String, String> = text("REGPROC")
    val regRole: PostgresColumnType<String, String> = text("REGROLE")
    val regType: PostgresColumnType<String, String> = text("REGTYPE")

    val int4Range: PostgresColumnType<String, String> = text("INT4RANGE")
    val int8Range: PostgresColumnType<String, String> = text("INT8RANGE")
    val numericRange: PostgresColumnType<String, String> = text("NUMRANGE")
    val timestampRange: PostgresColumnType<String, String> = text("TSRANGE")
    val timestampWithTimeZoneRange: PostgresColumnType<String, String> = text("TSTZRANGE")
    val dateRange: PostgresColumnType<String, String> = text("DATERANGE")
    val int4MultiRange: PostgresColumnType<String, String> = text("INT4MULTIRANGE")
    val int8MultiRange: PostgresColumnType<String, String> = text("INT8MULTIRANGE")
    val numericMultiRange: PostgresColumnType<String, String> = text("NUMMULTIRANGE")
    val timestampMultiRange: PostgresColumnType<String, String> = text("TSMULTIRANGE")
    val timestampWithTimeZoneMultiRange: PostgresColumnType<String, String> = text("TSTZMULTIRANGE")
    val dateMultiRange: PostgresColumnType<String, String> = text("DATEMULTIRANGE")

    fun character(length: Int): PostgresColumnType<String, String> = text("CHARACTER(${positive(length, "length")})")

    fun varchar(): PostgresColumnType<String, String> = text("CHARACTER VARYING")

    fun varchar(maxLength: Int): PostgresColumnType<String, String> =
        text("CHARACTER VARYING(${positive(maxLength, "maxLength")})")

    fun bit(length: Int): PostgresColumnType<String, String> = text("BIT(${positive(length, "length")})")

    fun varbit(maxLength: Int? = null): PostgresColumnType<String, String> = text(
        maxLength?.let { "BIT VARYING(${positive(it, "maxLength")})" } ?: "BIT VARYING",
    )

    /** `DATE` with an explicit application mapping over its textual transport. */
    fun <T : Any> date(mapping: ColumnMapping<T, String>): PostgresColumnType<T, String> =
        mappedText(date.sqlDeclaration, mapping)

    fun time(fractionalSecondsPrecision: Int): PostgresColumnType<String, String> =
        text("TIME(${fractionalSeconds(fractionalSecondsPrecision)}) WITHOUT TIME ZONE")

    /** `TIME WITHOUT TIME ZONE(p)` with an explicit application mapping over its textual transport. */
    fun <T : Any> time(
        fractionalSecondsPrecision: Int,
        mapping: ColumnMapping<T, String>,
    ): PostgresColumnType<T, String> = mappedText(time(fractionalSecondsPrecision).sqlDeclaration, mapping)

    fun timeWithTimeZone(fractionalSecondsPrecision: Int): PostgresColumnType<String, String> =
        text("TIME(${fractionalSeconds(fractionalSecondsPrecision)}) WITH TIME ZONE")

    fun timestamp(fractionalSecondsPrecision: Int): PostgresColumnType<String, String> =
        text("TIMESTAMP(${fractionalSeconds(fractionalSecondsPrecision)}) WITHOUT TIME ZONE")

    /** `TIMESTAMP WITHOUT TIME ZONE(p)` with an explicit application mapping over its textual transport. */
    fun <T : Any> timestamp(
        fractionalSecondsPrecision: Int,
        mapping: ColumnMapping<T, String>,
    ): PostgresColumnType<T, String> = mappedText(timestamp(fractionalSecondsPrecision).sqlDeclaration, mapping)

    /**
     * `TIME WITH TIME ZONE`, `TIMESTAMP WITH TIME ZONE`, and `INTERVAL` retain raw
     * text because their semantics cannot be represented by one local kotlinx-datetime
     * value without an additional application policy.
     */
    fun timestampWithTimeZone(fractionalSecondsPrecision: Int): PostgresColumnType<String, String> =
        text("TIMESTAMP(${fractionalSeconds(fractionalSecondsPrecision)}) WITH TIME ZONE")

    fun interval(
        fields: PostgresIntervalFields? = null,
        fractionalSecondsPrecision: Int? = null,
    ): PostgresColumnType<String, String> {
        require(fractionalSecondsPrecision == null || fields == null || fields.includesSecond) {
            "PostgreSQL INTERVAL precision requires an interval field specification containing SECOND"
        }
        val declaration = buildString {
            append("INTERVAL")
            if (fields != null) {
                append(' ')
                append(fields.sqlName)
            }
            if (fractionalSecondsPrecision != null) {
                append('(')
                append(fractionalSeconds(fractionalSecondsPrecision))
                append(')')
            }
        }
        return text(declaration)
    }

    fun numeric(precision: Int? = null, scale: Int? = null): PostgresColumnType<String, String> {
        require((precision == null) == (scale == null)) {
            "PostgreSQL NUMERIC precision and scale must be specified together"
        }
        return if (precision == null) {
            text("NUMERIC")
        } else {
            require(precision in 1..POSTGRES_MAX_NUMERIC_PRECISION) {
                "PostgreSQL NUMERIC precision must be between 1 and $POSTGRES_MAX_NUMERIC_PRECISION: $precision"
            }
            require(scale in POSTGRES_MIN_NUMERIC_SCALE..POSTGRES_MAX_NUMERIC_SCALE) {
                "PostgreSQL NUMERIC scale must be between $POSTGRES_MIN_NUMERIC_SCALE and " +
                    "$POSTGRES_MAX_NUMERIC_SCALE: $scale"
            }
            text("NUMERIC($precision,$scale)")
        }
    }

    /** Creates a constrained NUMERIC declaration with an explicit lossless application mapping. */
    fun <T : Any> numeric(
        precision: Int,
        scale: Int,
        mapping: ColumnMapping<T, String>,
    ): PostgresColumnType<T, String> = mappedText(numeric(precision, scale).sqlDeclaration, mapping)

    fun array(element: PostgresColumnType<*, *>): PostgresColumnType<String, String> =
        text("${element.sqlDeclaration}[]")

    /** PostgreSQL array transport uses its documented text input/output grammar. */
    fun <T : Any> array(
        element: PostgresColumnType<*, *>,
        mapping: ColumnMapping<T, String>,
    ): PostgresColumnType<T, String> = mappedText("${element.sqlDeclaration}[]", mapping)

    fun namedText(typeName: String): PostgresColumnType<String, String> = text(quotedTypeName(null, typeName))

    fun namedText(schemaName: String, typeName: String): PostgresColumnType<String, String> =
        text(quotedTypeName(schemaName, typeName))

    fun <T : Any, S : Any> named(
        schemaName: String? = null,
        typeName: String,
        storageType: ColumnType<S>,
        mapping: ColumnMapping<T, S>,
    ): PostgresColumnType<T, S> = PostgresColumnType(
        name = "PostgreSQL ${displayName(schemaName, typeName)}",
        storageType = storageType,
        mapping = mapping,
        sqlDeclaration = quotedTypeName(schemaName, typeName),
    )

    private fun text(declaration: String): PostgresColumnType<String, String> =
        scalar("PostgreSQL $declaration", StringColumnType, declaration)

    private fun <T : Any> mappedText(
        declaration: String,
        mapping: ColumnMapping<T, String>,
    ): PostgresColumnType<T, String> = PostgresColumnType(
        name = "PostgreSQL $declaration",
        storageType = StringColumnType,
        mapping = mapping,
        sqlDeclaration = declaration,
    )

    private fun <T : Any> scalar(
        name: String,
        storageType: ColumnType<T>,
        declaration: String,
    ): PostgresColumnType<T, T> = PostgresColumnType(
        name = name,
        storageType = storageType,
        mapping = columnMapping(fromStorage = { it }, toStorage = { it }),
        sqlDeclaration = declaration,
    )

    private fun positive(value: Int, parameter: String): Int {
        require(value > 0) { "PostgreSQL $parameter must be positive: $value" }
        return value
    }

    private fun fractionalSeconds(precision: Int): Int {
        require(precision in 0..POSTGRES_MAX_FRACTIONAL_SECONDS_PRECISION) {
            "PostgreSQL fractional seconds precision must be between 0 and " +
                "$POSTGRES_MAX_FRACTIONAL_SECONDS_PRECISION: $precision"
        }
        return precision
    }

    private fun quotedTypeName(schemaName: String?, typeName: String): String {
        require(typeName.isNotBlank()) { "PostgreSQL type name must not be blank" }
        require(schemaName == null || schemaName.isNotBlank()) { "PostgreSQL type schema must not be blank" }
        return PostgresDialect.quoteQualifiedIdentifier(schemaName, typeName)
    }

    private fun displayName(schemaName: String?, typeName: String): String =
        listOfNotNull(schemaName, typeName).joinToString(".")
}

/** SQL-standard field restrictions accepted by PostgreSQL `INTERVAL` declarations. */
enum class PostgresIntervalFields(internal val sqlName: String, internal val includesSecond: Boolean) {
    YEAR("YEAR", false),
    MONTH("MONTH", false),
    DAY("DAY", false),
    HOUR("HOUR", false),
    MINUTE("MINUTE", false),
    SECOND("SECOND", true),
    YEAR_TO_MONTH("YEAR TO MONTH", false),
    DAY_TO_HOUR("DAY TO HOUR", false),
    DAY_TO_MINUTE("DAY TO MINUTE", false),
    DAY_TO_SECOND("DAY TO SECOND", true),
    HOUR_TO_MINUTE("HOUR TO MINUTE", false),
    HOUR_TO_SECOND("HOUR TO SECOND", true),
    MINUTE_TO_SECOND("MINUTE TO SECOND", true),
}

private const val POSTGRES_MAX_FRACTIONAL_SECONDS_PRECISION = 6
private const val POSTGRES_MAX_NUMERIC_PRECISION = 1_000
private const val POSTGRES_MIN_NUMERIC_SCALE = -1_000
private const val POSTGRES_MAX_NUMERIC_SCALE = 1_000
