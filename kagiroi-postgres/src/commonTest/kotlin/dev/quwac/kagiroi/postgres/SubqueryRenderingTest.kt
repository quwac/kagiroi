package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.exists
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.notExists
import dev.quwac.kagiroi.sql.notInSubquery
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubqueryRenderingTest {
    @Test
    fun rendersNonRecursiveCtesInDefinitionOrder() {
        val activeOwners = select(PostgresSubqueryOwners.id)
            .from(PostgresSubqueryOwners)
            .where(PostgresSubqueryOwners.active eq true)
            .asCte("active_owners")
        val activeOwnerId = activeOwners[PostgresSubqueryOwners.id]
        val recentOwners = select(activeOwnerId)
            .from(activeOwners)
            .where(activeOwnerId gt 0L)
            .asCte("recent_owners")
        val recentOwnerId = recentOwners[activeOwnerId]
        val rendered = SqlRenderer(PostgresDialect).render(
            with(activeOwners, recentOwners) {
                select(recentOwnerId).from(recentOwners).where(recentOwnerId gt 1L)
            },
        )

        assertTrue(rendered.sql.startsWith("WITH \"active_owners\" AS (SELECT"))
        assertTrue(rendered.sql.contains(", \"recent_owners\" AS (SELECT"))
        assertTrue(rendered.sql.contains("FROM \"recent_owners\" WHERE \"recent_owners\".\"id\" > ${'$'}3"))
        assertEquals(listOf<Any>(true, 0L, 1L), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersScalarPredicatesCorrelationAndDerivedTables() {
        val latest = select(PostgresSubqueryRows.value)
            .from(PostgresSubqueryRows)
            .where(PostgresSubqueryRows.ownerId eq PostgresSubqueryOwners.id)
            .limit(1)
            .scalar()
        val eligible = select(PostgresSubqueryRows.ownerId)
            .from(PostgresSubqueryRows)
            .where(PostgresSubqueryRows.value gt 10L)
        val correlated = select(PostgresSubqueryRows.id)
            .from(PostgresSubqueryRows)
            .where(PostgresSubqueryRows.ownerId eq PostgresSubqueryOwners.id)
        val eligibleOwner = PostgresSubqueryOwners.id.inSubquery(eligible) and
            PostgresSubqueryOwners.id.notInSubquery(eligible)
        val hasEligibleOrder = eligibleOwner and (exists(correlated) and notExists(correlated))
        val query = select(PostgresSubqueryOwners.id, latest.alias("latest_value"))
            .from(PostgresSubqueryOwners)
            .where(hasEligibleOrder)
        val activeOwners = select(PostgresSubqueryOwners.id)
            .from(PostgresSubqueryOwners)
            .where(PostgresSubqueryOwners.active eq true)
            .asTable("active_owners")
        val activeOwnerId = activeOwners[PostgresSubqueryOwners.id]
        val derived = select(activeOwnerId).from(activeOwners).where(activeOwnerId gt 0L)

        val rendered = SqlRenderer(PostgresDialect).render(query)
        val derivedRendered = SqlRenderer(PostgresDialect).render(derived)

        assertTrue(rendered.sql.contains("IN (SELECT"))
        assertTrue(rendered.sql.contains("NOT IN (SELECT"))
        assertTrue(rendered.sql.contains("EXISTS (SELECT"))
        assertTrue(rendered.sql.contains("NOT EXISTS (SELECT"))
        assertTrue(rendered.sql.contains("AS \"latest_value\""))
        assertEquals(listOf<Any>(1, 10L, 10L), rendered.parameters.map { it.value })
        assertTrue(derivedRendered.sql.contains("FROM (SELECT"))
        assertTrue(derivedRendered.sql.contains("AS \"active_owners\""))
        assertEquals(listOf(true, 0L), derivedRendered.parameters.map { it.value })
    }
}

private object PostgresSubqueryOwners : Table("subquery_owners") {
    val id = long("id")
    val active = boolean("active")
}

private object PostgresSubqueryRows : Table("subquery_rows") {
    val id = long("id")
    val ownerId = long("owner_id")
    val value = long("value")
}
