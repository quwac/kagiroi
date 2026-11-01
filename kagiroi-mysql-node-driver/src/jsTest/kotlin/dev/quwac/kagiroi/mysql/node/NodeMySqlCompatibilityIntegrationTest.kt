@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlinx.coroutines.test.runTest
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

@Suppress("UnusedParameter")
private fun environmentVariable(name: String): String? =
    js("typeof process !== 'undefined' ? process.env[name] : undefined") as? String

private const val LOCAL_TEST_URL = "KAGIROI_MYSQL_TEST_URL"
private const val PLANETSCALE_TEST_URL = "KAGIROI_PLANETSCALE_MYSQL_TEST_URL"
private const val AURORA_TEST_URL = "KAGIROI_AURORA_MYSQL_TEST_URL"
private const val INTEGRATION_POOL_SIZE = 4
private val INTEGRATION_ACQUISITION_TIMEOUT = 5.seconds
