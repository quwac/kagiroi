@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class NodePostgresCompatibilityIntegrationTest {
    @Test
    fun nativeRlsAndPoolReuse() = runTest {
        val url = environmentVariable(LOCAL_TEST_URL) ?: return@runTest
        val database = NodePostgresDatabase(url) { pool { maxConnections = 1 } }
        try {
            dev.quwac.kagiroi.sql.testing.postgresRlsContract(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun localPostgresContract() = runTest {
        val url = environmentVariable(LOCAL_TEST_URL) ?: return@runTest
        nodePostgresDriverContract(configuredDatabase(url))
        nodePostgresPoolContract(url)
    }
}

private fun configuredDatabase(url: String): NodePostgresDatabase = NodePostgresDatabase(url) {
    pool {
        maxConnections = INTEGRATION_POOL_SIZE
        connectionTimeout = INTEGRATION_CONNECTION_TIMEOUT
    }
    install(PostgresKotlinxDateTime)
    install(PostgresQuwacBigNumber)
}

@Suppress("UnusedParameter")
private fun environmentVariable(name: String): String? =
    js("typeof process !== 'undefined' ? process.env[name] : undefined") as? String

private const val LOCAL_TEST_URL = "KAGIROI_POSTGRES_TEST_URL"
private const val INTEGRATION_POOL_SIZE = 4
private val INTEGRATION_CONNECTION_TIMEOUT = 5.seconds
