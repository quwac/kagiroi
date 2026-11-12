package fixtures
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.rlsContext
suspend fun unsafe(db: SqlDatabase) { db.rlsContext("tenant" to "a") {} }
