package fixtures
import dev.quwac.kagiroi.postgres.postgresRls
import dev.quwac.kagiroi.schema.Table
object Documents : Table("documents") {
    val tenantId = uuid("tenant_id")
    init { postgresRls { policy("tenant") { using { "tenant_id = 1" } } } }
}
