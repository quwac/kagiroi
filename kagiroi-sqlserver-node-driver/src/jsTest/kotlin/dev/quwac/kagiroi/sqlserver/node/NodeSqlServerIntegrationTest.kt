package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.sql.dedicatedSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class NodeSqlServerIntegrationTest {
    @Test
    fun dedicatedSessionKeepsExplicitTransactionOnOneConnection() = runTest {
        val host = environmentVariable("KAGIROI_SQLSERVER_TEST_HOST") ?: return@runTest
        val database = NodeSqlServerDatabase {
            this.host = host
            port = environmentVariable("KAGIROI_SQLSERVER_TEST_PORT")?.toInt() ?: 1433
            this.database = environmentVariable("KAGIROI_SQLSERVER_TEST_DATABASE") ?: "master"
            user = environmentVariable("KAGIROI_SQLSERVER_TEST_USER") ?: "sa"
            password = environmentVariable("KAGIROI_SQLSERVER_TEST_PASSWORD") ?: "Kagiroi!Passw0rd"
            tls { mode = NodeSqlServerTlsMode.REQUIRE }
        }
        try {
            database.dedicatedSession {
                val first = queryRaw("SELECT @@SPID AS session_id").toList().single().long("session_id")
                executeRaw("BEGIN TRANSACTION")
                executeRaw("CREATE TABLE #kagiroi_session_contract (id INT NOT NULL)")
                executeRaw("INSERT INTO #kagiroi_session_contract VALUES (1)")
                assertEquals(
                    1L,
                    queryRaw("SELECT COUNT(*) AS value FROM #kagiroi_session_contract").toList().single().long("value"),
                )
                assertEquals(first, queryRaw("SELECT @@SPID AS session_id").toList().single().long("session_id"))
                executeRaw("ROLLBACK TRANSACTION")
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun sqlServerContract() = runTest {
        val host = environmentVariable("KAGIROI_SQLSERVER_TEST_HOST") ?: return@runTest
        nodeSqlServerDriverContract(
            NodeSqlServerDatabase {
                this.host = host
                port = environmentVariable("KAGIROI_SQLSERVER_TEST_PORT")?.toInt() ?: 1433
                database = environmentVariable("KAGIROI_SQLSERVER_TEST_DATABASE") ?: "master"
                user = environmentVariable("KAGIROI_SQLSERVER_TEST_USER") ?: "sa"
                password = environmentVariable("KAGIROI_SQLSERVER_TEST_PASSWORD") ?: "Kagiroi!Passw0rd"
                pool {
                    maxConnections = 4
                    connectionTimeout = 10.seconds
                }
                tls {
                    mode = NodeSqlServerTlsMode.REQUIRE
                }
            },
        )
    }
}

@Suppress("UnusedParameter")
private fun environmentVariable(name: String): String? =
    js("typeof process !== 'undefined' ? process.env[name] : undefined") as? String
