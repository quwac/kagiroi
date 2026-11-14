package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.nullIf
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.CreatePostgresPolicy
import dev.quwac.kagiroi.sql.DoubleQuotedQuestionMarkDialect
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.createSecurityObjects
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PostgresArraysTest {
    @Test
    fun preservesArrayAndPredicateParameterOrder() {
        val table = ArrayDocuments()
        val value = "{11111111-1111-4111-8111-111111111111}"
        val query = select(table.tenantId).from(table)
            .where(table.tenantId.eqAny(literal(value).asPostgresArray(PostgresTypes.uuid)))
        val rendered = SqlRenderer(PostgresDialect).render(query)
        assertTrue(rendered.sql.contains("= ANY (CAST($1 AS UUID[]))"))
        assertEquals(listOf(value, true), rendered.parameters.map { it.value })
        assertFailsWith<UnsupportedCapability> { SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query) }
    }

    @Test
    fun rendersAndExportsArrayPoliciesWithNullableContext() {
        val table = ArrayDocuments()
        val registry = schema(table)
        val policy = registry.createSecurityObjects().filterIsInstance<CreatePostgresPolicy>().single()
        val sql = PostgresDialect.render(policy).sql
        val array = "CAST(NULLIF(current_setting('app.tenant_ids', TRUE), '') AS UUID[])"
        val condition = "(\"tenant_id\" = ANY ($array)) = TRUE"
        assertTrue(sql.contains("USING ($condition)"), sql)
        assertTrue(sql.contains("WITH CHECK ($condition)"), sql)
        assertTrue(registry.project().encode().contains("postgresAnyEquals"))
    }

    @Test
    fun encodesEmptyAndMultipleUuidContextValues() {
        val first = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val second = Uuid.parse("22222222-2222-4222-8222-222222222222")
        assertEquals("{}", uuidArrayText(emptyList()))
        assertEquals("{$first,$second}", uuidArrayText(listOf(first, second)))
    }
}

private class ArrayDocuments : Table("array_documents") {
    val tenantId = uuid("tenant_id")
    init {
        postgresRls {
            policy("memberships") {
                fun condition() = tenantId.eqAny(
                    nullIf(currentSetting("app.tenant_ids", missingOk = true), "").asPostgresArray(PostgresTypes.uuid),
                )
                using { condition() }
                withCheck { condition() }
            }
        }
    }
}
