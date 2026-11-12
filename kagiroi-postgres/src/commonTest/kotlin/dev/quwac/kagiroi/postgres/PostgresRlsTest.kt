package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.PostgresPolicyCommand
import dev.quwac.kagiroi.schema.PostgresRlsDefinition
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.CreatePostgresPolicy
import dev.quwac.kagiroi.sql.DisableRowLevelSecurity
import dev.quwac.kagiroi.sql.DropPostgresPolicy
import dev.quwac.kagiroi.sql.EnableRowLevelSecurity
import dev.quwac.kagiroi.sql.ForceRowLevelSecurity
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.createSecurityObjects
import dev.quwac.kagiroi.sql.diffSecurity
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.query
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PostgresRlsTest {
    private class Documents(
        force: Boolean = false,
        command: PostgresPolicyCommand = PostgresPolicyCommand.All,
        reverseRoles: Boolean = false,
        policy: String? = "tenant",
        newValue: String = "app.tenant_id",
    ) : Table(
        "documents",
    ) {
        val id = uuid("id").primaryKey()
        val tenantId = uuid("tenant_id")
        init {
            postgresRls {
                if (force) force()
                if (policy != null) {
                    policy(policy) {
                        when (command) {
                            PostgresPolicyCommand.All -> forAll()
                            PostgresPolicyCommand.Select -> forSelect()
                            PostgresPolicyCommand.Insert -> forInsert()
                            PostgresPolicyCommand.Update -> forUpdate()
                            PostgresPolicyCommand.Delete -> forDelete()
                        }
                        if (reverseRoles) to(role("support"), role("app"))
                        if (command != PostgresPolicyCommand.Insert) {
                            using {
                                tenantId eq currentSetting(
                                    newValue,
                                ).cast<Uuid>()
                            }
                        }
                        if (command !in listOf(PostgresPolicyCommand.Select, PostgresPolicyCommand.Delete)) {
                            withCheck { tenantId eq currentSetting(newValue).cast<Uuid>() }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun initRegistersImplicitEnableForceAndTypedPolicy() {
        val table = Documents(force = true)
        val registry = schema(table)
        val plan = registry.createSecurityObjects()
        assertIs<EnableRowLevelSecurity>(plan[0])
        assertIs<ForceRowLevelSecurity>(plan[1])
        assertEquals("ALTER TABLE \"documents\" ENABLE ROW LEVEL SECURITY", PostgresDialect.render(plan[0]).sql)
        assertEquals("ALTER TABLE \"documents\" FORCE ROW LEVEL SECURITY", PostgresDialect.render(plan[1]).sql)
        val sql = PostgresDialect.render(plan[2]).sql
        assertEquals(
            "CREATE POLICY \"tenant\" ON \"documents\" USING (\"tenant_id\" = CAST(current_setting('app.tenant_id') AS UUID)) WITH CHECK (\"tenant_id\" = CAST(current_setting('app.tenant_id') AS UUID))",
            sql,
        )
        val policy = (table.securityDefinitions.single() as PostgresRlsDefinition).policies.single()
        assertEquals(PostgresPolicyCommand.All, policy.command)
        assertEquals(listOf(Public), policy.roles)
        assertFalse(policy.restrictive)
        assertEquals(2, registry.securityObjects.size)
        assertTrue(registry.project().encode().contains("\"formatVersion\":2"))
        assertTrue(registry.project().encode().contains("postgresCurrentSetting"))
    }

    @Test
    fun rendersEveryCommandAndSpecialPrincipalWithRestrictiveMode() {
        PostgresPolicyCommand.entries.forEach { command ->
            val table = Documents(command = command)
            val sql = PostgresDialect.render(schema(table).createSecurityObjects().last()).sql
            if (command != PostgresPolicyCommand.All) assertTrue(sql.contains(" FOR ${command.name.uppercase()}"))
            assertEquals(command != PostgresPolicyCommand.Insert, sql.contains(" USING "))
            assertEquals(
                command !in listOf(PostgresPolicyCommand.Select, PostgresPolicyCommand.Delete),
                sql.contains(" WITH CHECK "),
            )
        }
        val table = object : Table("roles") {
            val tenant = string("tenant")
            init {
                postgresRls {
                    policy("restricted") {
                        restrictive()
                        to(Public, CurrentUser, SessionUser, role("a\"b"))
                        using { tenant eq currentSetting("app.tenant_id") }
                    }
                }
            }
        }
        val sql = PostgresDialect.render(schema(table).createSecurityObjects().last()).sql
        assertTrue(sql.contains(" AS RESTRICTIVE"))
        listOf("PUBLIC", "CURRENT_USER", "SESSION_USER", "\"a\"\"b\"").forEach { assertTrue(sql.contains(it)) }
    }

    @Test
    fun rejectsInvalidClausesForeignColumnsDuplicatesAndSealedRegistration() {
        val invalid = listOf(PostgresPolicyCommand.Select, PostgresPolicyCommand.Delete, PostgresPolicyCommand.Insert)
        invalid.forEach { command ->
            assertFailsWith<IllegalArgumentException> {
                object : Table("invalid") {
                    val tenant = string("tenant")
                    init {
                        postgresRls {
                            policy("p") {
                                when (command) {
                                    PostgresPolicyCommand.Select -> forSelect()
                                    PostgresPolicyCommand.Delete -> forDelete()
                                    else -> forInsert()
                                }
                                using { tenant eq "a" }
                                withCheck { tenant eq "a" }
                            }
                        }
                    }
                }
            }
        }
        val other = Documents()
        assertFailsWith<IllegalArgumentException> {
            object : Table("foreign") {
                init {
                    postgresRls { policy("p") { using { other.tenantId eq Uuid.NIL } } }
                }
            }
        }
        val table = Documents()
        schema(table)
        assertFailsWith<IllegalArgumentException> { table.postgresRls {} }
        assertFailsWith<IllegalArgumentException> {
            object : Table("duplicate") {
                init {
                    postgresRls {}
                    postgresRls {}
                }
            }
        }
    }

    @Test
    fun canonicalObjectsIgnoreRuntimeIdentityAndDeclarationOrder() {
        fun registry(reverse: Boolean) = schema(object : Table("stable") {
            val tenant = string("tenant")
            init {
                postgresRls {
                    val names = if (reverse) listOf("b", "a") else listOf("a", "b")
                    names.forEach { name ->
                        policy(name) {
                            val roles = if (reverse) listOf(role("y"), role("x")) else listOf(role("x"), role("y"))
                            to(roles[0], roles[1])
                            using { tenant eq currentSetting("app.tenant") }
                        }
                    }
                }
            }
        })
        assertEquals(registry(false).securityObjects, registry(true).securityObjects)
        assertEquals(registry(false).project().encode(), registry(true).project().encode())
        assertTrue(diffSecurity(registry(false), registry(true)).isEmpty())
    }

    @Test
    fun diffsOnlySecurityAndPreservesDependencyOrder() {
        val empty = schema(
            object : Table("documents") {
                val id = uuid("id").primaryKey()
                val tenantId = uuid("tenant_id")
            },
        )
        val plain = schema(Documents())
        val forced = schema(Documents(force = true))
        assertEquals(
            listOf(EnableRowLevelSecurity::class, CreatePostgresPolicy::class),
            diffSecurity(empty, plain).map { it::class },
        )
        assertIs<ForceRowLevelSecurity>(diffSecurity(plain, forced).single())
        assertFalse((diffSecurity(forced, plain).single() as ForceRowLevelSecurity).force)
        assertEquals(
            listOf(DropPostgresPolicy::class, DisableRowLevelSecurity::class),
            diffSecurity(plain, empty).map { it::class },
        )
        val changed = diffSecurity(plain, schema(Documents(newValue = "app.other")))
        assertEquals(listOf(DropPostgresPolicy::class, CreatePostgresPolicy::class), changed.map { it::class })
        assertIs<DropPostgresPolicy>(diffSecurity(plain, schema(Documents(policy = null))).single())
        assertIs<CreatePostgresPolicy>(diffSecurity(schema(Documents(policy = null)), plain).single())
    }

    @Test
    fun currentSettingUsesParametersInQueriesAndPreservesNullableCast() {
        val table = Documents()
        val setting = currentSetting("app.tenant_id", missingOk = true).cast<Uuid>()
        assertTrue(setting.isNullable)
        val query = select(setting).from(table)
        val rendered = SqlRenderer(PostgresDialect).render(query)
        assertTrue(rendered.sql.contains("current_setting($1, $2)"))
        assertEquals(listOf("app.tenant_id", true), rendered.parameters.map { it.value })
    }
}
