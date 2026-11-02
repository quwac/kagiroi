package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
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
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals

class NullSafeUpdateFromRenderingTest {
    @Test
    fun rendersSqliteNullSafeComparisonsAndUpdateFrom() {
        val comparison = SqlRenderer(SqliteDialect).render(
            select(SqliteUpdateUsers.id)
                .from(SqliteUpdateUsers)
                .where(
                    (SqliteUpdateUsers.nickname isNotDistinctFrom "Ada") and
                        (SqliteUpdateUsers.name isDistinctFrom SqliteUpdateUsers.nickname),
                ),
        )
        val update = SqlRenderer(SqliteDialect).render(
            update(SqliteUpdateUsers) { set(SqliteUpdateUsers.name, SqliteUpdateNames.name) }
                .from(SqliteUpdateNames)
                .where(SqliteUpdateUsers.id eq SqliteUpdateNames.userId),
        )

        assertEquals(
            "SELECT \"sqlite_update_users\".\"id\" FROM \"sqlite_update_users\" WHERE " +
                "(\"sqlite_update_users\".\"nickname\" IS ? AND \"sqlite_update_users\".\"name\" " +
                "IS NOT \"sqlite_update_users\".\"nickname\")",
            comparison.sql,
        )
        assertEquals(listOf("Ada"), comparison.parameters.map { it.value })
        assertEquals(
            "UPDATE \"sqlite_update_users\" SET \"name\" = \"sqlite_update_names\".\"name\" " +
                "FROM \"sqlite_update_names\" WHERE \"sqlite_update_users\".\"id\" = " +
                "\"sqlite_update_names\".\"user_id\"",
            update.sql,
        )
    }

    @Test
    fun rendersSqliteNullOrdering() {
        val rendered = SqlRenderer(SqliteDialect).render(
            select(SqliteUpdateUsers.id)
                .from(SqliteUpdateUsers)
                .orderBy(
                    SqliteUpdateUsers.nickname.asc().nullsLast(),
                    SqliteUpdateUsers.name.desc().nullsFirst(),
                ),
        )

        assertEquals(
            "SELECT \"sqlite_update_users\".\"id\" FROM \"sqlite_update_users\" ORDER BY " +
                "\"sqlite_update_users\".\"nickname\" ASC NULLS LAST, " +
                "\"sqlite_update_users\".\"name\" DESC NULLS FIRST",
            rendered.sql,
        )
    }
}

private object SqliteUpdateUsers : Table("sqlite_update_users") {
    val id = long("id")
    val name = string("name")
    val nickname = string("nickname").nullable()
}

private object SqliteUpdateNames : Table("sqlite_update_names") {
    val userId = long("user_id")
    val name = string("name")
}
