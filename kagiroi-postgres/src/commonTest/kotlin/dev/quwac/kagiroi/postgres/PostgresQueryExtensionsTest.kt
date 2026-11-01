package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.rowNumber
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.isDistinctFrom
import dev.quwac.kagiroi.sql.isNotDistinctFrom
import dev.quwac.kagiroi.sql.nullsFirst
import dev.quwac.kagiroi.sql.nullsLast
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresQueryExtensionsTest {
    @Test
    fun rendersDistinctOnAndIlike() {
        val distinct = SqlRenderer(PostgresDialect).render(
            select(PostgresQueryUsers.id, PostgresQueryUsers.name)
                .from(PostgresQueryUsers)
                .distinctOn(PostgresQueryUsers.name)
                .orderBy(PostgresQueryUsers.name.asc(), PostgresQueryUsers.id.asc()),
        )
        val insensitiveMatch = SqlRenderer(PostgresDialect).render(
            select(PostgresQueryUsers.id)
                .from(PostgresQueryUsers)
                .where(PostgresQueryUsers.name ilike "ada%"),
        )

        assertEquals(
            "SELECT DISTINCT ON (\"postgres_query_users\".\"name\") \"postgres_query_users\".\"id\", " +
                "\"postgres_query_users\".\"name\" FROM \"postgres_query_users\" " +
                "ORDER BY \"postgres_query_users\".\"name\" ASC, \"postgres_query_users\".\"id\" ASC",
            distinct.sql,
        )
        assertEquals(
            "SELECT \"postgres_query_users\".\"id\" FROM \"postgres_query_users\" " +
                "WHERE \"postgres_query_users\".\"name\" ILIKE $1",
            insensitiveMatch.sql,
        )
        assertEquals(listOf("ada%"), insensitiveMatch.parameters.map { it.value })
    }

    @Test
    fun rendersNativeNullSafeComparisonAndUpdateFrom() {
        val comparison = SqlRenderer(PostgresDialect).render(
            select(PostgresQueryUsers.id)
                .from(PostgresQueryUsers)
                .where(
                    (PostgresQueryUsers.nickname isNotDistinctFrom "Ada") and
                        (PostgresQueryUsers.name isDistinctFrom PostgresQueryUsers.nickname),
                ),
        )
        val update = SqlRenderer(PostgresDialect).render(
            update(PostgresQueryUsers) {
                set(PostgresQueryUsers.name, PostgresQueryNames.name)
            }
                .from(PostgresQueryNames)
                .where(PostgresQueryUsers.id eq PostgresQueryNames.userId),
        )

        assertEquals(
            "SELECT \"postgres_query_users\".\"id\" FROM \"postgres_query_users\" WHERE " +
                "(\"postgres_query_users\".\"nickname\" IS NOT DISTINCT FROM $1 AND " +
                "\"postgres_query_users\".\"name\" IS DISTINCT FROM \"postgres_query_users\".\"nickname\")",
            comparison.sql,
        )
        assertEquals(listOf("Ada"), comparison.parameters.map { it.value })
        assertEquals(
            "UPDATE \"postgres_query_users\" SET \"name\" = \"postgres_query_names\".\"name\" " +
                "FROM \"postgres_query_names\" WHERE \"postgres_query_users\".\"id\" = " +
                "\"postgres_query_names\".\"user_id\"",
            update.sql,
        )
    }

    @Test
    fun rendersExplicitNullOrderingInSelectAndWindowOrderings() {
        val rowNumber = rowNumber().over {
            orderBy(PostgresQueryUsers.nickname.asc().nullsLast())
        }
        val rendered = SqlRenderer(PostgresDialect).render(
            select(PostgresQueryUsers.id, rowNumber)
                .from(PostgresQueryUsers)
                .orderBy(PostgresQueryUsers.nickname.desc().nullsFirst()),
        )

        assertEquals(
            "SELECT \"postgres_query_users\".\"id\", ROW_NUMBER() OVER (ORDER BY " +
                "\"postgres_query_users\".\"nickname\" ASC NULLS LAST) FROM \"postgres_query_users\" " +
                "ORDER BY \"postgres_query_users\".\"nickname\" DESC NULLS FIRST",
            rendered.sql,
        )
    }
}

private object PostgresQueryUsers : Table("postgres_query_users") {
    val id = long("id")
    val name = string("name")
    val nickname = string("nickname").nullable()
}

private object PostgresQueryNames : Table("postgres_query_names") {
    val userId = long("user_id")
    val name = string("name")
}
