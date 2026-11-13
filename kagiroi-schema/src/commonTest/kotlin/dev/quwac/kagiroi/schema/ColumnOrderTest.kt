package dev.quwac.kagiroi.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ColumnOrderTest {
    @Test
    fun defaultsToDeclarationPositionsIncludingForeignKeysAndReadOnlyColumns() {
        val parent = OrderParent()
        val child = object : Table("child") {
            val value = int("value")
            val parentId = foreignKey("parent_id", parent.id)
            val label = string("label").computed()
            val doubled = int("doubled").generated(value * value, GeneratedColumnStorage.Stored)
        }
        assertEquals(listOf(0, 1, 2, 3), child.columns.map { it.order })
        assertEquals(listOf("value", "parent_id", "label", "doubled"), child.columns.map { it.name })
    }

    @Test
    fun ordersInheritedColumnsAndExportsOnlyTheEffectiveColumnSequence() {
        val table = OrderedRecords()
        val expected = listOf("id", "name", "created_at", "updated_at")
        assertEquals(expected, table.columns.map { it.name })
        assertEquals(3, table.name.order)
        assertEquals(-100, table.id.order)
        val json = Json.parseToJsonElement(schema(table).project().encode()).jsonObject
        val columns = json.getValue("schema").jsonObject.getValue("tables").jsonArray.single()
            .jsonObject.getValue("columns").jsonArray
        assertEquals(expected, columns.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(false, columns.any { "order" in it.jsonObject })
    }

    @Test
    fun breaksTiesByDeclarationPositionAndComparesExtremeKeysWithoutOverflow() {
        val table = object : Table("ties") {
            val last = int("last").order(Int.MAX_VALUE)
            val firstTie = int("first_tie").order(2).comment("First tie")
            val implicitTie = int("implicit_tie")
            val thirdTie = int("third_tie").order(2).nullable()
            val first = int("first").order(Int.MIN_VALUE)
        }
        assertEquals(
            listOf("first", "first_tie", "implicit_tie", "third_tie", "last"),
            table.columns.map { it.name },
        )
    }

    @Test
    fun preservesForeignKeyModifiersAndReadOnlyColumnKinds() {
        val parent = OrderParent()
        val child = object : Table("related") {
            val value = int("value")
            val parentId = foreignKey("parent_id", parent.id).order(-100).onDelete(ForeignKeyAction.Cascade)
            val label = string("label").computed().order(100).comment("Logical label")
            val squared = int("squared").generated(value * value, GeneratedColumnStorage.Stored).order(200)
        }
        assertEquals(listOf("parent_id", "value", "label", "squared"), child.columns.map { it.name })
        assertIs<ForeignKeyColumn<Int>>(child.parentId)
        assertIs<ComputedColumn<String>>(child.label)
        assertIs<GeneratedColumn<Int>>(child.squared)
        assertSame(child.parentId, child.foreignKeys.single().sourceColumns.single())
        assertEquals(ForeignKeyAction.Cascade, child.foreignKeys.single().onDelete)
    }
}

private class OrderParent : Table("parent") {
    val id = int("id").primaryKey()
}

private open class AuditRecords : Table("ordered_records") {
    val createdAt = int("created_at").order(100)
    val updatedAt = int("updated_at").order(200)
}

private class OrderedRecords : AuditRecords() {
    val id = int("id").order(-100).primaryKey().comment("ID")
    val name = string("name").default("Example")
}
