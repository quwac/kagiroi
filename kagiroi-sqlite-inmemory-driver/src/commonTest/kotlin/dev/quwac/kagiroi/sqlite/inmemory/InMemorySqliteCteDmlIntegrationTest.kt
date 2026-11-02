package dev.quwac.kagiroi.sqlite.inmemory

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

class InMemorySqliteCteDmlIntegrationTest {
    @Test
    fun executesCteScopedWritesInsertSelectReturningAndRecursiveDelete() = runTest {
        val database = InMemorySqliteDatabase.inMemory()
        try {
            database.execute(createTable(SqliteCteDmlUsers))
            database.execute(createTable(SqliteCteDmlArchive))
            insertUsers(database)
            updateFromCte(database)
            insertFromCte(database)
            deleteFromCte(database)
            deleteFromRecursiveCte(database)
        } finally {
            database.dispose()
        }
    }
}

private suspend fun insertUsers(database: InMemorySqliteDatabase) {
    database.insertInto(SqliteCteDmlUsers).values {
        set(SqliteCteDmlUsers.id, 1L)
        set(SqliteCteDmlUsers.name, "copy")
        set(SqliteCteDmlUsers.status, "queued")
    }.execute()
    database.insertInto(SqliteCteDmlUsers).values {
        set(SqliteCteDmlUsers.id, 2L)
        set(SqliteCteDmlUsers.name, "remove")
        set(SqliteCteDmlUsers.status, "queued")
    }.execute()
}

private suspend fun updateFromCte(database: InMemorySqliteDatabase) {
    val active = select(SqliteCteDmlUsers.id).from(SqliteCteDmlUsers)
        .where(SqliteCteDmlUsers.name eq "copy").asCte("active_users")
    val activeId = active[SqliteCteDmlUsers.id]
    val updated = with(active) {
        database.update(SqliteCteDmlUsers) { set(SqliteCteDmlUsers.status, "processed") }
            .where(SqliteCteDmlUsers.id.inSubquery(select(activeId).from(active)))
    }.returning(SqliteCteDmlUsers.id, SqliteCteDmlUsers.status).one()
    assertEquals(1L, updated[SqliteCteDmlUsers.id])
    assertEquals("processed", updated[SqliteCteDmlUsers.status])
}

private suspend fun insertFromCte(database: InMemorySqliteDatabase) {
    val archived = select(SqliteCteDmlUsers.id, SqliteCteDmlUsers.status).from(SqliteCteDmlUsers)
        .where(SqliteCteDmlUsers.status eq "processed").asCte("archived")
    val archivedId = archived[SqliteCteDmlUsers.id]
    val archivedStatus = archived[SqliteCteDmlUsers.status]
    val inserted = with(archived) {
        database.insertInto(SqliteCteDmlArchive, SqliteCteDmlArchive.id, SqliteCteDmlArchive.status)
            .from(select(archivedId, archivedStatus).from(archived))
    }.returning(SqliteCteDmlArchive.id, SqliteCteDmlArchive.status).one()
    assertEquals(1L, inserted[SqliteCteDmlArchive.id])
    assertEquals("processed", inserted[SqliteCteDmlArchive.status])
}

private suspend fun deleteFromCte(database: InMemorySqliteDatabase) {
    val removable = select(SqliteCteDmlUsers.id).from(SqliteCteDmlUsers)
        .where(SqliteCteDmlUsers.name eq "remove").asCte("removable")
    val removableId = removable[SqliteCteDmlUsers.id]
    assertEquals(
        1L,
        with(removable) {
            database.deleteFrom(SqliteCteDmlUsers)
                .where(SqliteCteDmlUsers.id.inSubquery(select(removableId).from(removable)))
        }.execute().affectedRows,
    )
}

private suspend fun deleteFromRecursiveCte(database: InMemorySqliteDatabase) {
    val n = literal(1L).alias("n")
    val numbers = recursiveCte("numbers", select(n)) { self ->
        select(self[n] + 1L).from(self).where(self[n] lt 1L)
    }
    assertEquals(
        1L,
        with(numbers) {
            database.deleteFrom(SqliteCteDmlArchive)
                .where(SqliteCteDmlArchive.id.inSubquery(select(numbers[n]).from(numbers)))
        }.returning(SqliteCteDmlArchive.id).one()[SqliteCteDmlArchive.id],
    )
}

private object SqliteCteDmlUsers : Table("cte_dml_users") {
    val id = long("id").primaryKey()
    val name = string("name")
    val status = string("status")
}

private object SqliteCteDmlArchive : Table("cte_dml_archive") {
    val id = long("id").primaryKey()
    val status = string("status")
}
