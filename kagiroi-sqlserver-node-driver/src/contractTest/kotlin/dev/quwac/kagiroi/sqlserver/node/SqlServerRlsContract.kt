package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.createSecurityObjects
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.executeSecurity
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.rlsContext
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sqlserver.AfterInsert
import dev.quwac.kagiroi.sqlserver.function
import dev.quwac.kagiroi.sqlserver.sqlServerRls
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal suspend fun sqlServerRlsContract(database: SqlDatabase) {
    try {
        database.executeRaw(
            "CREATE FUNCTION dbo.kagiroi_rls_predicate(@tenant INT) RETURNS TABLE WITH SCHEMABINDING AS RETURN SELECT 1 AS allowed WHERE @tenant = CAST(SESSION_CONTEXT(N'tenant_id') AS INT)",
        )
        database.execute(createTable(RlsDocuments))
        database.executeRaw("INSERT INTO dbo.kagiroi_rls_documents VALUES (1, 1), (2, 2)")
        database.executeSecurity(schema(RlsDocuments).createSecurityObjects())
        var session: Any? = null
        for (tenant in listOf(1, 2, 1)) {
            database.transaction {
                val current = queryRaw("SELECT @@SPID AS value").toList().single()[0]
                if (session == null) session = current else assertEquals(session, current)
                rlsContext("tenant_id" to tenant) {
                    assertEquals(listOf(tenant), RlsDocuments.select().all().map { it[RlsDocuments.id] })
                }
                assertNull(queryRaw("SELECT SESSION_CONTEXT(N'tenant_id') AS value").toList().single()[0])
            }
        }
        assertFailsWith<IllegalStateException> {
            database.transaction { rlsContext("tenant_id" to 1) { error("request failed") } }
        }
        database.transaction {
            assertNull(queryRaw("SELECT SESSION_CONTEXT(N'tenant_id') AS value").toList().single()[0])
            rlsContext("tenant_id" to 2) {
                assertEquals(listOf(2), RlsDocuments.select().all().map { it[RlsDocuments.id] })
            }
        }
        assertFailsWith<DatabaseError> {
            database.transaction {
                rlsContext("tenant_id" to 1) {
                    executeRaw("SET XACT_ABORT ON")
                    insertInto(RlsDocuments).values {
                        set(RlsDocuments.id, BLOCKED_DOCUMENT_ID)
                        set(RlsDocuments.tenant, 2)
                    }.execute()
                }
            }
        }
        // Server-initiated rollback may have destroyed this session. A replacement must be clean.
        database.transaction {
            assertNull(queryRaw("SELECT SESSION_CONTEXT(N'tenant_id') AS value").toList().single()[0])
            rlsContext("tenant_id" to 1) {
                assertEquals(listOf(1), RlsDocuments.select().all().map { it[RlsDocuments.id] })
            }
        }
    } finally {
        database.executeRaw("DROP SECURITY POLICY IF EXISTS dbo.kagiroi_tenant_security")
        database.executeRaw("DROP TABLE IF EXISTS dbo.kagiroi_rls_documents")
        database.executeRaw("DROP FUNCTION IF EXISTS dbo.kagiroi_rls_predicate")
    }
}

private object RlsDocuments : Table("kagiroi_rls_documents", "dbo") {
    val id = int("id").primaryKey()
    val tenant = int("tenant_id")
    init {
        sqlServerRls("kagiroi_tenant_security") {
            filterPredicate(function("dbo", "kagiroi_rls_predicate"), tenant)
            blockPredicate(function("dbo", "kagiroi_rls_predicate"), AfterInsert, tenant)
        }
    }
}

private const val BLOCKED_DOCUMENT_ID = 3
