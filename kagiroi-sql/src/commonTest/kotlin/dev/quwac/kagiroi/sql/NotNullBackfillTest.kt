package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.schema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotNullBackfillTest {
    @Test
    fun retainsNonNullTypesAndExportsOrderedTypedOperationsOutsideThePhysicalSchema() {
        val parent = Parent()
        val child = Child(parent, backfill = true)
        val registry = schema(child, parent)
        val projection = Json.parseToJsonElement(registry.project().encode()).jsonObject
        val hint = projection.getValue(
            "evolution",
        ).jsonObject.getValue("notNullBackfills").jsonArray.single().jsonObject
        val statements = hint.getValue("statements").jsonArray
        assertEquals(
            listOf("insert", "update"),
            statements.map { it.jsonObject.getValue("kind").jsonPrimitive.content },
        )
        assertEquals("parent_id", hint.getValue("column").jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(child.parentId.isNullable)
        assertFalse(child.parentId.hasDefault)
        assertEquals("Parent ID", child.parentId.comment)
        val plainParent = Parent()
        val plain = Json.parseToJsonElement(
            schema(Child(plainParent, backfill = false), plainParent).project().encode(),
        ).jsonObject
        assertEquals(plain.getValue("schema"), projection.getValue("schema"))
        assertEquals(registry.project().encode(), schema(parent, child).project().encode())
        assertTrue(statements.last().jsonObject.containsKey("predicate"))
    }

    @Test
    fun rejectsEmptyDeclarationsAndUnmanagedTargets() {
        val empty = object : Table("empty") {
            fun declare() = int("value").notNullBackfill { }
        }
        assertFailsWith<IllegalArgumentException> { empty.declare() }
        val child = Child(Parent(), backfill = true)
        assertFailsWith<IllegalArgumentException> { schema(child) }
    }

    @Test
    fun rejectsIdentityBackfillsAndAdvancedQueryClausesRatherThanDroppingTheirSemantics() {
        val table = object : Table("identities") {
            val id = long("id").identity()
        }
        assertFailsWith<IllegalArgumentException> {
            table.id.notNullBackfill { execute(update(table) { set(table.id, 1L) }) }
        }
        val target = Parent()
        assertFailsWith<IllegalArgumentException> {
            target.id.notNullBackfill {
                execute(update(target) { set(target.id, 1) }.from(Parent().alias("other")))
            }
        }
    }
}

private class Parent : Table("parents") {
    val id = int("id").primaryKey()
    val name = string("name")
}

private class Child(parent: Parent, backfill: Boolean) : Table("children") {
    val id = int("id").primaryKey()
    val parentId = int("parent_id").let { definition ->
        if (backfill) {
            definition.notNullBackfill {
                execute(
                    insertInto(parent).values {
                        set(parent.id, 7)
                        set(parent.name, "Owner's parent")
                    },
                )
                execute(update(column.table) { set(column, 7) }.where(column.optional.isNull()))
            }
        } else {
            definition
        }
    }.comment("Parent ID")
}
