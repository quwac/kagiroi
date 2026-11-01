package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.SqlTransaction
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.flow.toList

private object SavepointRows : Table("savepoint_rows") {
    val id = long("id").primaryKey()
    val name = string("name")
}

suspend fun SqlTransaction.validSavepointScope() {
    savepoint {
        execute(createTable(SavepointRows))
        insertInto(SavepointRows).values {
            set(SavepointRows.id, 1L)
            set(SavepointRows.name, "Ada")
        }.execute()
        update(SavepointRows) { set(SavepointRows.name, "Grace") }
            .where(SavepointRows.id eq 1L)
            .execute()
        select(SavepointRows.id, SavepointRows.name)
            .from(SavepointRows)
            .where(SavepointRows.id eq 1L)
            .one()
        executeRaw("SELECT 1")
        queryRaw("SELECT 1").toList()
        savepoint {
            deleteFrom(SavepointRows).where(SavepointRows.id eq 1L).execute()
        }
    }
}
