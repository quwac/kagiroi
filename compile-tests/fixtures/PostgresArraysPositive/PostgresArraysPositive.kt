package fixtures

import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.postgres.asPostgresArray
import dev.quwac.kagiroi.postgres.currentSetting
import dev.quwac.kagiroi.postgres.eqAny
import dev.quwac.kagiroi.postgres.postgresRls
import dev.quwac.kagiroi.postgres.uuidArrayText
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.nullIf
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.rlsContext
import kotlin.uuid.Uuid

object Documents : Table("documents") {
    val tenantId = uuid("tenant_id")
    val optionalTenant = uuid("optional_tenant").nullable()
    init {
        postgresRls {
            policy("memberships") {
                val tenants = nullIf(currentSetting("app.tenant_ids", true), "").asPostgresArray(PostgresTypes.uuid)
                using { tenantId.eqAny(tenants) }
                withCheck { optionalTenant.eqAny(tenants) }
            }
        }
    }
}

suspend fun scoped(database: SqlDatabase, ids: List<Uuid>) = database.transaction {
    rlsContext("app.tenant_ids" to uuidArrayText(ids)) { Documents.select().all() }
}
