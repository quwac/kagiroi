package fixtures
import dev.quwac.kagiroi.sqlserver.sqlServerRls
import dev.quwac.kagiroi.sqlserver.function
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.literal
object Documents : Table("documents") {
    init { sqlServerRls("p") { filterPredicate(function("security", "predicate"), literal(1)) } }
}
