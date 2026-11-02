package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WithDmlRenderingTest {
    @Test
    fun rejectsDmlCtesForTheSupportedMariaDb114Baseline() {
        val targets = select(WithDmlUsers.id).from(WithDmlUsers).where(WithDmlUsers.name eq "source").asCte("targets")
        val targetId = targets[WithDmlUsers.id]

        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(MariaDbDialect).render(
                with(targets) {
                    update(WithDmlUsers) { set(WithDmlUsers.name, "processed") }
                        .where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
                },
            )
        }
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(MariaDbDialect).render(
                with(targets) {
                    deleteFrom(WithDmlUsers).where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
                },
            )
        }
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(MariaDbDialect).render(
                with(targets) {
                    insertInto(WithDmlArchive, WithDmlArchive.id).from(select(targetId).from(targets))
                },
            )
        }
    }
}

private object WithDmlUsers : Table("with_dml_users") {
    val id = long("id")
    val name = string("name")
}

private object WithDmlArchive : Table("with_dml_archive") {
    val id = long("id")
}
