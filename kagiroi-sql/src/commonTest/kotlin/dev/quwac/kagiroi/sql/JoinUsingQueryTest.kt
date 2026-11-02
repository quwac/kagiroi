package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JoinUsingQueryTest {
    @Test
    fun retainsUsingAsASeparateJoinConditionAndPreservesPairOrder() {
        val query = select(UsingUsers.id, UsingOrganizations.name)
            .from(UsingUsers)
            .innerJoinUsing(UsingOrganizations) {
                using(UsingUsers.tenantId, UsingOrganizations.tenantId)
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }

        val condition = assertIs<JoinCondition.Using>(query.joins.single().condition)
        assertEquals(listOf("tenant_id", "organization_id"), condition.pairs.map(UsingColumnPair<*>::name))
        assertEquals(
            "SELECT \"using_users\".\"id\", \"using_organizations\".\"name\" FROM \"using_users\" " +
                "INNER JOIN \"using_organizations\" USING (\"tenant_id\", \"organization_id\")",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }

    @Test
    fun rendersLeftRightAndFullOuterUsingWithoutQualifyingUsingIdentifiers() {
        val renderer = SqlRenderer(DoubleQuotedQuestionMarkDialect)
        val left = select(UsingUsers.id, UsingOrganizations.name.optional)
            .from(UsingUsers)
            .leftJoinUsing(UsingOrganizations) {
                using(UsingUsers.tenantId, UsingOrganizations.tenantId)
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }
        val right = select(UsingUsers.id.optional, UsingOrganizations.name)
            .from(UsingUsers)
            .rightJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }
        val full = select(UsingUsers.id.optional, UsingOrganizations.name.optional)
            .from(UsingUsers)
            .fullOuterJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }

        assertEquals(
            "SELECT \"using_users\".\"id\", \"using_organizations\".\"name\" FROM \"using_users\" " +
                "LEFT JOIN \"using_organizations\" USING (\"tenant_id\", \"organization_id\")",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT \"using_users\".\"id\", \"using_organizations\".\"name\" FROM \"using_users\" " +
                "RIGHT JOIN \"using_organizations\" USING (\"organization_id\")",
            renderer.render(right).sql,
        )
        assertEquals(
            "SELECT \"using_users\".\"id\", \"using_organizations\".\"name\" FROM \"using_users\" " +
                "FULL OUTER JOIN \"using_organizations\" USING (\"organization_id\")",
            renderer.render(full).sql,
        )
    }

    @Test
    fun acceptsAliasesDerivedOutputsAndCteOutputsAsUsingOperands() {
        val user = UsingUsers.alias("user_source")
        val organization = UsingOrganizations.alias("organization_source")
        val aliased = select(user[UsingUsers.id], organization[UsingOrganizations.name])
            .from(user)
            .innerJoinUsing(organization) {
                using(user[UsingUsers.tenantId], organization[UsingOrganizations.tenantId])
                using(user[UsingUsers.organizationId], organization[UsingOrganizations.organizationId])
            }
        assertEquals(
            "SELECT \"user_source\".\"id\", \"organization_source\".\"name\" FROM \"using_users\" AS " +
                "\"user_source\" INNER JOIN \"using_organizations\" AS \"organization_source\" " +
                "USING (\"tenant_id\", \"organization_id\")",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(aliased).sql,
        )

        val organizationKey = lower(UsingOrganizations.key).alias("organization_key")
        val derived = select(UsingOrganizations.tenantId, UsingOrganizations.organizationId, organizationKey)
            .from(UsingOrganizations)
            .asTable("organization_source")
        val derivedQuery = select(UsingUsers.id, derived[organizationKey])
            .from(UsingUsers)
            .innerJoinUsing(derived) {
                using(UsingUsers.tenantId, derived[UsingOrganizations.tenantId])
                using(UsingUsers.organizationId, derived[UsingOrganizations.organizationId])
                using(UsingUsers.key, derived[organizationKey])
            }
        assertTrue(
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(derivedQuery).sql.contains(
                "USING (\"tenant_id\", \"organization_id\", \"organization_key\")",
            ),
        )

        val organizations = select(
            UsingOrganizations.tenantId,
            UsingOrganizations.organizationId,
            UsingOrganizations.name,
        )
            .from(UsingOrganizations)
            .asCte("using_organizations_source")
        val cteQuery = with(organizations) {
            select(UsingUsers.id, organizations[UsingOrganizations.name])
                .from(UsingUsers)
                .innerJoinUsing(organizations) {
                    using(UsingUsers.tenantId, organizations[UsingOrganizations.tenantId])
                    using(UsingUsers.organizationId, organizations[UsingOrganizations.organizationId])
                }
        }
        assertTrue(
            SqlRenderer(
                DoubleQuotedQuestionMarkDialect,
            ).render(cteQuery).sql.contains("USING (\"tenant_id\", \"organization_id\")"),
        )
    }

    @Test
    fun preservesExistingOuterJoinOptionalRules() {
        val valid = select(UsingUsers.id, UsingOrganizations.name.optional)
            .from(UsingUsers)
            .leftJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }
        SqlRenderer(DoubleQuotedQuestionMarkDialect).render(valid)

        val invalid = select(UsingUsers.id, UsingOrganizations.name)
            .from(UsingUsers)
            .leftJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }
        val failure = assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(invalid)
        }
        assertTrue(failure.message.orEmpty().contains("relation[column].optional"))
    }

    @Test
    fun makesTheJoinedSourceAvailableToLaterOnJoinsWithoutChangingParameterOrder() {
        val query = select(UsingUsers.id, UsingOrders.id)
            .from(UsingUsers)
            .innerJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }
            .innerJoin(
                UsingOrders,
                on = (UsingOrganizations.organizationId eq UsingOrders.organizationId) and
                    (UsingOrders.region eq "JP"),
            )
            .where(UsingUsers.key eq "account-key")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"using_users\".\"id\", \"using_orders\".\"id\" FROM \"using_users\" " +
                "INNER JOIN \"using_organizations\" USING (\"organization_id\") " +
                "INNER JOIN \"using_orders\" ON (\"using_organizations\".\"organization_id\" = " +
                "\"using_orders\".\"organization_id\" AND \"using_orders\".\"region\" = ?) " +
                "WHERE \"using_users\".\"organization_key\" = ?",
            rendered.sql,
        )
        assertEquals(listOf("JP", "account-key"), rendered.parameters.map { it.value })
    }

    @Test
    fun rejectsEmptyDuplicateMismatchedAndOutOfScopeUsingPairs() {
        assertFailsWith<IllegalArgumentException> {
            select(UsingUsers.id).from(UsingUsers).innerJoinUsing(UsingOrganizations) {}
        }
        assertFailsWith<IllegalArgumentException> {
            select(UsingUsers.id).from(UsingUsers).innerJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
                using(UsingUsers.organizationId, UsingOrganizations.organizationId)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            select(UsingUsers.id).from(UsingUsers).innerJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOrganizations.id)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            select(UsingUsers.id).from(UsingUsers).innerJoinUsing(UsingOrganizations) {
                using(UsingOther.organizationId, UsingOrganizations.organizationId)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            select(UsingUsers.id).from(UsingUsers).innerJoinUsing(UsingOrganizations) {
                using(UsingUsers.organizationId, UsingOther.organizationId)
            }
        }
    }
}

private object UsingUsers : Table("using_users") {
    val id = long("id")
    val tenantId = long("tenant_id")
    val organizationId = long("organization_id").nullable()
    val key = string("organization_key")
}

private object UsingOrganizations : Table("using_organizations") {
    val id = long("id")
    val tenantId = long("tenant_id")
    val organizationId = long("organization_id")
    val key = string("source_key")
    val name = string("name")
}

private object UsingOther : Table("using_other") {
    val organizationId = long("organization_id")
}

private object UsingOrders : Table("using_orders") {
    val id = long("id")
    val organizationId = long("organization_id")
    val region = string("region")
}
