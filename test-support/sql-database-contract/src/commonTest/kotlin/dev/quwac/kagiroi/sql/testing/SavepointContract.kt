package dev.quwac.kagiroi.sql.testing

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.flow.single
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Shared live-driver contract for transaction-local savepoint behavior. */
suspend fun savepointContract(database: SqlDatabase) {
    assertNotNull(database.capability(SqlCapabilities.Savepoints))
    database.execute(createTable(SavepointRows))
    try {
        verifiesTypedRollbackAndContinuedTransaction(database)
        verifiesNestedSavepoints(database)
        verifiesOuterSavepointRollback(database)
        verifiesOuterTransactionRollback(database)
    } finally {
        database.execute(dropTable(SavepointRows))
    }
}

private suspend fun verifiesTypedRollbackAndContinuedTransaction(database: SqlDatabase) {
    database.transaction {
        insertRow(1, "before")
        insertRow(2, "delete-me")

        assertFailsWith<ExpectedSavepointFailure> {
            savepoint {
                val before = select(SavepointRows.id, SavepointRows.name)
                    .from(SavepointRows)
                    .where(SavepointRows.id eq 1L)
                    .one()
                assertEquals("before", before[SavepointRows.name])

                update(SavepointRows) { set(SavepointRows.name, "inside-savepoint") }
                    .where(SavepointRows.id eq 1L)
                    .execute()
                deleteFrom(SavepointRows).where(SavepointRows.id eq 2L).execute()
                insertRow(3, "rolled-back")
                // Raw result labels retain the database's identifier case, so use the portable positional
                // surface here rather than imposing a case-folding policy on every driver.
                assertEquals(1, queryRaw("SELECT 1 AS savepoint_raw").single().size)
                throw ExpectedSavepointFailure()
            }
        }

        assertEquals(
            "before",
            select(SavepointRows.name).from(SavepointRows).where(SavepointRows.id eq 1L).one()[SavepointRows.name],
        )
        assertNotNull(select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 2L).oneOrNull())
        assertNull(select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 3L).oneOrNull())
        insertRow(4, "continued")
    }

    assertEquals(
        "continued",
        database.transaction {
            savepoint {
                select(SavepointRows.name)
                    .from(SavepointRows)
                    .where(SavepointRows.id eq 4L)
                    .one()[SavepointRows.name]
            }
        },
    )
}

private suspend fun verifiesNestedSavepoints(database: SqlDatabase) {
    database.transaction {
        insertRow(10, "parent")
        savepoint {
            insertRow(11, "outer")
            assertFailsWith<ExpectedSavepointFailure> {
                savepoint {
                    insertRow(12, "inner-rolled-back")
                    throw ExpectedSavepointFailure()
                }
            }
            insertRow(13, "outer-continued")
        }
    }

    assertNotNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 10L).oneOrNull())
    assertNotNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 11L).oneOrNull())
    assertNotNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 13L).oneOrNull())
    assertNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 12L).oneOrNull())
}

private suspend fun verifiesOuterSavepointRollback(database: SqlDatabase) {
    database.transaction {
        insertRow(30, "outer-parent")
        assertFailsWith<ExpectedSavepointFailure> {
            savepoint {
                insertRow(31, "outer-rolled-back")
                savepoint { insertRow(32, "nested-rolled-back") }
                throw ExpectedSavepointFailure()
            }
        }
        insertRow(33, "parent-continued")
    }
    assertNotNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 30L).oneOrNull())
    assertNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 31L).oneOrNull())
    assertNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 32L).oneOrNull())
    assertNotNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 33L).oneOrNull())
}

private suspend fun verifiesOuterTransactionRollback(database: SqlDatabase) {
    assertFailsWith<ExpectedSavepointFailure> {
        database.transaction {
            insertRow(20, "outer-transaction-before")
            insertRow(21, "deleted-inside-savepoint")
            savepoint {
                update(SavepointRows) { set(SavepointRows.name, "updated-inside-savepoint") }
                    .where(SavepointRows.id eq 20L)
                    .execute()
                deleteFrom(SavepointRows).where(SavepointRows.id eq 21L).execute()
                insertRow(22, "inserted-inside-savepoint")
            }
            throw ExpectedSavepointFailure()
        }
    }
    assertNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 20L).oneOrNull())
    assertNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 21L).oneOrNull())
    assertNull(database.select(SavepointRows.id).from(SavepointRows).where(SavepointRows.id eq 22L).oneOrNull())
}

private suspend fun dev.quwac.kagiroi.sql.SqlExecution.insertRow(id: Long, name: String) {
    insertInto(SavepointRows).values {
        set(SavepointRows.id, id)
        set(SavepointRows.name, name)
    }.execute()
}

private class ExpectedSavepointFailure : RuntimeException()

private object SavepointRows : Table("kagiroi_savepoint_contract_rows") {
    val id = long("id").primaryKey()
    val name = string("name")
}
