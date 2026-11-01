package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlin.test.Test
import kotlin.test.assertTrue

class WithDmlRenderingTest {
    @Test
    fun rendersCteScopesAtMySqlsDmlGrammarPositions() {
        val targets = select(WithDmlUsers.id).from(WithDmlUsers).where(WithDmlUsers.name eq "source").asCte("targets")
        val targetId = targets[WithDmlUsers.id]
        val update = with(targets) {
            update(WithDmlUsers) { set(WithDmlUsers.name, "processed") }
                .where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
        }
        val delete = with(targets) {
            deleteFrom(WithDmlUsers).where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
        }
        val insert = with(targets) {
            insertInto(WithDmlArchive, WithDmlArchive.id).from(select(targetId).from(targets))
        }

        assertTrue(
            SqlRenderer(MySqlDialect).render(update).sql.startsWith("WITH `targets` AS (SELECT"),
            SqlRenderer(MySqlDialect).render(update).sql,
        )
        assertTrue(
            SqlRenderer(MySqlDialect).render(delete).sql.contains(") DELETE FROM `with_dml_users`"),
            SqlRenderer(MySqlDialect).render(delete).sql,
        )
        assertTrue(
            SqlRenderer(MySqlDialect).render(insert).sql.startsWith(
                "INSERT INTO `with_dml_archive` (`id`) WITH `targets` AS (SELECT",
            ),
            SqlRenderer(MySqlDialect).render(insert).sql,
        )
    }

    @Test
    fun rendersRecursiveCteAtTheInsertSelectPosition() {
        val n = literal(1L).alias("n")
        val numbers = recursiveCte("numbers", select(n)) { self ->
            select(self[n] + 1L).from(self).where(self[n] lt 3L)
        }
        val sql = SqlRenderer(MySqlDialect).render(
            with(numbers) {
                insertInto(WithDmlArchive, WithDmlArchive.id).from(select(numbers[n]).from(numbers))
            },
        ).sql

        assertTrue(
            sql.startsWith("INSERT INTO `with_dml_archive` (`id`) WITH RECURSIVE `numbers`(`n`) AS (SELECT"),
            sql,
        )
    }
}

private object WithDmlUsers : Table("with_dml_users") {
    val id = long("id")
    val name = string("name")
}

private object WithDmlArchive : Table("with_dml_archive") {
    val id = long("id")
}
