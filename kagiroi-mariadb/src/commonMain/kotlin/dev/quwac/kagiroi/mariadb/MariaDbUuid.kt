package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.mysql.MySqlExtension
import dev.quwac.kagiroi.mysql.MySqlTypeMapping
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.sql.SqlTypeCodec
import kotlin.uuid.Uuid

internal object MariaDbCharUuidStorageType : ColumnType<Uuid>("MariaDB CHAR(36)"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.mariadb.charUuidStorage")
}

/**
 * Optional explicit transport codec for a `CHAR(36)` UUID column.
 *
 * [charUuid] and [MariaDbTypes.charUuid] already carry this codec. This extension remains
 * available for a configuration that assembles its codecs explicitly.
 */
object MariaDbCharUuidStorage : MySqlExtension {
    override val typeMappings: List<MySqlTypeMapping<*>> = emptyList()

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(
            type = MariaDbCharUuidStorageType,
            storageType = StringColumnType,
            encode = Uuid::toString,
            decode = Uuid::parse,
        ),
    )
}

/**
 * Declares a UUID stored in MariaDB CHAR(36).
 *
 * This is an explicit cross-version storage choice. Its physical type carries the string
 * codec, so values remain parameterized without an execution-adapter installation.
 */
fun Table.charUuid(name: String): WritableColumn<Uuid> = column(name, MariaDbTypes.charUuid)

/** Declares a UUID stored in MariaDB BINARY(16) through the shared MySQL-compatible mapping. */
fun Table.binaryUuid(name: String): WritableColumn<Uuid> = column(name, MariaDbTypes.binaryUuid)
