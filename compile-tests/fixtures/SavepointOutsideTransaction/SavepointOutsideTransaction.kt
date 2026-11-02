package fixtures

import dev.quwac.kagiroi.sql.SqlDatabase

suspend fun SqlDatabase.savepointOutsideTransaction() {
    savepoint { }
}
