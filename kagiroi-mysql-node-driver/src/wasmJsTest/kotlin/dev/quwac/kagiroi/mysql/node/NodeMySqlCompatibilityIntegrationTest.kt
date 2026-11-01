@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.js.JsString
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class NodeMySqlCompatibilityIntegrationTest {
    @Test
    fun analyticalWindowFunctions() = runTest {
        val url = environmentVariable(LOCAL_TEST_URL) ?: return@runTest
        verifyDistributionWindowSql(configuredDatabase(url))
        verifyMySqlExplicitNullTreatment(configuredDatabase(url))
    }

    @Test
    fun localMySqlContract() = runTest {
        val url = environmentVariable(LOCAL_TEST_URL) ?: return@runTest
        nodeMySqlDriverContract(configuredDatabase(url))
        nodeMySqlPoolContract(url)
        nodeMySqlPhysicalTypeContract(configuredDatabase(url))
        verifyMySqlCompatibleIndexAndUniqueComments(configuredDatabase(url))
        verifyMySqlDuplicateKeyUpdate(configuredDatabase(url))
    }

    @Test
    fun planetScaleContract() = runTest {
        runProviderContract(PLANETSCALE_TEST_URL)
    }

    @Test
    fun auroraContract() = runTest {
        runProviderContract(AURORA_TEST_URL)
    }
}

private suspend fun runProviderContract(variable: String) {
    val url = environmentVariable(variable) ?: return
    nodeMySqlDriverContract(configuredDatabase(url))
}

private fun configuredDatabase(url: String): NodeMySqlDatabase = NodeMySqlDatabase(url) {
    pool {
        maxConnections = INTEGRATION_POOL_SIZE
        acquisitionTimeout = INTEGRATION_ACQUISITION_TIMEOUT
    }
    install(MySqlKotlinxDateTime)
    install(MySqlQuwacBigNumber)
}

private fun environmentVariable(name: String): String? = environmentVariableValue(name)?.toString()

@Suppress("UnusedParameter")
private fun environmentVariableValue(name: String): JsString? =
    js("typeof process !== 'undefined' && typeof process.env[name] === 'string' ? process.env[name] : null")

private const val LOCAL_TEST_URL = "KAGIROI_MYSQL_TEST_URL"
private const val PLANETSCALE_TEST_URL = "KAGIROI_PLANETSCALE_MYSQL_TEST_URL"
private const val AURORA_TEST_URL = "KAGIROI_AURORA_MYSQL_TEST_URL"
private const val INTEGRATION_POOL_SIZE = 4
private val INTEGRATION_ACQUISITION_TIMEOUT = 5.seconds
