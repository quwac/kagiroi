package dev.quwac.kagiroi.schema

/**
 * Explicit root of the tables managed by one application schema.
 *
 * A registry never repeats schema details. Table-owned indexes and table-declared sequences are
 * collected from the registered table definitions. Reflection and source-code analysis are never
 * used.
 */
class SchemaRegistry internal constructor(internal val tables: List<Table>) {
    internal val sequences: List<Sequence>
        get() = tables.flatMap(Table::sequences)

    internal val indexes: List<SchemaIndex>
        get() = tables.flatMap(Table::indexes)

    /** Projects this registry through Kagiroi's stable, versioned schema-export contract. */
    fun project(): SchemaProjection = projectSchema(this)

    internal fun validate() {
        validateNoRepeatedInstances()
        validateNames()
        validateRelations()
    }

    private fun validateNoRepeatedInstances() {
        for (left in tables.indices) {
            for (right in left + 1 until tables.size) {
                require(tables[left] !== tables[right]) {
                    "Table is registered more than once: ${tables[left]}"
                }
            }
        }
    }

    private fun validateNames() {
        require(tables.map(Table::qualifiedName).distinct().size == tables.size) {
            "Schema registry contains duplicate table qualified names"
        }
        require(sequences.map(Sequence::qualifiedName).distinct().size == sequences.size) {
            "Schema registry contains duplicate sequence qualified names"
        }
        require(indexes.map(SchemaIndex::qualifiedName).distinct().size == indexes.size) {
            "Schema registry contains duplicate index names in one schema"
        }
    }

    private fun validateRelations() {
        tables.forEach { table ->
            table.validateConstraints()
            table.foreignKeys.forEach { foreignKey ->
                require(tables.any { it === foreignKey.targetTable }) {
                    "Foreign key on ${table.qualifiedName()} references unregistered table " +
                        foreignKey.targetTable.qualifiedName()
                }
                foreignKey.sourceColumns.forEach(::requireManagedColumn)
                foreignKey.targetColumns.forEach(::requireManagedColumn)
            }
            table.checks.forEach { check -> check.expression.referencedColumns.forEach(::requireManagedColumn) }
            table.referencedSequences().forEach { sequence ->
                require(sequences.any { it === sequence }) {
                    "Default on ${table.qualifiedName()} references a sequence not declared by a managed table: " +
                        sequence.qualifiedName()
                }
            }
        }
        indexes.forEach { index ->
            require(tables.any { it === index.table }) {
                "Index ${index.name} references unregistered table ${index.table.qualifiedName()}"
            }
            index.keys.flatMap { it.referencedColumns }.forEach(::requireManagedColumn)
            index.includedColumns.forEach(::requireManagedColumn)
            index.predicate?.referencedColumns?.forEach(::requireManagedColumn)
        }
    }

    private fun requireManagedColumn(column: Column<*>) {
        require(!column.isComputed) {
            "Application schema cannot reference logical computed column $column"
        }
    }
}

/** Creates an explicit application schema registry from its managed table roots. */
fun schema(vararg tables: Table): SchemaRegistry = SchemaRegistry(tables.toList()).also { registry ->
    registry.validate()
    registry.tables.forEach(Table::sealSchema)
}

private fun Table.qualifiedName(): String = "${schemaName.orEmpty()}\u0000$tableName"

private fun Sequence.qualifiedName(): String = "${schemaName.orEmpty()}\u0000$name"

private fun SchemaIndex.qualifiedName(): String = "${table.schemaName.orEmpty()}\u0000$name"
