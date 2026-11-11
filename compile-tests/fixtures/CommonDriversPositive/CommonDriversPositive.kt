import dev.quwac.kagiroi.d1.D1Binding
import dev.quwac.kagiroi.d1.D1Database
import dev.quwac.kagiroi.d1.d1HttpDatabase
import dev.quwac.kagiroi.mysql.hyperdrive.HyperdriveMySqlDatabase
import dev.quwac.kagiroi.mariadb.MariaDbDialect

fun d1(binding: D1Binding) = D1Database(binding)
fun nativeD1(native: Any) = D1Database(D1Binding(native))
fun http(account: String, database: String, token: String) = d1HttpDatabase(account, database, token)
fun mysql(connectionString: String) = HyperdriveMySqlDatabase(connectionString)
fun maria(connectionString: String) = HyperdriveMySqlDatabase(connectionString) { dialect(MariaDbDialect) }
