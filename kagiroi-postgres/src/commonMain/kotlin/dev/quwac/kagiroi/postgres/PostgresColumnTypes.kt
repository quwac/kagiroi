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
    /** Value associated with boolean. */
    val boolean: PostgresColumnType<Boolean, Boolean> = scalar("PostgreSQL BOOLEAN", BooleanColumnType, "BOOLEAN")

    /** Value associated with small int. */
    val smallInt: PostgresColumnType<Short, Short> = scalar("PostgreSQL SMALLINT", ShortColumnType, "SMALLINT")

    /** Value associated with integer. */
    val integer: PostgresColumnType<Int, Int> = scalar("PostgreSQL INTEGER", IntColumnType, "INTEGER")

    /** Value associated with big int. */
    val bigInt: PostgresColumnType<Long, Long> = scalar("PostgreSQL BIGINT", LongColumnType, "BIGINT")

    /** PostgreSQL `SMALLSERIAL`; it creates a sequence-backed default without adding a generic key API. */
    val smallSerial: PostgresColumnType<Short, Short> = scalar("PostgreSQL SMALLSERIAL", ShortColumnType, "SMALLSERIAL")

    /** PostgreSQL `SERIAL`; it creates a sequence-backed default without adding a generic key API. */
    val serial: PostgresColumnType<Int, Int> = scalar("PostgreSQL SERIAL", IntColumnType, "SERIAL")

    /** PostgreSQL `BIGSERIAL`; it creates a sequence-backed default without adding a generic key API. */
    val bigSerial: PostgresColumnType<Long, Long> = scalar("PostgreSQL BIGSERIAL", LongColumnType, "BIGSERIAL")

    /** Value associated with real. */
    val real: PostgresColumnType<Float, Float> = scalar("PostgreSQL REAL", FloatColumnType, "REAL")

    /** Value associated with double precision. */
    val doublePrecision: PostgresColumnType<Double, Double> = scalar(
        "PostgreSQL DOUBLE PRECISION",
        DoubleColumnType,
        "DOUBLE PRECISION",
    )

    /** Value associated with text. */
    val text: PostgresColumnType<String, String> = text("TEXT")

    /** PostgreSQL's internal single-byte catalog character type, distinct from `CHAR(1)`. */
    val catalogCharacter: PostgresColumnType<String, String> = text("\"char\"")

    /** PostgreSQL's internal object-name type; its server maximum is configuration-dependent. */
    val name: PostgresColumnType<String, String> = text("NAME")

    /** Value associated with bytea. */
    val bytea: PostgresColumnType<ByteArray, ByteArray> = scalar("PostgreSQL BYTEA", ByteArrayColumnType, "BYTEA")

    /** Value associated with uuid. */
    val uuid: PostgresColumnType<kotlin.uuid.Uuid, kotlin.uuid.Uuid> = scalar(
        "PostgreSQL UUID",
        UuidColumnType,
        "UUID",
    )

    /** Value associated with date. */
    val date: PostgresColumnType<String, String> = text("DATE")

    /** Value associated with time. */
    val time: PostgresColumnType<String, String> = text("TIME WITHOUT TIME ZONE")

    /** Value associated with time with time zone. */
    val timeWithTimeZone: PostgresColumnType<String, String> = text("TIME WITH TIME ZONE")

    /** Value associated with timestamp. */
    val timestamp: PostgresColumnType<String, String> = text("TIMESTAMP WITHOUT TIME ZONE")

    /** Value associated with timestamp with time zone. */
    val timestampWithTimeZone: PostgresColumnType<String, String> = text("TIMESTAMP WITH TIME ZONE")

    /** Value associated with interval. */
    val interval: PostgresColumnType<String, String> = text("INTERVAL")

    /** Value associated with money. */
    val money: PostgresColumnType<String, String> = text("MONEY")

    /** Value associated with json. */
    val json: PostgresColumnType<String, String> = text("JSON")

    /** Value associated with jsonb. */
    val jsonb: PostgresColumnType<String, String> = text("JSONB")

    /** Value associated with json path. */
    val jsonPath: PostgresColumnType<String, String> = text("JSONPATH")

    /** Value associated with inet. */
    val inet: PostgresColumnType<String, String> = text("INET")

    /** Value associated with cidr. */
    val cidr: PostgresColumnType<String, String> = text("CIDR")

    /** Value associated with macaddr. */
    val macaddr: PostgresColumnType<String, String> = text("MACADDR")

    /** Value associated with macaddr8. */
    val macaddr8: PostgresColumnType<String, String> = text("MACADDR8")

    /** Value associated with point. */
    val point: PostgresColumnType<String, String> = text("POINT")

    /** Value associated with line. */
    val line: PostgresColumnType<String, String> = text("LINE")

    /** Value associated with line segment. */
    val lineSegment: PostgresColumnType<String, String> = text("LSEG")

    /** Value associated with box. */
    val box: PostgresColumnType<String, String> = text("BOX")

    /** Value associated with path. */
    val path: PostgresColumnType<String, String> = text("PATH")

    /** Value associated with polygon. */
    val polygon: PostgresColumnType<String, String> = text("POLYGON")

    /** Value associated with circle. */
    val circle: PostgresColumnType<String, String> = text("CIRCLE")

    /** Value associated with text search query. */
    val textSearchQuery: PostgresColumnType<String, String> = text("TSQUERY")

    /** Value associated with text search vector. */
    val textSearchVector: PostgresColumnType<String, String> = text("TSVECTOR")

    /** Value associated with xml. */
    val xml: PostgresColumnType<String, String> = text("XML")

    /** Value associated with log sequence number. */
    val logSequenceNumber: PostgresColumnType<String, String> = text("PG_LSN")

    /** Value associated with transaction snapshot. */
    val transactionSnapshot: PostgresColumnType<String, String> = text("PG_SNAPSHOT")

    /** Deprecated PostgreSQL snapshot type retained for existing schemas. Prefer [transactionSnapshot]. */
    val txidSnapshot: PostgresColumnType<String, String> = text("TXID_SNAPSHOT")

    /** PostgreSQL object and transaction identifier types retain their exact unsigned textual transport. */
    val oid: PostgresColumnType<UInt, UInt> = scalar("PostgreSQL OID", UIntColumnType, "OID")

    /** Value associated with xid. */
    val xid: PostgresColumnType<UInt, UInt> = scalar("PostgreSQL XID", UIntColumnType, "XID")

    /** Value associated with cid. */
    val cid: PostgresColumnType<UInt, UInt> = scalar("PostgreSQL CID", UIntColumnType, "CID")

    /** Value associated with xid8. */
    val xid8: PostgresColumnType<ULong, ULong> = scalar("PostgreSQL XID8", ULongColumnType, "XID8")

    /** Value associated with tuple id. */
    val tupleId: PostgresColumnType<String, String> = text("TID")

    /** Value associated with int2 vector. */
    val int2Vector: PostgresColumnType<String, String> = text("INT2VECTOR")

    /** Value associated with oid vector. */
    val oidVector: PostgresColumnType<String, String> = text("OIDVECTOR")

    /** Value associated with acl item. */
    val aclItem: PostgresColumnType<String, String> = text("ACLITEM")

    /** Value associated with reg class. */
    val regClass: PostgresColumnType<String, String> = text("REGCLASS")

    /** Value associated with reg collation. */
    val regCollation: PostgresColumnType<String, String> = text("REGCOLLATION")

    /** Value associated with reg config. */
    val regConfig: PostgresColumnType<String, String> = text("REGCONFIG")

    /** Value associated with reg dictionary. */
    val regDictionary: PostgresColumnType<String, String> = text("REGDICTIONARY")

    /** Value associated with reg namespace. */
    val regNamespace: PostgresColumnType<String, String> = text("REGNAMESPACE")

    /** Value associated with reg oper. */
    val regOper: PostgresColumnType<String, String> = text("REGOPER")

    /** Value associated with reg operator. */
    val regOperator: PostgresColumnType<String, String> = text("REGOPERATOR")

    /** Value associated with reg procedure. */
    val regProcedure: PostgresColumnType<String, String> = text("REGPROCEDURE")

    /** Value associated with reg proc. */
    val regProc: PostgresColumnType<String, String> = text("REGPROC")

    /** Value associated with reg role. */
    val regRole: PostgresColumnType<String, String> = text("REGROLE")

    /** Value associated with reg type. */
    val regType: PostgresColumnType<String, String> = text("REGTYPE")

    /** Value associated with int4 range. */
    val int4Range: PostgresColumnType<String, String> = text("INT4RANGE")

    /** Value associated with int8 range. */
    val int8Range: PostgresColumnType<String, String> = text("INT8RANGE")

    /** Value associated with numeric range. */
    val numericRange: PostgresColumnType<String, String> = text("NUMRANGE")

    /** Value associated with timestamp range. */
    val timestampRange: PostgresColumnType<String, String> = text("TSRANGE")

    /** Value associated with timestamp with time zone range. */
    val timestampWithTimeZoneRange: PostgresColumnType<String, String> = text("TSTZRANGE")

    /** Value associated with date range. */
    val dateRange: PostgresColumnType<String, String> = text("DATERANGE")

    /** Value associated with int4 multi range. */
    val int4MultiRange: PostgresColumnType<String, String> = text("INT4MULTIRANGE")

    /** Value associated with int8 multi range. */
    val int8MultiRange: PostgresColumnType<String, String> = text("INT8MULTIRANGE")

    /** Value associated with numeric multi range. */
    val numericMultiRange: PostgresColumnType<String, String> = text("NUMMULTIRANGE")

    /** Value associated with timestamp multi range. */
    val timestampMultiRange: PostgresColumnType<String, String> = text("TSMULTIRANGE")

    /** Value associated with timestamp with time zone multi range. */
    val timestampWithTimeZoneMultiRange: PostgresColumnType<String, String> = text("TSTZMULTIRANGE")

    /** Value associated with date multi range. */
    val dateMultiRange: PostgresColumnType<String, String> = text("DATEMULTIRANGE")

    /** Provides the character result. */
    fun character(length: Int): PostgresColumnType<String, String> = text("CHARACTER(${positive(length, "length")})")

    /** Provides the varchar result. */
    fun varchar(): PostgresColumnType<String, String> = text("CHARACTER VARYING")

    /** Provides the varchar result. */
    fun varchar(maxLength: Int): PostgresColumnType<String, String> =
        text("CHARACTER VARYING(${positive(maxLength, "maxLength")})")

    /** Provides the bit result. */
    fun bit(length: Int): PostgresColumnType<String, String> = text("BIT(${positive(length, "length")})")

    /** Provides the varbit result. */
    fun varbit(maxLength: Int? = null): PostgresColumnType<String, String> = text(
        maxLength?.let { "BIT VARYING(${positive(it, "maxLength")})" } ?: "BIT VARYING",
    )

    /** `DATE` with an explicit application mapping over its textual transport. */
    fun <T : Any> date(mapping: ColumnMapping<T, String>): PostgresColumnType<T, String> =
        mappedText(date.sqlDeclaration, mapping)

    /** Provides the time result. */
    fun time(fractionalSecondsPrecision: Int): PostgresColumnType<String, String> =
        text("TIME(${fractionalSeconds(fractionalSecondsPrecision)}) WITHOUT TIME ZONE")

    /** `TIME WITHOUT TIME ZONE(p)` with an explicit application mapping over its textual transport. */
    fun <T : Any> time(
        fractionalSecondsPrecision: Int,
        mapping: ColumnMapping<T, String>,
    ): PostgresColumnType<T, String> = mappedText(time(fractionalSecondsPrecision).sqlDeclaration, mapping)

    /** Provides the time with time zone result. */
    fun timeWithTimeZone(fractionalSecondsPrecision: Int): PostgresColumnType<String, String> =
        text("TIME(${fractionalSeconds(fractionalSecondsPrecision)}) WITH TIME ZONE")

    /** Provides the timestamp result. */
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

    /** Provides the interval result. */
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

    /** Provides the numeric result. */
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

    /** Provides the array result. */
    fun array(element: PostgresColumnType<*, *>): PostgresColumnType<String, String> =
        text("${element.sqlDeclaration}[]")

    /** PostgreSQL array transport uses its documented text input/output grammar. */
    fun <T : Any> array(
        element: PostgresColumnType<*, *>,
        mapping: ColumnMapping<T, String>,
    ): PostgresColumnType<T, String> = mappedText("${element.sqlDeclaration}[]", mapping)

    /** Provides the named text result. */
    fun namedText(typeName: String): PostgresColumnType<String, String> = text(quotedTypeName(null, typeName))

    /** Provides the named text result. */
    fun namedText(schemaName: String, typeName: String): PostgresColumnType<String, String> =
        text(quotedTypeName(schemaName, typeName))

    /** Provides the named result. */
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
    /** Value associated with year. */
    YEAR("YEAR", false),

    /** Value associated with month. */
    MONTH("MONTH", false),

    /** Value associated with day. */
    DAY("DAY", false),

    /** Value associated with hour. */
    HOUR("HOUR", false),

    /** Value associated with minute. */
    MINUTE("MINUTE", false),

    /** Value associated with second. */
    SECOND("SECOND", true),

    /** Value associated with year to month. */
    YEAR_TO_MONTH("YEAR TO MONTH", false),

    /** Value associated with day to hour. */
    DAY_TO_HOUR("DAY TO HOUR", false),

    /** Value associated with day to minute. */
    DAY_TO_MINUTE("DAY TO MINUTE", false),

    /** Value associated with day to second. */
    DAY_TO_SECOND("DAY TO SECOND", true),

    /** Value associated with hour to minute. */
    HOUR_TO_MINUTE("HOUR TO MINUTE", false),

    /** Value associated with hour to second. */
    HOUR_TO_SECOND("HOUR TO SECOND", true),

    /** Value associated with minute to second. */
    MINUTE_TO_SECOND("MINUTE TO SECOND", true),
}

private const val POSTGRES_MAX_FRACTIONAL_SECONDS_PRECISION = 6
private const val POSTGRES_MAX_NUMERIC_PRECISION = 1_000
private const val POSTGRES_MIN_NUMERIC_SCALE = -1_000
private const val POSTGRES_MAX_NUMERIC_SCALE = 1_000
