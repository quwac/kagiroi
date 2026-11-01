package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PGliteCteDmlIntegrationTest {
    @Test
    @Suppress("LongMethod") // Keeps the one PGlite connection and CTE/DML assertions in one integration scenario.
    fun executesCteScopedWritesInsertSelectReturningAndRecursiveDelete() = runTest {
        val database = PGliteDatabase.inMemory()
        try {
            database.execute(createTable(PGliteCteDmlUsers))
            database.execute(createTable(PGliteCteDmlArchive))
            database.insertInto(PGliteCteDmlUsers).values {
                set(PGliteCteDmlUsers.id, 1L)
                set(PGliteCteDmlUsers.name, "copy")
                set(PGliteCteDmlUsers.status, "queued")
            }.execute()
            database.insertInto(PGliteCteDmlUsers).values {
                set(PGliteCteDmlUsers.id, 2L)
                set(PGliteCteDmlUsers.name, "remove")
                set(PGliteCteDmlUsers.status, "queued")
            }.execute()

            val active = select(PGliteCteDmlUsers.id)
                .from(PGliteCteDmlUsers)
                .where(PGliteCteDmlUsers.name eq "copy")
                .asCte("active_users")
            val activeId = active[PGliteCteDmlUsers.id]
            val updated = with(active) {
                database.update(PGliteCteDmlUsers) { set(PGliteCteDmlUsers.status, "processed") }
                    .where(PGliteCteDmlUsers.id.inSubquery(select(activeId).from(active)))
            }.returning(PGliteCteDmlUsers.id, PGliteCteDmlUsers.status).one()
            assertEquals(1L, updated[PGliteCteDmlUsers.id])
            assertEquals("processed", updated[PGliteCteDmlUsers.status])

            val archived = select(PGliteCteDmlUsers.id, PGliteCteDmlUsers.status)
                .from(PGliteCteDmlUsers)
                .where(PGliteCteDmlUsers.status eq "processed")
                .asCte("archived_users")
            val archivedId = archived[PGliteCteDmlUsers.id]
            val archivedStatus = archived[PGliteCteDmlUsers.status]
            val inserted = with(archived) {
                database.insertInto(PGliteCteDmlArchive, PGliteCteDmlArchive.id, PGliteCteDmlArchive.status)
                    .from(select(archivedId, archivedStatus).from(archived))
            }.returning(PGliteCteDmlArchive.id, PGliteCteDmlArchive.status).one()
            assertEquals(1L, inserted[PGliteCteDmlArchive.id])
            assertEquals("processed", inserted[PGliteCteDmlArchive.status])

            val removable = select(PGliteCteDmlUsers.id)
                .from(PGliteCteDmlUsers)
                .where(PGliteCteDmlUsers.name eq "remove")
                .asCte("removable_users")
            val removableId = removable[PGliteCteDmlUsers.id]
            assertEquals(
                1L,
                with(removable) {
                    database.deleteFrom(PGliteCteDmlUsers)
                        .where(PGliteCteDmlUsers.id.inSubquery(select(removableId).from(removable)))
                }.execute().affectedRows,
            )

            val n = literal(1L).alias("n")
            val numbers = recursiveCte("numbers", select(n)) { self ->
                select(self[n] + 1L).from(self).where(self[n] lt 1L)
            }
            assertEquals(
                1L,
                with(numbers) {
                    database.deleteFrom(PGliteCteDmlArchive)
                        .where(PGliteCteDmlArchive.id.inSubquery(select(numbers[n]).from(numbers)))
                }.returning(PGliteCteDmlArchive.id).one()[PGliteCteDmlArchive.id],
            )
        } finally {
            database.close()
        }
    }
}

private object PGliteCteDmlUsers : Table("pglite_cte_dml_users") {
    val id = long("id").primaryKey()
    val name = string("name")
    val status = string("status")
}

private object PGliteCteDmlArchive : Table("pglite_cte_dml_archive") {
    val id = long("id").primaryKey()
    val status = string("status")
}
