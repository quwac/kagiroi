package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.CanonicalSecurityObject
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.CreateVpdPolicy
import dev.quwac.kagiroi.sql.DropVpdPolicy
import dev.quwac.kagiroi.sql.SetVpdPolicyEnabled
import dev.quwac.kagiroi.sql.createSecurityObjects
import dev.quwac.kagiroi.sql.diffSecurity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OracleVpdTest {
    private class Rows(disabled: Boolean = false, packaged: Boolean = false, reverse: Boolean = false) :
        Table(
            "DOCUMENTS",
            "APP",
        ) {
        val tenant = int("TENANT_ID")
        init {
            oracleVpd("tenant_isolation") {
                function(
                    schema = "SECURITY",
                    packageName = if (packaged) "TENANT_SECURITY" else null,
                    name = "TENANT_PREDICATE",
                )
                if (reverse) {
                    forStatements(
                        Delete,
                        Update,
                        Insert,
                        Select,
                    )
                } else {
                    forStatements(Select, Insert, Update, Delete)
                }
                if (disabled) disabled()
            }
        }
    }

    @Test
    fun registersNativeVpdWithFunctionStatementsAndInsertCheck() {
        val registry = schema(Rows())
        assertIs<CanonicalSecurityObject.OracleVpdPolicy>(registry.securityObjects.single())
        val sql = OracleDialect.render(registry.createSecurityObjects().single()).sql
        assertTrue(sql.startsWith("BEGIN DBMS_RLS.ADD_POLICY("))
        assertTrue(
            sql.contains("object_schema => 'APP', object_name => 'DOCUMENTS', policy_name => 'tenant_isolation'"),
        )
        assertTrue(sql.contains("function_schema => 'SECURITY', policy_function => '\"TENANT_PREDICATE\"'"))
        assertTrue(
            sql.contains("statement_types => 'DELETE,INSERT,SELECT,UPDATE', update_check => TRUE, enable => TRUE"),
        )
        assertTrue(sql.endsWith("); END;"))
        assertEquals(registry.securityObjects, schema(Rows(reverse = true)).securityObjects)
    }

    @Test
    fun supportsPackagesDisabledAndExplicitUpdateCheck() {
        val sql = OracleDialect.render(
            schema(Rows(disabled = true, packaged = true)).createSecurityObjects().single(),
        ).sql
        assertTrue(sql.contains("'\"TENANT_SECURITY\".\"TENANT_PREDICATE\"'"))
        assertTrue(sql.contains("enable => FALSE"))
        val table = object : Table("t") {
            val id = int("id")
            init {
                oracleVpd("p") {
                    function(name = "f")
                    forStatements(Update)
                    updateCheck()
                }
            }
        }
        assertTrue(
            OracleDialect.render(schema(table).createSecurityObjects().single()).sql.contains("update_check => TRUE"),
        )
        assertFailsWith<IllegalArgumentException> {
            object : Table(
                "bad",
            ) {
                init {
                    oracleVpd("p") {
                        function(name = "f")
                        forStatements(Select)
                        updateCheck()
                    }
                }
            }
        }
    }

    @Test
    fun diffsAddDropConfigurationAndEnableState() {
        val plain = schema(Rows())
        assertIs<CreateVpdPolicy>(diffSecurity(schema(), plain).single())
        assertIs<DropVpdPolicy>(diffSecurity(plain, schema()).single())
        val state = diffSecurity(plain, schema(Rows(disabled = true))).single()
        assertIs<SetVpdPolicyEnabled>(state)
        assertTrue(OracleDialect.render(state).sql.contains("DBMS_RLS.ENABLE_POLICY"))
        assertEquals(
            listOf(DropVpdPolicy::class, CreateVpdPolicy::class),
            diffSecurity(plain, schema(Rows(packaged = true))).map { it::class },
        )
    }
}
