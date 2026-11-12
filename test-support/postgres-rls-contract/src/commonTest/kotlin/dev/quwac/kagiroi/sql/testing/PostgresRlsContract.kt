package dev.quwac.kagiroi.sql.testing

import dev.quwac.kagiroi.sql.eq

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cast
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
import dev.quwac.kagiroi.postgres.currentSetting
import dev.quwac.kagiroi.postgres.postgresRls
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue



import kotlinx.coroutines.flow.toList
import kotlin.uuid.Uuid

/** Verifies database-enforced filtering, write checks, context recovery, and physical-session reuse. */
suspend fun postgresRlsContract(database: SqlDatabase) {
    val a = Uuid.parse("11111111-1111-1111-1111-111111111111")
    val b = Uuid.parse("22222222-2222-2222-2222-222222222222")
    try {
        database.execute(createTable(RlsDocuments))
        database.executeRaw("CREATE ROLE kagiroi_rls_reader NOLOGIN")
        database.executeRaw("GRANT SELECT, INSERT, UPDATE, DELETE ON kagiroi_rls_documents TO kagiroi_rls_reader")
        database.insertInto(RlsDocuments).values { set(RlsDocuments.id, 1); set(RlsDocuments.tenant, a) }.execute()
        database.insertInto(RlsDocuments).values { set(RlsDocuments.id, 2); set(RlsDocuments.tenant, b) }.execute()
        database.executeSecurity(schema(RlsDocuments).createSecurityObjects())
        suspend fun tenantRows(tenant: Uuid): List<Int> = database.transaction {
            executeRaw("SET LOCAL ROLE kagiroi_rls_reader")
            rlsContext("app.tenant_id" to tenant) {
                RlsDocuments.select().all().map { it[RlsDocuments.id] }
            }
        }
        assertEquals(listOf(1), tenantRows(a))
        assertEquals(listOf(2), tenantRows(b))
        database.transaction {
            executeRaw("SET LOCAL ROLE kagiroi_rls_reader")
            assertFailsWith<DatabaseError> {
                rlsContext("app.tenant_id" to a) {
                    insertInto(RlsDocuments).values { set(RlsDocuments.id, 3); set(RlsDocuments.tenant, b) }.execute()
                }
            }
            // A failed statement must recover via the RLS savepoint before the next context.
            rlsContext("app.tenant_id" to b) {
                assertEquals(listOf(2), RlsDocuments.select().all().map { it[RlsDocuments.id] })
                assertFailsWith<IllegalStateException> { rlsContext("app.tenant_id" to a) {} }
            }
            val cleared = queryRaw("SELECT current_setting('app.tenant_id', true) AS value").toList().single()[0]
            assertTrue(cleared == null || cleared == "")
            assertFailsWith<IllegalStateException> {
                rlsContext("app.tenant_id" to a) { error("scope failure") }
            }
            val recovered = queryRaw("SELECT current_setting('app.tenant_id', true) AS value").toList().single()[0]
            assertTrue(recovered == null || recovered == "")
        }
        database.transaction {
            val value = queryRaw("SELECT current_setting('app.tenant_id', true) AS value").toList().single()[0]
            assertTrue(value == null || value == "")
        }
        assertEquals(listOf(1), tenantRows(a))
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_rls_documents")
        database.executeRaw("DROP ROLE IF EXISTS kagiroi_rls_reader")
    }
}

private object RlsDocuments : Table("kagiroi_rls_documents") {
    val id = int("id").primaryKey()
    val tenant = uuid("tenant_id")
    init { postgresRls { force(); policy("tenant_isolation") {
        using { tenant eq currentSetting("app.tenant_id").cast<Uuid>() }
        withCheck { tenant eq currentSetting("app.tenant_id").cast<Uuid>() }
    } } }
}
