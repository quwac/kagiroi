package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where

private object ReturningRows : Table("returning_rows") {
    val id = long("id").primaryKey()
    val name = string("name")
}

suspend fun SqlExecution.validReturningScope() {
    insertInto(ReturningRows).values {
        set(ReturningRows.id, 1L)
        set(ReturningRows.name, "Ada")
    }.returning(ReturningRows.id, ReturningRows.name).one()
    update(ReturningRows) { set(ReturningRows.name, "Grace") }
        .where(ReturningRows.id eq 1L)
        .returning(ReturningRows.id, ReturningRows.name)
        .all()
    deleteFrom(ReturningRows)
        .where(ReturningRows.id eq 1L)
        .returning(ReturningRows.id)
        .oneOrNull()
}
