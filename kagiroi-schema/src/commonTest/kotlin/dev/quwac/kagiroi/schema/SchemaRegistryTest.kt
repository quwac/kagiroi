package dev.quwac.kagiroi.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaRegistryTest {
    @Test
    fun projectsAStablePhysicalSchemaAndSeparateEvolutionLineage() {
        val first = schema(ProjectionAccounts, ProjectionOrganizations).project().encode()
        val reordered = schema(ProjectionOrganizations, ProjectionAccounts).project().encode()

        assertEquals(first, reordered)
        assertTrue(first.contains("\"formatVersion\":1"))
        assertTrue(first.contains("\"old_accounts\""))
        assertTrue(first.contains("\"old_display_name\""))
        assertFalse(first.contains("runtime_label"))
    }

    @Test
    fun sealsRegisteredTablesAtThePublicRegistryBoundary() {
        val table = MutableProjectionTable()

        schema(table)

        assertFailsWith<IllegalArgumentException> { table.addColumnAfterRegistration() }
    }
}

private object ProjectionOrganizations : Table("organizations") {
    val id = long("id").primaryKey()
}

private object ProjectionAccounts : Table("accounts") {
    val id = long("id").primaryKey()
    val displayName = string("display_name").renamedFrom("old_display_name")
    val runtimeLabel = string("runtime_label").computed()

    init {
        renamedFrom("old_accounts")
        foreignKey("organization_id", ProjectionOrganizations.id)
    }
}

private class MutableProjectionTable : Table("mutable_projection") {
    fun addColumnAfterRegistration() {
        string("late_value")
    }
}
