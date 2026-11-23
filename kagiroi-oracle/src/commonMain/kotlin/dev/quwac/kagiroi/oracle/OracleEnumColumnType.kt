package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.OracleEnumDefinition
import dev.quwac.kagiroi.schema.OracleEnumStorage
import dev.quwac.kagiroi.schema.ScalarMappedColumnType
import dev.quwac.kagiroi.schema.SchemaTypeArgument
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.columnMapping

/** Native Oracle enumeration domain transported as explicitly assigned text values.
 * @property enumDomain managed domain declaration.
 */
class OracleEnumColumnType internal constructor(override val enumDomain: OracleEnumDefinition) :
    ScalarMappedColumnType<String, String>(
        "Oracle ENUM ${enumDomain.name}",
        StringColumnType,
        columnMapping({ it }, { it }),
    ),
    StableSchemaColumnType,
    OracleEnumStorage {
    override val schemaTypeDescriptor = SchemaTypeDescriptor(
        "kagiroi.oracle.enumDomain",
        listOfNotNull(
            enumDomain.schemaName?.let { SchemaTypeArgument("schemaName", it) },
            SchemaTypeArgument("name", enumDomain.name),
        ),
    )
}
