@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.js.JsString
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Represents node my sql compatibility integration test. */
class NodeMySqlCompatibilityIntegrationTest {
    /** Provides the analytical window functions result. */
    @Test
    fun analyticalWindowFunctions() = runTest {
        val url = environmentVariable(LOCAL_TEST_URL) ?: return@runTest
        verifyDistributionWindowSql(configuredDatabase(url))
        verifyMySqlExplicitNullTreatment(configuredDatabase(url))
    }

    /** Provides the local my sql contract result. */
    @Test
    fun localMySqlContract() = runTest {
        val url = environmentVariable(LOCAL_TEST_URL) ?: return@runTest
        nodeMySqlDriverContract(configuredDatabase(url))
        nodeMySqlPoolContract(url)
        nodeMySqlPhysicalTypeContract(configuredDatabase(url))
        verifyMySqlCompatibleIndexAndUniqueComments(configuredDatabase(url))
        verifyMySqlDuplicateKeyUpdate(configuredDatabase(url))
    }
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
private const val INTEGRATION_POOL_SIZE = 4
private val INTEGRATION_ACQUISITION_TIMEOUT = 5.seconds
