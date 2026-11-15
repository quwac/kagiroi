package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.castTarget
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.CreateOracleEnumDomain
import dev.quwac.kagiroi.sql.DropOracleEnumDomain
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OracleEnumTest {
    private enum class Kind { In, Out }

    @Test
    fun mapsExplicitTextAndExportsManagedDomains() {
        val type = OracleTypes.enumDomain("punch_kind", linkedMapOf("In" to "clock_in", "Out" to "clock_out"), "app")
        val table = object : Table("punches") {
            val kind = column("kind", type).asEnum(Kind.entries) {
                Kind.In mapsTo "clock_in"
                Kind.Out mapsTo "clock_out"
            }
        }
        assertEquals("clock_in", table.kind.toStorageValue(Kind.In))
        assertEquals(Kind.Out, table.kind.fromStorageValue("clock_out"))
        assertFailsWith<IllegalArgumentException> { table.kind.fromStorageValue("invalid") }
        val registry = schema(table)
        assertEquals(type.enumDomain.values, registry.oracleEnumDomains.single().values)
        assertTrue(registry.project().encode().contains("\"formatVersion\":1"))
        assertTrue(registry.project().encode().contains("\"value\":\"clock_in\""))
        assertEquals(
            "CREATE TABLE \"punches\" (\"kind\" \"app\".\"punch_kind\" NOT NULL)",
            OracleDialect.render(createTable(table)).sql,
        )
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(OracleDialect).render(select(cast(table.kind, castTarget(table.kind))).from(table))
        }
    }

    @Test
    fun rendersNativeDomainDdlAndRejectsInvalidDefinitions() {
        val type = OracleTypes.enumDomain("it's\"type", linkedMapOf("Can't" to "can't", "終" to "終"), "app")
        assertEquals(
            "CREATE DOMAIN \"app\".\"it's\"\"type\" AS ENUM (\"Can't\" = 'can''t', \"終\" = '終')",
            OracleDialect.render(CreateOracleEnumDomain(type.enumDomain)).sql,
        )
        assertEquals(
            "DROP DOMAIN \"app\".\"it's\"\"type\"",
            OracleDialect.render(DropOracleEnumDomain(type.enumDomain)).sql,
        )
        assertFailsWith<IllegalArgumentException> { OracleTypes.enumDomain("empty", emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            OracleTypes.enumDomain(
                "aliases",
                mapOf("A" to "same", "B" to "same"),
            )
        }
        assertFailsWith<IllegalArgumentException> { OracleTypes.enumDomain("nul", mapOf("A" to "a\u0000b")) }
        assertFailsWith<IllegalArgumentException> { OracleTypes.enumDomain("long", mapOf("A" to "a".repeat(4001))) }
        fun table(name: String, value: String) = object : Table(name) {
            val kind = column("kind", OracleTypes.enumDomain("shared", mapOf("A" to value)))
        }
        assertFailsWith<IllegalArgumentException> { schema(table("first", "one"), table("second", "two")) }
        assertEquals(1, schema(table("first", "one"), table("second", "one")).oracleEnumDomains.size)
    }
}
