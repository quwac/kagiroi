package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.sum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommonTableExpressionTest {
    @Test
    fun sharesTypedProjectionRelationsWithDerivedTables() {
        val normalizedEmail = lower(CteUsers.email).alias("normalized_email")
        val derived = select(CteUsers.id, normalizedEmail).from(CteUsers).asTable("derived_users")
        val cte = select(CteUsers.id, normalizedEmail).from(CteUsers).asCte("active_users")
        val cteId = cte[CteUsers.id]
        val cteEmail = cte[normalizedEmail]
        val typedCteId: SqlExpression<Long> = cteId
        val typedCteEmail: SqlExpression<String> = cteEmail
        val query = with(cte) {
            select(cteId, cteEmail).from(cte).where(cteEmail eq "ada@example.com")
        }

        assertIs<QueryResultRelation>(derived)
        assertIs<QueryResultRelation>(cte)
        assertTrue(!typedCteId.isNullable)
        assertTrue(!typedCteEmail.isNullable)
        assertEquals(
            "WITH \"active_users\" AS (SELECT \"cte_users\".\"id\", " +
                "LOWER(\"cte_users\".\"email\") AS \"normalized_email\" FROM \"cte_users\") " +
                "SELECT \"active_users\".\"id\", \"active_users\".\"normalized_email\" FROM " +
                "\"active_users\" WHERE \"active_users\".\"normalized_email\" = ?",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
        val row = sqlRow(listOf(cteId, cteEmail), listOf<Any?>(7L, "ada@example.com"))
        assertEquals(7L, row[cteId])
        assertEquals("ada@example.com", row[cteEmail])
    }

    @Test
    fun supportsCteRelationsInCrossJoin() {
        val activeUsers = select(CteUsers.id)
            .from(CteUsers)
            .where(CteUsers.active eq true)
            .asCte("active_users")
        val activeUserId: SqlExpression<Long> = activeUsers[CteUsers.id]
        val query = with(activeUsers) {
            select(CteOrders.id, activeUserId)
                .from(CteOrders)
                .crossJoin(activeUsers)
                .where(activeUserId gt 10L)
        }

        assertEquals(
            "WITH \"active_users\" AS (SELECT \"cte_users\".\"id\" FROM \"cte_users\" " +
                "WHERE \"cte_users\".\"active\" = ?) SELECT \"cte_orders\".\"id\", " +
                "\"active_users\".\"id\" FROM \"cte_orders\" CROSS JOIN \"active_users\" " +
                "WHERE \"active_users\".\"id\" > ?",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }

    @Test
    fun preservesDefinitionOrderDependenciesAndOneParameterSequence() {
        val activeUsers = select(CteUsers.id, CteUsers.name)
            .from(CteUsers)
            .where(CteUsers.active eq true)
            .asCte("active_users")
        val activeUserId = activeUsers[CteUsers.id]
        val activeUserName = activeUsers[CteUsers.name]
        val recentUsers = select(activeUserId, activeUserName)
            .from(activeUsers)
            .where(activeUserId gt 10L)
            .asCte("recent_users")
        val recentUserId: SqlExpression<Long> = recentUsers[activeUserId]
        val recentUserName: SqlExpression<String> = recentUsers[activeUserName]
        val query = with(activeUsers, recentUsers) {
            select(recentUserId, recentUserName)
                .from(recentUsers)
                .where(recentUserName eq "Ada")
                .orderBy(recentUserId.desc())
                .limit(5)
        }

        val rendered = SqlRenderer(NumberedCteDialect).render(query)

        assertEquals(
            "WITH `active_users` AS (SELECT `cte_users`.`id`, `cte_users`.`name` FROM `cte_users` " +
                "WHERE `cte_users`.`active` = $1), `recent_users` AS (SELECT `active_users`.`id`, " +
                "`active_users`.`name` FROM `active_users` WHERE `active_users`.`id` > $2) " +
                "SELECT `recent_users`.`id`, `recent_users`.`name` FROM `recent_users` " +
                "WHERE `recent_users`.`name` = $3 ORDER BY `recent_users`.`id` DESC LIMIT $4",
            rendered.sql,
        )
        assertEquals(listOf<Any>(true, 10L, "Ada", 5), rendered.parameters.map { it.value })
    }

    @Test
    fun supportsGroupedAndSubqueryBodiesWithoutChangingTheirScopeRules() {
        val totalProjection = sum(CteOrders.amount).alias("total")
        val qualifyingOrders = select(CteOrders.organizationId, totalProjection)
            .from(CteOrders)
            .where(
                exists(
                    select(CtePayments.id)
                        .from(CtePayments)
                        .where(CtePayments.orderId eq CteOrders.id),
                ),
            )
            .groupBy(CteOrders.organizationId)
            .having(totalProjection gt 20L)
            .asCte("qualifying_orders")
        val organizationId = qualifyingOrders[CteOrders.organizationId]
        val total: SqlExpression<Long?> = qualifyingOrders[totalProjection]
        val query = with(qualifyingOrders) {
            select(organizationId, total)
                .from(qualifyingOrders)
                .where(total gt 30L)
                .orderBy(total.desc())
                .limit(2)
        }

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertTrue(rendered.sql.contains("EXISTS (SELECT \"cte_payments\".\"id\""))
        assertTrue(rendered.sql.contains("GROUP BY \"cte_orders\".\"organization_id\""))
        assertTrue(rendered.sql.contains("HAVING SUM(\"cte_orders\".\"amount\") > ?"))
        assertEquals(listOf<Any>(20L, 30L, 2), rendered.parameters.map { it.value })
    }

    @Test
    fun rejectsInvalidNamesDuplicateNamesAndUnboundOrForwardReferences() {
        assertFailsWith<IllegalArgumentException> {
            select(CteUsers.id).from(CteUsers).asCte(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            select(CteUsers.id, CteOrders.id).from(CteUsers).asCte("duplicate_columns")
        }

        val first = select(CteUsers.id).from(CteUsers).asCte("users")
        val duplicate = select(CteOrders.id).from(CteOrders).asCte("users")
        val second = select(first[CteUsers.id]).from(first).asCte("second")
        val unbound = select(first[CteUsers.id]).from(first)
        val correlated = select(CteOrders.id)
            .from(CteOrders)
            .where(CteOrders.userId eq CteUsers.id)
            .asCte("correlated")
        val shadowing = select(CteUsers.id).from(CteUsers).asCte("cte_users")

        assertFailsWith<IllegalArgumentException> { with(first, duplicate) { select(first[CteUsers.id]).from(first) } }
        assertFailsWith<IllegalArgumentException> { with(second, first) { select(second[CteUsers.id]).from(second) } }
        assertFailsWith<IllegalArgumentException> { SqlRenderer(DoubleQuotedQuestionMarkDialect).render(unbound) }
        assertFailsWith<IllegalArgumentException> { with(correlated) { select(CteUsers.id).from(CteUsers) } }
        assertFailsWith<IllegalArgumentException> { with(shadowing) { select(CteUsers.id).from(CteUsers) } }
    }
}

private object CteUsers : Table("cte_users") {
    val id = long("id")
    val name = string("name")
    val email = string("email")
    val active = boolean("active")
}

private object CteOrders : Table("cte_orders") {
    val id = long("id")
    val organizationId = long("organization_id")
    val userId = long("user_id")
    val amount = long("amount")
}

private object CtePayments : Table("cte_payments") {
    val id = long("id")
    val orderId = long("order_id")
}

private object NumberedCteDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override fun parameterMarker(position: Int): String = "$$position"
}
