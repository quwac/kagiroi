@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class NodePostgresCompatibilityIntegrationTest {
    @Test
    fun localPostgresContract() = runTest {
        val url = environmentVariable(LOCAL_TEST_URL) ?: return@runTest
        nodePostgresDriverContract(configuredDatabase(url))
        nodePostgresPoolContract(url)
    }

    @Test
    fun neonContract() = runTest {
        runProviderContract(NEON_TEST_URL)
    }

    @Test
    fun supabaseContract() = runTest {
        runProviderContract(SUPABASE_TEST_URL)
    }

    @Test
    fun auroraContract() = runTest {
        runProviderContract(AURORA_TEST_URL)
    }
}

private suspend fun runProviderContract(variable: String) {
    val url = environmentVariable(variable) ?: return
    nodePostgresDriverContract(configuredDatabase(url))
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
private const val NEON_TEST_URL = "KAGIROI_NEON_POSTGRES_TEST_URL"
private const val SUPABASE_TEST_URL = "KAGIROI_SUPABASE_POSTGRES_TEST_URL"
private const val AURORA_TEST_URL = "KAGIROI_AURORA_POSTGRES_TEST_URL"
private const val INTEGRATION_POOL_SIZE = 4
private val INTEGRATION_CONNECTION_TIMEOUT = 5.seconds
