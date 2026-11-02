package dev.quwac.kagiroi.sql.testing

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.ReturningCapability
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared live-driver contract for standard DML RETURNING semantics. */
suspend fun returningContract(database: SqlDatabase) {
    assertTrue(database.capability(SqlCapabilities.Returning) is ReturningCapability)
    database.execute(createTable(ReturningRows))
    try {
        val inserted = database.insertInto(ReturningRows).values {
            set(ReturningRows.id, 1L)
            set(ReturningRows.name, "inserted")
        }.returning(ReturningRows.id, ReturningRows.name).one()
        assertEquals(1L, inserted[ReturningRows.id])
        assertEquals("inserted", inserted[ReturningRows.name])

        val updated = database.update(ReturningRows) { set(ReturningRows.name, "updated") }
            .where(ReturningRows.id eq 1L)
            .returning(ReturningRows.id, ReturningRows.name)
            .one()
        assertEquals(1L, updated[ReturningRows.id])
        assertEquals("updated", updated[ReturningRows.name])

        val deleted = database.deleteFrom(ReturningRows)
            .where(ReturningRows.id eq 1L)
            .returning(ReturningRows.id, ReturningRows.name)
            .one()
        assertEquals(1L, deleted[ReturningRows.id])
        assertEquals("updated", deleted[ReturningRows.name])

        val transactionValue = database.transaction {
            insertInto(ReturningRows).values {
                set(ReturningRows.id, 2L)
                set(ReturningRows.name, "transaction")
            }.returning(ReturningRows.name).one()[ReturningRows.name]
        }
        assertEquals("transaction", transactionValue)
    } finally {
        database.execute(dropTable(ReturningRows))
    }
}

private object ReturningRows : Table("kagiroi_returning_contract_rows") {
    val id = long("id").primaryKey()
    val name = string("name")
}
