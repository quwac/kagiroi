@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.js.JsString
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class NodePostgresCompatibilityIntegrationTest {
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

private fun environmentVariable(name: String): String? = environmentVariableValue(name)?.toString()

@Suppress("UnusedParameter")
private fun environmentVariableValue(name: String): JsString? =
    js("typeof process !== 'undefined' && typeof process.env[name] === 'string' ? process.env[name] : null")

private const val LOCAL_TEST_URL = "KAGIROI_POSTGRES_TEST_URL"
private const val INTEGRATION_POOL_SIZE = 4
private val INTEGRATION_CONNECTION_TIMEOUT = 5.seconds
