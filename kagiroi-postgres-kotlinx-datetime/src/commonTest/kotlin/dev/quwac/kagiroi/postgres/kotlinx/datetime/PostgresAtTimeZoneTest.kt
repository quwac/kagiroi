package dev.quwac.kagiroi.postgres.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.postgres.PostgresDialect
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.currentInstant
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresAtTimeZoneTest {
    @Test
    fun bindsZonesInQueriesAndInlinesThemInDefaults() {
        val dialect = PostgresDialect.configured(listOf(PostgresKotlinxDateTime))
        val query = SqlRenderer(dialect).render(select(currentInstant().atTimeZone("UTC")))
        assertEquals("SELECT (CURRENT_TIMESTAMP AT TIME ZONE $1)", query.sql)
        assertEquals(listOf("UTC"), query.parameters.map { it.value.toString() })
        assertEquals(
            "CREATE TABLE \"utc_events\" (\"created_at\" TIMESTAMP WITHOUT TIME ZONE DEFAULT ((CURRENT_TIMESTAMP) AT TIME ZONE 'UTC') NOT NULL)",
            dialect.render(createTable(UtcEvents)).sql,
        )
    }

    @Test
    fun reversesTheResultTypeAndRejectsAnEmptyZone() {
        val result = SqlRenderer(
            PostgresDialect,
        ).render(select(UtcEvents.createdAt.atTimeZone("Asia/Tokyo")).from(UtcEvents))
        assertEquals("SELECT (\"utc_events\".\"created_at\" AT TIME ZONE $1) FROM \"utc_events\"", result.sql)
        assertEquals(listOf("Asia/Tokyo"), result.parameters.map { it.value.toString() })
        assertFailsWith<IllegalArgumentException> { currentInstant().atTimeZone("") }
    }
}

private object UtcEvents : Table("utc_events") {
    val createdAt = localDateTime("created_at").default(currentInstant().atTimeZone("UTC"))
}
