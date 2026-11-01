package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubqueryTest {
    @Test
    fun scalarSubqueryPreservesTheProjectionTypeAndAllowsCorrelation() {
        val latestAmount: SqlExpression<Int?> = select(SubqueryOrders.amount)
            .from(SubqueryOrders)
            .where(SubqueryOrders.userId eq SubqueryUsers.id)
            .orderBy(SubqueryOrders.createdAt.desc())
            .limit(1)
            .scalar()
        val query = select(
            SubqueryUsers.id,
            latestAmount.alias("latest_amount"),
        ).from(SubqueryUsers)
            .where(SubqueryUsers.active eq true)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertTrue(latestAmount.isNullable)
        assertEquals(
            "SELECT \"subquery_users\".\"id\", (SELECT \"subquery_orders\".\"amount\" " +
                "FROM \"subquery_orders\" WHERE \"subquery_orders\".\"user_id\" = \"subquery_users\".\"id\" " +
                "ORDER BY \"subquery_orders\".\"created_at\" DESC LIMIT ?) AS \"latest_amount\" " +
                "FROM \"subquery_users\" WHERE \"subquery_users\".\"active\" = ?",
            rendered.sql,
        )
        assertEquals(listOf(1, true), rendered.parameters.map { it.value })
    }

    @Test
    fun comparesAgainstACorrelatedScalarSubqueryWithoutAnImplicitLimit() {
        val latestAmount = select(SubqueryOrders.amount)
            .from(SubqueryOrders)
            .where(SubqueryOrders.userId eq SubqueryUsers.id)
            .scalar()
        val query = select(SubqueryUsers.id)
            .from(SubqueryUsers)
            .where(SubqueryUsers.score gt latestAmount)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"subquery_users\".\"id\" FROM \"subquery_users\" WHERE \"subquery_users\".\"score\" > " +
                "(SELECT \"subquery_orders\".\"amount\" FROM \"subquery_orders\" WHERE " +
                "\"subquery_orders\".\"user_id\" = \"subquery_users\".\"id\")",
            rendered.sql,
        )
        assertTrue(rendered.parameters.isEmpty())
    }

    @Test
    fun rendersInAndNotInWithOneSharedParameterSequence() {
        val eligibleUserIds = select(SubqueryOrders.userId)
            .from(SubqueryOrders)
            .where(SubqueryOrders.amount gt 100)
        val excludedUserIds = select(SubqueryOrders.userId)
            .from(SubqueryOrders)
            .where(SubqueryOrders.amount lt 10)
        val eligibleUser = SubqueryUsers.id.inSubquery(eligibleUserIds) and
            SubqueryUsers.id.notInSubquery(excludedUserIds)
        val predicate = eligibleUser and (SubqueryUsers.score gt 50)
        val query = select(SubqueryUsers.id)
            .from(SubqueryUsers)
            .where(predicate)

        val rendered = SqlRenderer(NumberedSubqueryDialect).render(query)

        assertEquals(
            "SELECT `subquery_users`.`id` FROM `subquery_users` WHERE " +
                "((`subquery_users`.`id` IN (SELECT `subquery_orders`.`user_id` FROM `subquery_orders` " +
                "WHERE `subquery_orders`.`amount` > $1) AND `subquery_users`.`id` NOT IN " +
                "(SELECT `subquery_orders`.`user_id` FROM `subquery_orders` WHERE `subquery_orders`.`amount` < $2)) " +
                "AND `subquery_users`.`score` > $3)",
            rendered.sql,
        )
        assertEquals(listOf(100, 10, 50), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersExistsNotExistsAndNestedCorrelation() {
        val paidOrder = select(SubqueryPayments.id)
            .from(SubqueryPayments)
            .where(
                (SubqueryPayments.orderId eq SubqueryOrders.id) and
                    (SubqueryPayments.userId eq SubqueryUsers.id),
            )
        val hasPaidOrder = select(SubqueryOrders.id)
            .from(SubqueryOrders)
            .where(
                (SubqueryOrders.userId eq SubqueryUsers.id) and exists(paidOrder),
            )
        val hasNoBlockedOrder = select(SubqueryOrders.id)
            .from(SubqueryOrders)
            .where(
                (SubqueryOrders.userId eq SubqueryUsers.id) and (SubqueryOrders.blocked eq true),
            )
        val query = select(SubqueryUsers.id)
            .from(SubqueryUsers)
            .where(exists(hasPaidOrder) and notExists(hasNoBlockedOrder))

        assertEquals(
            "SELECT \"subquery_users\".\"id\" FROM \"subquery_users\" WHERE " +
                "(EXISTS (SELECT \"subquery_orders\".\"id\" FROM \"subquery_orders\" WHERE " +
                "(\"subquery_orders\".\"user_id\" = \"subquery_users\".\"id\" AND EXISTS " +
                "(SELECT \"subquery_payments\".\"id\" FROM \"subquery_payments\" WHERE " +
                "(\"subquery_payments\".\"order_id\" = \"subquery_orders\".\"id\" AND " +
                "\"subquery_payments\".\"user_id\" = \"subquery_users\".\"id\")))) AND " +
                "NOT EXISTS (SELECT \"subquery_orders\".\"id\" FROM \"subquery_orders\" WHERE " +
                "(\"subquery_orders\".\"user_id\" = \"subquery_users\".\"id\" AND " +
                "\"subquery_orders\".\"blocked\" = ?)))",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }

    @Test
    fun usesTypedDerivedProjectionReferencesInFromWhereAndJoin() {
        val normalizedName = lower(SubqueryUsers.name).alias("normalized_name")
        val activeUsers = select(SubqueryUsers.id, normalizedName)
            .from(SubqueryUsers)
            .where(SubqueryUsers.active eq true)
            .asTable("active_users")
        val activeUserId = activeUsers[SubqueryUsers.id]
        val activeUserName = activeUsers[normalizedName]
        val typedActiveUserId: SqlExpression<Long> = activeUserId
        val typedActiveUserName: SqlExpression<String> = activeUserName
        val query = select(SubqueryOrders.id, activeUserName.optional)
            .from(SubqueryOrders)
            .leftJoin(activeUsers, on = SubqueryOrders.userId eq activeUserId)
            .where(activeUserName eq "ada")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(false, typedActiveUserId.isNullable)
        assertEquals(false, typedActiveUserName.isNullable)
        assertEquals(
            "SELECT \"subquery_orders\".\"id\", \"active_users\".\"normalized_name\" FROM " +
                "\"subquery_orders\" LEFT JOIN (SELECT \"subquery_users\".\"id\", " +
                "LOWER(\"subquery_users\".\"name\") AS \"normalized_name\" FROM \"subquery_users\" " +
                "WHERE \"subquery_users\".\"active\" = ?) AS \"active_users\" ON " +
                "\"subquery_orders\".\"user_id\" = \"active_users\".\"id\" WHERE " +
                "\"active_users\".\"normalized_name\" = ?",
            rendered.sql,
        )
        assertEquals(listOf(true, "ada"), rendered.parameters.map { it.value })
    }

    @Test
    fun usesTypedDerivedProjectionReferencesInCrossJoin() {
        val activeUsers = select(SubqueryUsers.id)
            .from(SubqueryUsers)
            .where(SubqueryUsers.active eq true)
            .asTable("active_users")
        val activeUserId: SqlExpression<Long> = activeUsers[SubqueryUsers.id]
        val query = select(SubqueryOrders.id, activeUserId)
            .from(SubqueryOrders)
            .crossJoin(activeUsers)
            .where(activeUserId gt 10L)

        assertEquals(
            "SELECT \"subquery_orders\".\"id\", \"active_users\".\"id\" FROM \"subquery_orders\" " +
                "CROSS JOIN (SELECT \"subquery_users\".\"id\" FROM \"subquery_users\" " +
                "WHERE \"subquery_users\".\"active\" = ?) AS \"active_users\" " +
                "WHERE \"active_users\".\"id\" > ?",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }

    @Test
    fun requiresNamedUniqueDerivedProjectionOutputs() {
        assertFailsWith<IllegalArgumentException> {
            select(SubqueryUsers.id, SubqueryOrders.id)
                .from(SubqueryUsers)
                .asTable("duplicate_ids")
        }
        assertFailsWith<IllegalArgumentException> {
            select(lower(SubqueryUsers.name))
                .from(SubqueryUsers)
                .asTable("unnamed_expression")
        }
    }

    @Test
    fun rejectsUnboundCorrelationWhenRenderedAsARootQuery() {
        val unbound = select(SubqueryOrders.id)
            .from(SubqueryOrders)
            .where(SubqueryOrders.userId eq SubqueryUsers.id)

        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(unbound)
        }
    }
}

private object SubqueryUsers : Table("subquery_users") {
    val id = long("id")
    val name = string("name")
    val active = boolean("active")
    val score = int("score")
}

private object SubqueryOrders : Table("subquery_orders") {
    val id = long("id")
    val userId = long("user_id")
    val amount = int("amount")
    val createdAt = long("created_at")
    val blocked = boolean("blocked")
}

private object SubqueryPayments : Table("subquery_payments") {
    val id = long("id")
    val orderId = long("order_id")
    val userId = long("user_id")
}

private object NumberedSubqueryDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override fun parameterMarker(position: Int): String = "$$position"
}
