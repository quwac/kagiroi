package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.order
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.createTable
import kotlin.test.Test
import kotlin.test.assertEquals

class ColumnOrderDdlTest {
    @Test
    fun rendersInheritedColumnsInTheirConfiguredOrder() {
        assertEquals(
            "CREATE TABLE \"ordered_records\" (\"id\" INTEGER NOT NULL PRIMARY KEY, " +
                "\"name\" TEXT NOT NULL, \"created_at\" INTEGER NOT NULL, \"updated_at\" INTEGER NOT NULL)",
            PostgresDialect.render(createTable(OrderedRecords())).sql,
        )
    }
}

private open class AuditRecords : Table("ordered_records") {
    val createdAt = int("created_at").order(100)
    val updatedAt = int("updated_at").order(200)
}

private class OrderedRecords : AuditRecords() {
    val id = int("id").order(-100).primaryKey()
    val name = string("name")
}
