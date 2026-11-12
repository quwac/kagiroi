package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.AddSecurityPredicate
import dev.quwac.kagiroi.sql.CreateSecurityPolicy
import dev.quwac.kagiroi.sql.DropSecurityPolicy
import dev.quwac.kagiroi.sql.DropSecurityPredicate
import dev.quwac.kagiroi.sql.SetSecurityPolicyEnabled
import dev.quwac.kagiroi.sql.createSecurityObjects
import dev.quwac.kagiroi.sql.diffSecurity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlServerRlsTest {
    private class Rows(name: String, block: Boolean = true, disabled: Boolean = false, reverse: Boolean = false) :
        Table(
            name,
            "dbo",
        ) {
        val tenant = int("tenant_id")
        val region = string("region")
        init {
            sqlServerRls("tenant_security", schema = "security") {
                if (disabled) disabled()
                val args = if (reverse) arrayOf(region, tenant) else arrayOf(tenant, region)
                filterPredicate(function("security", "tenant_predicate"), *args)
                if (block) blockPredicate(function("security", "tenant_predicate"), AfterInsert, tenant)
            }
        }
    }

    @Test
    fun mergesTableLocalContributionsAndRendersNativePolicy() {
        val registry = schema(Rows("documents"), Rows("orders"))
        assertEquals(1, registry.securityObjects.size)
        val statement = registry.createSecurityObjects().single() as CreateSecurityPolicy
        assertTrue(statement.enabled)
        assertEquals(4, statement.predicates.size)
        val sql = SqlServerDialect.render(statement).sql
        assertTrue(sql.startsWith("CREATE SECURITY POLICY [security].[tenant_security] "))
        assertTrue(
            sql.contains(
                "ADD FILTER PREDICATE [security].[tenant_predicate]([tenant_id], [region]) ON [dbo].[documents]",
            ),
        )
        assertTrue(
            sql.contains(
                "ADD BLOCK PREDICATE [security].[tenant_predicate]([tenant_id]) ON [dbo].[orders] AFTER INSERT",
            ),
        )
        assertTrue(sql.endsWith("WITH (STATE = ON)"))
        val reversed = schema(Rows("orders"), Rows("documents"))
        assertEquals(registry.securityObjects, reversed.securityObjects)
        assertTrue(diffSecurity(registry, reversed).isEmpty())
    }

    @Test
    fun rendersAllBlockTimingsAndDisabledState() {
        val table = object : Table("timings") {
            val tenant = int("tenant")
            init {
                sqlServerRls("p") {
                    disabled()
                    listOf(AfterInsert, AfterUpdate, BeforeUpdate, BeforeDelete).forEach {
                        blockPredicate(function("security", "fn"), it, tenant)
                    }
                }
            }
        }
        val sql = SqlServerDialect.render(schema(table).createSecurityObjects().single()).sql
        listOf(
            "AFTER INSERT",
            "AFTER UPDATE",
            "BEFORE UPDATE",
            "BEFORE DELETE",
            "STATE = OFF",
        ).forEach { assertTrue(sql.contains(it)) }
        assertFailsWith<IllegalArgumentException> { schema(Rows("a"), Rows("b", disabled = true)) }
    }

    @Test
    fun diffsPredicateAdditionRemovalArgumentOrderAndState() {
        val before = schema(Rows("documents", block = false))
        val after = schema(Rows("documents"))
        assertIs<AddSecurityPredicate>(diffSecurity(before, after).single())
        assertIs<DropSecurityPredicate>(diffSecurity(after, before).single())
        val changed = diffSecurity(before, schema(Rows("documents", block = false, reverse = true)))
        assertEquals(listOf(DropSecurityPredicate::class, AddSecurityPredicate::class), changed.map { it::class })
        assertIs<SetSecurityPolicyEnabled>(diffSecurity(after, schema(Rows("documents", disabled = true))).single())
        assertIs<CreateSecurityPolicy>(diffSecurity(schema(), after).single())
        assertIs<DropSecurityPolicy>(diffSecurity(after, schema()).single())
    }

    @Test
    fun rejectsForeignArgumentsAndDuplicateOperations() {
        val other = Rows("other")
        assertFailsWith<IllegalArgumentException> {
            object : Table("bad") {
                init {
                    sqlServerRls("p") { filterPredicate(function("s", "f"), other.tenant) }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("bad") {
                val t = int("t")
                init {
                    sqlServerRls("p") {
                        filterPredicate(function("s", "f"), t)
                        filterPredicate(function("s", "f"), t)
                    }
                }
            }
        }
    }
}
