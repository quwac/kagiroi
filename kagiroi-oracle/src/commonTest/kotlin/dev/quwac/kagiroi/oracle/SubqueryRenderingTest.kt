package dev.quwac.kagiroi.oracle

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
        val activeOwners = select(OracleSubqueryOwners.id)
            .from(OracleSubqueryOwners)
            .where(OracleSubqueryOwners.active eq true)
            .asCte("active_owners")
        val activeOwnerId = activeOwners[OracleSubqueryOwners.id]
        val recentOwners = select(activeOwnerId)
            .from(activeOwners)
            .where(activeOwnerId gt 0L)
            .asCte("recent_owners")
        val recentOwnerId = recentOwners[activeOwnerId]
        val rendered = SqlRenderer(OracleDialect).render(
            with(activeOwners, recentOwners) {
                select(recentOwnerId).from(recentOwners).where(recentOwnerId gt 1L)
            },
        )

        assertTrue(rendered.sql.startsWith("WITH \"active_owners\" AS (SELECT"))
        assertTrue(rendered.sql.contains(", \"recent_owners\" AS (SELECT"))
        assertTrue(rendered.sql.contains("FROM \"recent_owners\" WHERE \"recent_owners\".\"id\" > :p3"))
        assertEquals(listOf<Any>(true, 0L, 1L), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersScalarPredicatesCorrelationAndDerivedTables() {
        val latest = select(OracleSubqueryRows.value)
            .from(OracleSubqueryRows)
            .where(OracleSubqueryRows.ownerId eq OracleSubqueryOwners.id)
            .limit(1)
            .scalar()
        val eligible = select(OracleSubqueryRows.ownerId)
            .from(OracleSubqueryRows)
            .where(OracleSubqueryRows.value gt 10L)
        val correlated = select(OracleSubqueryRows.id)
            .from(OracleSubqueryRows)
            .where(OracleSubqueryRows.ownerId eq OracleSubqueryOwners.id)
        val query = select(OracleSubqueryOwners.id, latest.alias("latest_value"))
            .from(OracleSubqueryOwners)
            .where(
                (OracleSubqueryOwners.id.inSubquery(eligible) and OracleSubqueryOwners.id.notInSubquery(eligible)) and
                    (exists(correlated) and notExists(correlated)),
            )
        val activeOwners = select(OracleSubqueryOwners.id)
            .from(OracleSubqueryOwners)
            .where(OracleSubqueryOwners.active eq true)
            .asTable("active_owners")
        val activeOwnerId = activeOwners[OracleSubqueryOwners.id]
        val derived = select(activeOwnerId).from(activeOwners).where(activeOwnerId gt 0L)

        val rendered = SqlRenderer(OracleDialect).render(query)
        val derivedRendered = SqlRenderer(OracleDialect).render(derived)

        assertTrue(rendered.sql.contains("IN (SELECT"))
        assertTrue(rendered.sql.contains("NOT IN (SELECT"))
        assertTrue(rendered.sql.contains("EXISTS (SELECT"))
        assertTrue(rendered.sql.contains("NOT EXISTS (SELECT"))
        assertTrue(rendered.sql.contains("AS \"latest_value\""))
        assertEquals(listOf<Any>(1, 10L, 10L), rendered.parameters.map { it.value })
        assertTrue(derivedRendered.sql.contains("FROM (SELECT"))
        assertTrue(derivedRendered.sql.contains(") \"active_owners\""))
        assertEquals(listOf(true, 0L), derivedRendered.parameters.map { it.value })
    }
}

private object OracleSubqueryOwners : Table("subquery_owners") {
    val id = long("id")
    val active = boolean("active")
}

private object OracleSubqueryRows : Table("subquery_rows") {
    val id = long("id")
    val ownerId = long("owner_id")
    val value = long("value")
}
