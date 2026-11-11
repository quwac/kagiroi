import dev.quwac.kagiroi.d1.D1Database
import dev.quwac.kagiroi.d1.d1HttpDatabase
import dev.quwac.kagiroi.mysql.hyperdrive.HyperdriveMySqlDatabase

fun d1() = D1Database("not a D1 binding")
fun http() = d1HttpDatabase(1, 2, 3)
fun mysql() = HyperdriveMySqlDatabase(42)
