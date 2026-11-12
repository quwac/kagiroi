@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("MagicNumber", "UnusedParameter")

package dev.quwac.kagiroi.sqlserver.node

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Represents node sql server integration test. */
class NodeSqlServerIntegrationTest {
    /** Verifies native row security and clean reuse of one pooled connection. */
    @Test
    fun nativeRlsAndPoolReuse() = runTest {
        val host = environmentVariable("KAGIROI_SQLSERVER_TEST_HOST") ?: return@runTest
        val database = NodeSqlServerDatabase {
            this.host = host
            port = environmentVariable("KAGIROI_SQLSERVER_TEST_PORT")?.toInt() ?: 1433
            this.database = environmentVariable("KAGIROI_SQLSERVER_TEST_DATABASE") ?: "master"
            user = environmentVariable("KAGIROI_SQLSERVER_TEST_USER") ?: "sa"
            password = environmentVariable("KAGIROI_SQLSERVER_TEST_PASSWORD") ?: "Kagiroi!Passw0rd"
            pool { maxConnections = 1 }
            tls { mode = NodeSqlServerTlsMode.REQUIRE }
        }
        try {
            sqlServerRlsContract(database)
        } finally {
            database.close()
        }
    }

    /** Provides the sql server contract result. */
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

private fun environmentVariable(name: String): String? = js(
    "typeof process !== 'undefined' && typeof process.env[name] === 'string' ? process.env[name] : null",
)
