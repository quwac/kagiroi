package fixtures

import dev.quwac.kagiroi.sql.SqlDatabase

fun enableAutoCommit(database: SqlDatabase) {
    database.autoCommit = true
}
