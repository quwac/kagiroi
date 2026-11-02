package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LateralRelationTest {
    @Test
    fun crossLateralKeepsTypedProjectionNonNullAndRendersParametersInSqlOrder() {
        val latest = latestOrderFor(LateralUsers.id)
        val latestId: SqlExpression<Long> = latest[LateralOrders.id]
        val latestAmount: SqlExpression<Long> = latest[LateralOrders.amount]
        val query = select(LateralUsers.id, latestId, latestAmount)
            .from(LateralUsers)
            .crossJoinLateral(latest)
            .where(LateralUsers.active eq true)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(false, latestId.isNullable)
        assertEquals(false, latestAmount.isNullable)
        assertEquals(
            "SELECT \"lateral_users\".\"id\", \"latest\".\"id\", \"latest\".\"amount\" FROM " +
                "\"lateral_users\" CROSS JOIN LATERAL (SELECT \"lateral_orders\".\"id\", " +
                "\"lateral_orders\".\"amount\" FROM \"lateral_orders\" WHERE " +
                "(\"lateral_orders\".\"user_id\" = \"lateral_users\".\"id\" AND " +
                "\"lateral_orders\".\"amount\" > ?) ORDER BY \"lateral_orders\".\"created_at\" DESC " +
                "LIMIT ?) AS \"latest\" WHERE \"lateral_users\".\"active\" = ?",
            rendered.sql,
        )
        assertEquals(listOf<Any>(100L, 1, true), rendered.parameters.map { it.value })
        assertEquals(listOf(LateralUsers), latest.outerDependencies)
    }

    @Test
    fun leftLateralRequiresAnExplicitNullableProjectionAndOwnsNeutralOnSyntax() {
        val latest = latestOrderFor(LateralUsers.id)
        val optionalLatestId: SqlExpression<Long?> = latest[LateralOrders.id].optional
        val valid = select(LateralUsers.id, optionalLatestId)
            .from(LateralUsers)
            .leftJoinLateral(latest)
        val invalid = select(LateralUsers.id, latest[LateralOrders.id])
            .from(LateralUsers)
            .leftJoinLateral(latest)

        assertEquals(
            "SELECT \"lateral_users\".\"id\", \"latest\".\"id\" FROM \"lateral_users\" " +
                "LEFT JOIN LATERAL (SELECT \"lateral_orders\".\"id\", \"lateral_orders\".\"amount\" " +
                "FROM \"lateral_orders\" WHERE (\"lateral_orders\".\"user_id\" = " +
                "\"lateral_users\".\"id\" AND \"lateral_orders\".\"amount\" > ?) ORDER BY " +
                "\"lateral_orders\".\"created_at\" DESC LIMIT ?) AS \"latest\" ON TRUE",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(valid).sql,
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(invalid)
        }
        assertTrue(failure.message.orEmpty().contains("relation[column].optional"))
    }

    @Test
    fun validatesOnlySourcesAvailableToTheLeftAndRetainsNormalDerivedTableScope() {
        val latest = latestOrderFor(LateralUsers.id)
        val unrelated = assertFailsWith<IllegalArgumentException> {
            select(LateralOrganizations.id)
                .from(LateralOrganizations)
                .crossJoinLateral(latest)
        }
        assertTrue(unrelated.message.orEmpty().contains("not available to its left"))

        val future = lateral("future") {
            select(LateralOrders.id)
                .from(LateralOrders)
                .where(LateralOrders.organizationId eq LateralOrganizations.id)
        }
        val forward = assertFailsWith<IllegalArgumentException> {
            select(LateralUsers.id)
                .from(LateralUsers)
                .crossJoinLateral(future)
        }
        assertTrue(forward.message.orEmpty().contains("not available to its left"))

        val nonLateral = select(LateralOrders.id)
            .from(LateralOrders)
            .where(LateralOrders.userId eq LateralUsers.id)
            .asTable("not_lateral")
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(LateralUsers.id)
                    .from(LateralUsers)
                    .crossJoin(nonLateral),
            )
        }
    }

    @Test
    fun supportsAliasedAndMultiplePrecedingOuterSources() {
        val user = LateralUsers.alias("user")
        val organization = LateralOrganizations.alias("organization")
        val latest = lateral("latest") {
            select(LateralOrders.id)
                .from(LateralOrders)
                .where(
                    (LateralOrders.userId eq user[LateralUsers.id]) and
                        (LateralOrders.organizationId eq organization[LateralOrganizations.id]),
                )
        }
        val latestId: SqlExpression<Long> = latest[LateralOrders.id]
        val query = select(user[LateralUsers.id], latestId)
            .from(user)
            .innerJoin(organization, on = user[LateralUsers.organizationId] eq organization[LateralOrganizations.id])
            .crossJoinLateral(latest)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(listOf(user, organization), latest.outerDependencies)
        assertTrue(rendered.sql.contains("\"lateral_orders\".\"user_id\" = \"user\".\"id\""))
        assertTrue(rendered.sql.contains("\"lateral_orders\".\"organization_id\" = \"organization\".\"id\""))
    }

    @Test
    fun rejectsAmbiguousLateralAliasesAndOrdinaryJoinEntryPoints() {
        val sameName = lateral("lateral_users") { select(LateralOrders.id).from(LateralOrders) }
        assertFailsWith<IllegalArgumentException> {
            select(LateralUsers.id).from(LateralUsers).crossJoinLateral(sameName)
        }
        assertFailsWith<IllegalArgumentException> {
            select(LateralUsers.id).from(LateralUsers).crossJoin(sameName)
        }
    }

    @Test
    fun resolvesOuterWithCtesOnlyAfterTheLateralRelationIsAttachedToThatScope() {
        val activeOrders = select(LateralOrders.userId)
            .from(LateralOrders)
            .asCte("active_orders")
        val activeOrderUserId = activeOrders[LateralOrders.userId]
        val latest = lateral("latest") {
            select(activeOrderUserId)
                .from(activeOrders)
                .where(activeOrderUserId eq LateralUsers.id)
        }
        val latestUserId = latest[activeOrderUserId]
        val query = with(activeOrders) {
            select(LateralUsers.id, latestUserId)
                .from(LateralUsers)
                .crossJoinLateral(latest)
        }

        assertEquals(
            "WITH \"active_orders\" AS (SELECT \"lateral_orders\".\"user_id\" FROM \"lateral_orders\") " +
                "SELECT \"lateral_users\".\"id\", \"latest\".\"user_id\" FROM \"lateral_users\" " +
                "CROSS JOIN LATERAL (SELECT \"active_orders\".\"user_id\" FROM \"active_orders\" " +
                "WHERE \"active_orders\".\"user_id\" = \"lateral_users\".\"id\") AS \"latest\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }

    @Test
    fun supportsSetBodiesAndComposesIntoNonCorrelatedDerivedRelationsAndCtes() {
        val matchingOrderId = LateralOrders.id.alias("matching_id")
        val matching = lateral("matching") {
            select(matchingOrderId)
                .from(LateralOrders)
                .where(LateralOrders.userId eq LateralUsers.id)
                .unionAll(
                    select(matchingOrderId)
                        .from(LateralOrders)
                        .where(LateralOrders.userId eq LateralUsers.id),
                )
        }
        val matchingId = matching[matchingOrderId]
        val joined = select(LateralUsers.id, matchingId)
            .from(LateralUsers)
            .crossJoinLateral(matching)
        val derived = joined.asTable("joined_users")
        val derivedUserId: SqlExpression<Long> = derived[LateralUsers.id]
        val cte = joined.asCte("joined_users")
        val cteUserId: SqlExpression<Long> = cte[LateralUsers.id]

        val derivedSql = SqlRenderer(DoubleQuotedQuestionMarkDialect)
            .render(select(derivedUserId).from(derived))
            .sql
        val cteSql = SqlRenderer(DoubleQuotedQuestionMarkDialect)
            .render(with(cte) { select(cteUserId).from(cte) })
            .sql

        assertEquals(listOf(LateralUsers), matching.outerDependencies)
        assertTrue(derivedSql.contains("CROSS JOIN LATERAL"))
        assertTrue(derivedSql.contains("UNION ALL"))
        assertTrue(cteSql.startsWith("WITH \"joined_users\" AS (SELECT"))
    }

    private fun latestOrderFor(userId: SqlExpression<Long>) = lateral("latest") {
        select(LateralOrders.id, LateralOrders.amount)
            .from(LateralOrders)
            .where((LateralOrders.userId eq userId) and (LateralOrders.amount gt 100L))
            .orderBy(LateralOrders.createdAt.desc())
            .limit(1)
    }
}

private object LateralUsers : Table("lateral_users") {
    val id = long("id")
    val organizationId = long("organization_id")
    val active = boolean("active")
}

private object LateralOrganizations : Table("lateral_organizations") {
    val id = long("id")
}

private object LateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
    val organizationId = long("organization_id")
    val amount = long("amount")
    val createdAt = long("created_at")
}
