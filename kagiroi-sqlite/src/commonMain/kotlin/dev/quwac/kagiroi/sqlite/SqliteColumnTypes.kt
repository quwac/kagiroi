package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ColumnMapping
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.DoubleColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.ScalarMappedColumnType
import dev.quwac.kagiroi.schema.SchemaTypeArgument
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.columnMapping

/**
 * A SQLite declared column type with an explicit scalar transport mapping.
 *
 * SQLite has five runtime storage classes, rather than a closed catalog of native
 * column types. This type records one of the four declarable storage-class names
 * that Kagiroi can bind and decode losslessly; SQL `NULL` is a value, not a
 * declared column type.
 */
class SqliteColumnType<T : Any, S : Any> internal constructor(
    name: String,
    storageType: ColumnType<S>,
    mapping: ColumnMapping<T, S>,
    internal val sqlDeclaration: String,
) : ScalarMappedColumnType<T, S>(name, storageType, mapping),
    StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor(
        id = "kagiroi.sqlite.declared",
        arguments = listOf(SchemaTypeArgument("declaration", sqlDeclaration)),
    )
}

/**
 * SQLite physical storage-class declarations.
 *
 * `NUMERIC` affinity and STRICT-table `ANY` intentionally do not appear here.
 * Neither guarantees one transport scalar: SQLite can store values with multiple
 * runtime storage classes, while a [ColumnType] must declare one lossless Kotlin
 * representation. A dialect-specific API can add those semantics when it also
 * supplies their conversion contract.
 */
object SqliteTypes {
    val integer: SqliteColumnType<Long, Long> = scalar("SQLite INTEGER", LongColumnType, "INTEGER")
    val real: SqliteColumnType<Double, Double> = scalar("SQLite REAL", DoubleColumnType, "REAL")
    val text: SqliteColumnType<String, String> = scalar("SQLite TEXT", StringColumnType, "TEXT")
    val blob: SqliteColumnType<ByteArray, ByteArray> = scalar("SQLite BLOB", ByteArrayColumnType, "BLOB")

    /**
     * Creates a type with an explicitly selected SQLite storage-class declaration.
     *
     * This is useful for a domain value that is transported through one of
     * SQLite's lossless scalar classes without weakening it to `Any`.
     */
    fun <T : Any, S : Any> mapped(
        declaration: SqliteDeclaredType,
        storageType: ColumnType<S>,
        mapping: ColumnMapping<T, S>,
    ): SqliteColumnType<T, S> = SqliteColumnType(
        name = "SQLite ${declaration.sqlName}",
        storageType = storageType,
        mapping = mapping,
        sqlDeclaration = declaration.sqlName,
    )

    /**
     * Declares an application-defined SQLite type name with an explicit scalar mapping.
     *
     * SQLite accepts arbitrary declared type names and derives an affinity from their text.
     * This factory quotes the name and renders optional numeric attributes structurally, so an
     * application can select an existing convention or extension type without interpolating
     * a type declaration into SQL.
     */
    fun <T : Any, S : Any> named(
        typeName: String,
        storageType: ColumnType<S>,
        mapping: ColumnMapping<T, S>,
        firstParameter: Int? = null,
        secondParameter: Int? = null,
    ): SqliteColumnType<T, S> = SqliteColumnType(
        name = "SQLite $typeName",
        storageType = storageType,
        mapping = mapping,
        sqlDeclaration = namedDeclaration(typeName, firstParameter, secondParameter),
    )

    /** Declares a named SQLite type transported as text. */
    fun namedText(
        typeName: String,
        firstParameter: Int? = null,
        secondParameter: Int? = null,
    ): SqliteColumnType<String, String> = named(
        typeName = typeName,
        storageType = StringColumnType,
        mapping = columnMapping(fromStorage = { it }, toStorage = { it }),
        firstParameter = firstParameter,
        secondParameter = secondParameter,
    )

    private fun <T : Any> scalar(
        name: String,
        storageType: ColumnType<T>,
        declaration: String,
    ): SqliteColumnType<T, T> = SqliteColumnType(
        name = name,
        storageType = storageType,
        mapping = columnMapping(fromStorage = { it }, toStorage = { it }),
        sqlDeclaration = declaration,
    )

    private fun namedDeclaration(typeName: String, firstParameter: Int?, secondParameter: Int?): String {
        require(typeName.isNotBlank()) { "SQLite type name must not be blank" }
        require(typeName.none { it == '\u0000' }) { "SQLite type name must not contain NUL" }
        require(secondParameter == null || firstParameter != null) {
            "SQLite second type parameter requires a first parameter"
        }
        require(firstParameter == null || firstParameter >= 0) {
            "SQLite first type parameter must not be negative: $firstParameter"
        }
        require(secondParameter == null || secondParameter >= 0) {
            "SQLite second type parameter must not be negative: $secondParameter"
        }
        return buildString {
            append('\"')
            append(typeName.replace("\"", "\"\""))
            append('\"')
            if (firstParameter != null) {
                append('(')
                append(firstParameter)
                if (secondParameter != null) {
                    append(',')
                    append(secondParameter)
                }
                append(')')
            }
        }
    }
}

/** The SQLite declared storage classes that have one matching Kagiroi scalar codec. */
enum class SqliteDeclaredType(internal val sqlName: String) {
    INTEGER("INTEGER"),
    REAL("REAL"),
    TEXT("TEXT"),
    BLOB("BLOB"),
}
