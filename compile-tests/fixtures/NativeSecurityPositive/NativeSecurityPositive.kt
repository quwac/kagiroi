package fixtures

import dev.quwac.kagiroi.postgres.postgresRls
import dev.quwac.kagiroi.postgres.currentSetting
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.rlsContext
import dev.quwac.kagiroi.sqlserver.sqlServerRls
import dev.quwac.kagiroi.sqlserver.function as sqlServerFunction
import dev.quwac.kagiroi.sqlserver.AfterInsert
import dev.quwac.kagiroi.oracle.oracleVpd
import dev.quwac.kagiroi.oracle.Select
import dev.quwac.kagiroi.oracle.Insert
import kotlin.uuid.Uuid

object Documents : Table("documents") {
    val id = uuid("id").primaryKey()
    val tenantId = uuid("tenant_id")
    init { postgresRls { force(); policy("tenant") {
        using { tenantId eq currentSetting("app.tenant_id").cast<Uuid>() }
        withCheck { tenantId eq currentSetting("app.tenant_id").cast<Uuid>() }
    } } }
}
object SqlServerDocuments : Table("documents") {
    val tenantId = uuid("tenant_id")
    init { sqlServerRls("tenant_security") {
        filterPredicate(function = sqlServerFunction(schema = "security", name = "predicate"), tenantId)
        blockPredicate(function = sqlServerFunction(schema = "security", name = "predicate"), timing = AfterInsert, tenantId)
    } }
}
object OracleDocuments : Table("documents") {
    val tenantId = uuid("tenant_id")
    init { oracleVpd("tenant_isolation") {
        function(schema = "security", packageName = "tenant_security", name = "predicate")
        forStatements(Select, Insert)
    } }
}
suspend fun scopedQuery(db: SqlDatabase, tenant: Uuid) = db.transaction {
    rlsContext("app.tenant_id" to tenant) { Documents.select().all() }
}
