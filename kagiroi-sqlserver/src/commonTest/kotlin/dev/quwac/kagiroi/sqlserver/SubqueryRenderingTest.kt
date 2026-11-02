package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.exists
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.notExists
import dev.quwac.kagiroi.sql.notInSubquery
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubqueryRenderingTest {
    @Test
    fun rendersNonRecursiveCtesInDefinitionOrder() {
        val activeOwners = select(SqlServerSubqueryOwners.id)
            .from(SqlServerSubqueryOwners)
            .where(SqlServerSubqueryOwners.active eq true)
            .asCte("active_owners")
        val activeOwnerId = activeOwners[SqlServerSubqueryOwners.id]
        val recentOwners = select(activeOwnerId)
            .from(activeOwners)
            .where(activeOwnerId gt 0L)
            .asCte("recent_owners")
        val recentOwnerId = recentOwners[activeOwnerId]
        val rendered = SqlRenderer(SqlServerDialect).render(
            with(activeOwners, recentOwners) {
                select(recentOwnerId).from(recentOwners).where(recentOwnerId gt 1L)
            },
        )

        assertTrue(rendered.sql.startsWith("WITH [active_owners] AS (SELECT"))
        assertTrue(rendered.sql.contains(", [recent_owners] AS (SELECT"))
        assertTrue(rendered.sql.contains("FROM [recent_owners] WHERE [recent_owners].[id] > @kb3"))
        assertEquals(listOf<Any>(true, 0L, 1L), rendered.parameters.map { it.value })
    }

    @Test
    fun rejectsBareOrderByInASubquery() {
        val orderedValue = select(SqlServerSubqueryRows.value)
            .from(SqlServerSubqueryRows)
            .orderBy(SqlServerSubqueryRows.value.desc())
            .scalar()
        val query = select(SqlServerSubqueryOwners.id, orderedValue.alias("ordered_value"))
            .from(SqlServerSubqueryOwners)

        assertFailsWith<IllegalArgumentException> { SqlRenderer(SqlServerDialect).render(query) }
    }

    @Test
    fun rendersScalarPredicatesCorrelationAndDerivedTables() {
        val latest = select(SqlServerSubqueryRows.value)
            .from(SqlServerSubqueryRows)
            .where(SqlServerSubqueryRows.ownerId eq SqlServerSubqueryOwners.id)
            .limit(1)
            .scalar()
        val eligible = select(SqlServerSubqueryRows.ownerId)
            .from(SqlServerSubqueryRows)
            .where(SqlServerSubqueryRows.value gt 10L)
        val correlated = select(SqlServerSubqueryRows.id)
            .from(SqlServerSubqueryRows)
            .where(SqlServerSubqueryRows.ownerId eq SqlServerSubqueryOwners.id)
        val eligibleOwner = SqlServerSubqueryOwners.id.inSubquery(eligible) and
            SqlServerSubqueryOwners.id.notInSubquery(eligible)
        val hasEligibleOrder = eligibleOwner and (exists(correlated) and notExists(correlated))
        val query = select(SqlServerSubqueryOwners.id, latest.alias("latest_value"))
            .from(SqlServerSubqueryOwners)
            .where(hasEligibleOrder)
        val activeOwners = select(SqlServerSubqueryOwners.id)
            .from(SqlServerSubqueryOwners)
            .where(SqlServerSubqueryOwners.active eq true)
            .asTable("active_owners")
        val activeOwnerId = activeOwners[SqlServerSubqueryOwners.id]
        val derived = select(activeOwnerId).from(activeOwners).where(activeOwnerId gt 0L)

        val rendered = SqlRenderer(SqlServerDialect).render(query)
        val derivedRendered = SqlRenderer(SqlServerDialect).render(derived)

        assertTrue(rendered.sql.contains("IN (SELECT"))
        assertTrue(rendered.sql.contains("NOT IN (SELECT"))
        assertTrue(rendered.sql.contains("EXISTS (SELECT"))
        assertTrue(rendered.sql.contains("NOT EXISTS (SELECT"))
        assertTrue(rendered.sql.contains("AS [latest_value]"))
        assertEquals(listOf<Any>(1, 10L, 10L), rendered.parameters.map { it.value })
        assertTrue(derivedRendered.sql.contains("FROM (SELECT"))
        assertTrue(derivedRendered.sql.contains("AS [active_owners]"))
        assertEquals(listOf(true, 0L), derivedRendered.parameters.map { it.value })
    }
}

private object SqlServerSubqueryOwners : Table("subquery_owners") {
    val id = long("id")
    val active = boolean("active")
}

private object SqlServerSubqueryRows : Table("subquery_rows") {
    val id = long("id")
    val ownerId = long("owner_id")
    val value = long("value")
}
