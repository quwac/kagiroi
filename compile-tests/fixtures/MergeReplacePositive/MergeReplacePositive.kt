package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.oracle.mergeInto as oracleMerge
import dev.quwac.kagiroi.sqlserver.mergeInto as sqlServerMerge
import dev.quwac.kagiroi.sqlite.onConflict
import dev.quwac.kagiroi.sqlite.replaceInto as sqliteReplace
import dev.quwac.kagiroi.mysql.replaceInto as mysqlReplace
import dev.quwac.kagiroi.mariadb.replaceInto as mariaReplace

private object Rows : Table("rows") {
    val id = long("id")
    val name = string("name")
}
private object Incoming : Table("incoming") {
    val id = long("id")
    val name = string("name")
}

fun typedStatements() {
    val source = Incoming.alias("s")
    sqlServerMerge(Rows, source, Rows.id eq source[Incoming.id]) {
        whenMatchedUpdate { set(Rows.name, source[Incoming.name]) }
        whenNotMatchedInsert { set(Rows.id, source[Incoming.id]); set(Rows.name, source[Incoming.name]) }
    }
    oracleMerge(Rows, source, Rows.id eq source[Incoming.id]) {
        whenMatchedUpdate { set(Rows.name, source[Incoming.name]) }
    }
    sqliteReplace(Rows).values { set(Rows.id, 1L) }
    mysqlReplace(Rows).values { set(Rows.id, 1L) }
    mariaReplace(Rows).values { set(Rows.id, 1L) }
    dev.quwac.kagiroi.sql.insertInto(Rows).values { set(Rows.id, 1L) }
        .onConflict(Rows.id) { doNothing() }.onConflict { doNothing() }
}

suspend fun executeMerge(database: SqlExecution) {
    val source = Incoming.alias("source")
    database.sqlServerMerge(Rows, source, Rows.id eq source[Incoming.id]) {
        whenMatchedUpdate { set(Rows.name, source[Incoming.name]) }
    }.execute()
    database.oracleMerge(Rows, source, Rows.id eq source[Incoming.id]) {
        whenMatchedUpdate { set(Rows.name, source[Incoming.name]) }
    }.execute()
}
