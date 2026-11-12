package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.oracle.OracleRlsContext
import dev.quwac.kagiroi.oracle.function
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.rlsContext
import dev.quwac.kagiroi.sql.transaction
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Exercises a real Oracle context setter and pool reuse without requiring CREATE ANY CONTEXT. */
internal suspend fun oracleRlsContextContract(database: SqlDatabase) {
    // USERENV.CLIENT_IDENTIFIER is managed by Oracle's native SET_IDENTIFIER/CLEAR_IDENTIFIER.
    // Production application namespaces use a CREATE CONTEXT ... USING trusted package instead.
    val adapter = OracleRlsContext("USERENV", function(packageName = "KAGIROI_RLS_CONTEXT", name = "SET_VALUE"))
    try {
        database.executeRaw(
            "CREATE OR REPLACE PACKAGE KAGIROI_RLS_CONTEXT AS PROCEDURE SET_VALUE(attribute VARCHAR2, value VARCHAR2); END;",
        )
        database.executeRaw(
            "CREATE OR REPLACE PACKAGE BODY KAGIROI_RLS_CONTEXT AS PROCEDURE SET_VALUE(attribute VARCHAR2, value VARCHAR2) IS BEGIN IF attribute <> 'CLIENT_IDENTIFIER' THEN RAISE_APPLICATION_ERROR(-20001, 'Unknown context attribute'); END IF; IF value IS NULL THEN DBMS_SESSION.CLEAR_IDENTIFIER; ELSE DBMS_SESSION.SET_IDENTIFIER(value); END IF; END; END;",
        )
        var session: Any? = null
        for (tenant in listOf("tenant_a", "tenant_b", "tenant_a")) {
            database.transaction {
                val current = queryRaw("SELECT SYS_CONTEXT('USERENV', 'SID') AS value FROM DUAL").toList().single()[0]
                if (session == null) session = current else assertEquals(session, current)
                rlsContext("CLIENT_IDENTIFIER" to tenant, adapter = adapter) {
                    assertEquals(
                        tenant,
                        queryRaw(
                            "SELECT SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') AS value FROM DUAL",
                        ).toList().single()[0],
                    )
                }
                assertNull(
                    queryRaw(
                        "SELECT SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') AS value FROM DUAL",
                    ).toList().single()[0],
                )
            }
        }
        assertFailsWith<IllegalStateException> {
            database.transaction {
                rlsContext(
                    "CLIENT_IDENTIFIER" to "tenant_a",
                    adapter = adapter,
                ) { error("request failed") }
            }
        }
        database.transaction {
            assertNull(
                queryRaw("SELECT SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') AS value FROM DUAL").toList().single()[0],
            )
        }
        oracleVpdContract(database)
    } finally {
        database.executeRaw("DROP PACKAGE KAGIROI_RLS_CONTEXT")
    }
}
