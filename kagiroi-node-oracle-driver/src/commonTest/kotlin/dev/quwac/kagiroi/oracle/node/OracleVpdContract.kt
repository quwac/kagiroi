package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.oracle.Delete
import dev.quwac.kagiroi.oracle.Insert
import dev.quwac.kagiroi.oracle.OracleRlsContext
import dev.quwac.kagiroi.oracle.Select
import dev.quwac.kagiroi.oracle.Update
import dev.quwac.kagiroi.oracle.function
import dev.quwac.kagiroi.oracle.oracleVpd
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.createSecurityObjects
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.executeSecurity
import dev.quwac.kagiroi.sql.rlsContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal suspend fun oracleVpdContract(database: SqlDatabase) {
    val adapter = OracleRlsContext("USERENV", function(packageName = "KAGIROI_RLS_CONTEXT", name = "SET_VALUE"))
    try {
        database.executeRaw(
            "CREATE OR REPLACE FUNCTION KAGIROI_RLS_PREDICATE(schema_name VARCHAR2, object_name VARCHAR2) " +
                "RETURN VARCHAR2 AS BEGIN RETURN '\"TENANT\" = SYS_CONTEXT(''USERENV'', ''CLIENT_IDENTIFIER'')'; END;",
        )
        database.execute(createTable(VpdDocuments))
        database.executeRaw("INSERT INTO \"KAGIROI_RLS_DOCUMENTS\" VALUES (1, 'tenant_a')")
        database.executeRaw("INSERT INTO \"KAGIROI_RLS_DOCUMENTS\" VALUES (2, 'tenant_b')")
        database.executeRaw("COMMIT")
        database.executeSecurity(schema(VpdDocuments).createSecurityObjects())
        for ((tenant, id) in listOf("tenant_a" to 1, "tenant_b" to 2, "tenant_a" to 1)) {
            database.transaction {
                rlsContext("CLIENT_IDENTIFIER" to tenant, adapter = adapter) {
                    assertEquals(listOf(id), VpdDocuments.select().all().map { it[VpdDocuments.id] })
                }
            }
        }
        assertFailsWith<DatabaseError> {
            database.transaction {
                rlsContext("CLIENT_IDENTIFIER" to "tenant_a", adapter = adapter) {
                    executeRaw("INSERT INTO \"KAGIROI_RLS_DOCUMENTS\" VALUES (3, 'tenant_b')")
                }
            }
        }
    } finally {
        database.execute(dropTable(VpdDocuments))
        database.executeRaw("DROP FUNCTION KAGIROI_RLS_PREDICATE")
    }
}

private object VpdDocuments : Table("KAGIROI_RLS_DOCUMENTS") {
    val id = int("ID").primaryKey()
    val tenant = string("TENANT")
    init {
        oracleVpd("TENANT_ISOLATION") {
            function(name = "KAGIROI_RLS_PREDICATE")
            forStatements(Select, Insert, Update, Delete)
        }
    }
}
