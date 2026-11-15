package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.castTarget
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.schema.typeUsing
import dev.quwac.kagiroi.sql.AddPostgresEnumValue
import dev.quwac.kagiroi.sql.CreatePostgresEnum
import dev.quwac.kagiroi.sql.DropPostgresEnum
import dev.quwac.kagiroi.sql.createTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresEnumTest {
    private enum class Kind { In, Out }

    @Test
    fun mapsExplicitLabelsExportsTheManagedTypeAndKeepsTypedConversion() {
        val type = PostgresTypes.enum("punch_kind", listOf("ClockIn", "ClockOut"), "app")
        val table = object : Table("punches") {
            val kind = column("kind", type).asEnum(Kind.entries) {
                Kind.In mapsTo "ClockIn"
                Kind.Out mapsTo "ClockOut"
            }.let { final -> final.typeUsing(StringColumnType) { cast(it, castTarget(final)) } }
        }
        assertEquals("ClockIn", table.kind.toStorageValue(Kind.In))
        assertEquals(Kind.Out, table.kind.fromStorageValue("ClockOut"))
        assertFailsWith<IllegalArgumentException> { table.kind.fromStorageValue("invalid") }
        val registry = schema(table)
        assertEquals(type.definition, registry.enumTypes.single())
        val projection = registry.project().encode()
        assertTrue(projection.contains("\"formatVersion\":1"))
        assertTrue(
            projection.contains(
                "\"enumTypes\":[{\"schemaName\":\"app\",\"name\":\"punch_kind\",\"labels\":[\"ClockIn\",\"ClockOut\"]}]",
            ),
        )
        assertEquals(
            "CREATE TABLE \"punches\" (\"kind\" \"app\".\"punch_kind\" NOT NULL)",
            PostgresDialect.render(createTable(table)).sql,
        )
    }

    @Test
    fun rendersNativeEnumDdlAndValidatesLabelsAndSharedDefinitions() {
        val definition = PostgresTypes.enum("it's\"type", listOf("can't", "終"), "app").definition
        assertEquals(
            "CREATE TYPE \"app\".\"it's\"\"type\" AS ENUM ('can''t', '終')",
            PostgresDialect.render(CreatePostgresEnum(definition)).sql,
        )
        assertEquals(
            "ALTER TYPE \"app\".\"it's\"\"type\" ADD VALUE 'New'",
            PostgresDialect.render(AddPostgresEnumValue(definition, "New")).sql,
        )
        assertEquals(
            "DROP TYPE IF EXISTS \"app\".\"it's\"\"type\"",
            PostgresDialect.render(DropPostgresEnum(definition, true)).sql,
        )
        assertFailsWith<IllegalArgumentException> { PostgresTypes.enum("empty", emptyList()) }
        assertFailsWith<IllegalArgumentException> { PostgresTypes.enum("duplicates", listOf("same", "same")) }
        assertFailsWith<IllegalArgumentException> { PostgresTypes.enum("long", listOf("あ".repeat(22))) }
        assertFailsWith<IllegalArgumentException> { PostgresTypes.enum("nul", listOf("a\u0000b")) }
        assertFailsWith<IllegalArgumentException> { AddPostgresEnumValue(definition, "can't") }
        fun table(name: String, labels: List<String>) = object : Table(
            name,
        ) {
            val kind = column("kind", PostgresTypes.enum("shared", labels))
        }
        assertFailsWith<IllegalArgumentException> {
            schema(
                table("first", listOf("one")),
                table("second", listOf("two")),
            )
        }
        assertEquals(1, schema(table("first", listOf("one")), table("second", listOf("one"))).enumTypes.size)
    }
}
