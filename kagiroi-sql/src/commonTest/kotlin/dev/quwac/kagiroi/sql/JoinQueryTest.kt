package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.coalesce
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JoinQueryTest {
    @Test
    fun rendersInnerJoinWithRawTableColumns() {
        val query = select(JoinUsers.id, JoinOrganizations.name)
            .from(JoinUsers)
            .innerJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)

        assertEquals(
            "SELECT \"join_users\".\"id\", \"join_organizations\".\"name\" FROM \"join_users\" " +
                "INNER JOIN \"join_organizations\" ON \"join_users\".\"organization_id\" = " +
                "\"join_organizations\".\"id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }

    @Test
    fun rendersOrderedCrossJoinsWithoutOnAndKeepsColumnsNonNull() {
        val organizationName: SqlExpression<String> = JoinOrganizations.name
        val query = select(JoinUsers.id, organizationName, JoinOrders.id)
            .from(JoinUsers)
            .crossJoin(JoinOrganizations)
            .crossJoin(JoinOrders)
            .where(JoinOrganizations.active eq true)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(false, organizationName.isNullable)
        assertEquals(
            "SELECT \"join_users\".\"id\", \"join_organizations\".\"name\", \"join_orders\".\"id\" " +
                "FROM \"join_users\" CROSS JOIN \"join_organizations\" CROSS JOIN \"join_orders\" " +
                "WHERE \"join_organizations\".\"active\" = ?",
            rendered.sql,
        )
        assertEquals(listOf(true), rendered.parameters.map { it.value })
    }

    @Test
    fun supportsCrossJoinAliasesAndConditionsForLaterJoins() {
        val employee = JoinUsers.alias("employee")
        val coworker = JoinUsers.alias("coworker")
        val coworkerName: SqlExpression<String> = coworker[JoinUsers.name]
        val query = select(employee[JoinUsers.id], coworkerName, JoinProducts.id)
            .from(employee)
            .crossJoin(coworker)
            .innerJoin(JoinProducts, on = coworker[JoinUsers.id] eq JoinProducts.id)

        assertEquals(
            "SELECT \"employee\".\"id\", \"coworker\".\"name\", \"join_products\".\"id\" " +
                "FROM \"join_users\" AS \"employee\" CROSS JOIN \"join_users\" AS \"coworker\" " +
                "INNER JOIN \"join_products\" ON \"coworker\".\"id\" = \"join_products\".\"id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }

    @Test
    fun leftJoinUsesAStaticallyNullableRightReference() {
        val userId = JoinUsers[JoinUsers.id]
        val organizationName = JoinOrganizations.name.optional
        val normalizedOrganizationName: SqlExpression<String?> = lower(organizationName)
        val displayName: SqlExpression<String> = coalesce(organizationName, JoinUsers[JoinUsers.name])
        val query = select(userId, normalizedOrganizationName, displayName)
            .from(JoinUsers)
            .leftJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)
            .where(JoinOrganizations.active eq true)
            .orderBy(organizationName.asc())

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(false, userId.isNullable)
        assertEquals(true, organizationName.isNullable)
        assertEquals(true, normalizedOrganizationName.isNullable)
        assertEquals(false, displayName.isNullable)
        assertEquals(
            "SELECT \"join_users\".\"id\", LOWER(\"join_organizations\".\"name\"), " +
                "COALESCE(\"join_organizations\".\"name\", \"join_users\".\"name\") FROM \"join_users\" " +
                "LEFT JOIN \"join_organizations\" ON \"join_users\".\"organization_id\" = " +
                "\"join_organizations\".\"id\" WHERE \"join_organizations\".\"active\" = ? " +
                "ORDER BY \"join_organizations\".\"name\" ASC",
            rendered.sql,
        )
        assertEquals(listOf(true), rendered.parameters.map { it.value })
    }

    @Test
    fun rightAndFullJoinRequireNullableViewsForPreservedOuterSides() {
        val rightQuery = select(JoinUsers.id.optional, JoinOrganizations[JoinOrganizations.name])
            .from(JoinUsers)
            .rightJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)
        val fullQuery = select(
            JoinUsers.id.optional,
            JoinOrganizations.name.optional,
        ).from(JoinUsers)
            .fullOuterJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)

        assertEquals(
            "SELECT \"join_users\".\"id\", \"join_organizations\".\"name\" FROM \"join_users\" " +
                "RIGHT JOIN \"join_organizations\" ON \"join_users\".\"organization_id\" = " +
                "\"join_organizations\".\"id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(rightQuery).sql,
        )
        assertEquals(
            "SELECT \"join_users\".\"id\", \"join_organizations\".\"name\" FROM \"join_users\" " +
                "FULL OUTER JOIN \"join_organizations\" ON \"join_users\".\"organization_id\" = " +
                "\"join_organizations\".\"id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(fullQuery).sql,
        )
    }

    @Test
    fun supportsMultipleJoinsAndParameterizedOnConditions() {
        val query = select(JoinOrders.id, JoinUsers.name, JoinOrganizations.name.optional)
            .from(JoinOrders)
            .innerJoin(JoinUsers, on = JoinOrders.userId eq JoinUsers.id)
            .leftJoin(
                JoinOrganizations,
                on = (JoinUsers.organizationId eq JoinOrganizations.id) and (JoinOrganizations.region eq "JP"),
            )

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"join_orders\".\"id\", \"join_users\".\"name\", \"join_organizations\".\"name\" " +
                "FROM \"join_orders\" INNER JOIN \"join_users\" ON \"join_orders\".\"user_id\" = " +
                "\"join_users\".\"id\" LEFT JOIN \"join_organizations\" ON " +
                "(\"join_users\".\"organization_id\" = \"join_organizations\".\"id\" AND " +
                "\"join_organizations\".\"region\" = ?)",
            rendered.sql,
        )
        assertEquals(listOf("JP"), rendered.parameters.map { it.value })
    }

    @Test
    fun supportsSelfJoinWithTableAliasesAndTypedOuterResult() {
        val employee = JoinUsers.alias("employee")
        val manager = JoinUsers.alias("manager")
        val employeeName = employee[JoinUsers.name]
        val managerName: SqlExpression<String?> = manager[JoinUsers.name].optional
        val query = select(employeeName, managerName)
            .from(employee)
            .leftJoin(manager, on = employee[JoinUsers.managerId] eq manager[JoinUsers.id])

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)
        val row = sqlRow(listOf(employeeName, managerName), listOf("Ada", null))

        assertEquals(
            "SELECT \"employee\".\"name\", \"manager\".\"name\" FROM \"join_users\" AS \"employee\" " +
                "LEFT JOIN \"join_users\" AS \"manager\" ON \"employee\".\"manager_id\" = \"manager\".\"id\"",
            rendered.sql,
        )
        assertEquals("Ada", row[employee[JoinUsers.name]])
        assertEquals(null, row[manager[JoinUsers.name].optional])
    }

    @Test
    fun rejectsUnrelatedConditionsDuplicateSourcesAndUnselectedSources() {
        val unrelated = select(JoinUsers.id)
            .from(JoinUsers)
        assertFailsWith<IllegalArgumentException> {
            unrelated.innerJoin(
                JoinOrganizations,
                on = JoinProducts.id eq JoinProducts.organizationId,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            unrelated.innerJoin(JoinUsers, on = JoinUsers.id eq JoinUsers.id)
        }
        assertFailsWith<IllegalArgumentException> {
            unrelated.crossJoin(JoinUsers)
        }
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(JoinProducts.id).from(JoinUsers))
        }
    }

    @Test
    fun rejectsNonNullableProjectionFromOuterSide() {
        val query = select(JoinUsers.id, JoinOrganizations.name)
            .from(JoinUsers)
            .leftJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)

        val failure = assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)
        }

        assertTrue(failure.message.orEmpty().contains("relation[column].optional"))
    }
}

private object JoinUsers : Table("join_users") {
    val id = long("id")
    val organizationId = long("organization_id").nullable()
    val managerId = long("manager_id").nullable()
    val name = string("name")
}

private object JoinOrganizations : Table("join_organizations") {
    val id = long("id")
    val name = string("name")
    val active = boolean("active")
    val region = string("region")
}

private object JoinOrders : Table("join_orders") {
    val id = long("id")
    val userId = long("user_id")
}

private object JoinProducts : Table("join_products") {
    val id = long("id")
    val organizationId = long("organization_id")
}
