@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("MagicNumber")

package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.oracle.kotlinx.datetime.OracleKotlinxDateTime
import dev.quwac.kagiroi.oracle.quwac.bignumber.OracleQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.js.JsString
import kotlin.test.Test

/** Represents node oracle integration test. */
class NodeOracleIntegrationTest {
    /** Verifies native Oracle context cleanup on one reused physical session. */
    @Test
    fun nativeContextAndPoolReuse() = runTest {
        val settings = integrationSettings() ?: return@runTest
        val database = NodeOracleDatabase {
            user = settings.user
            password = settings.password
            connectString = settings.connectString
            pool { maxConnections = 1 }
        }
        try {
            oracleRlsContextContract(database)
        } finally {
            database.close()
        }
    }

    /** Provides the oracle23 contract result. */
    @Test
    fun oracle23Contract() = runTest {
        val settings = integrationSettings() ?: return@runTest
        oracleIntegrationContract(configuredDatabase(settings))
    }
}

private data class OracleIntegrationSettings(val connectString: String, val user: String, val password: String)

private fun integrationSettings(): OracleIntegrationSettings? {
    val connectString = environmentVariable("KAGIROI_ORACLE_TEST_CONNECT_STRING") ?: return null
    val user = environmentVariable("KAGIROI_ORACLE_TEST_USER") ?: return null
    val password = environmentVariable("KAGIROI_ORACLE_TEST_PASSWORD") ?: return null
    return OracleIntegrationSettings(connectString, user, password)
}

private fun configuredDatabase(settings: OracleIntegrationSettings): NodeOracleDatabase = NodeOracleDatabase {
    connectString = settings.connectString
    user = settings.user
    password = settings.password
    pool { maxConnections = 4 }
    install(OracleQuwacBigNumber)
    install(OracleKotlinxDateTime)
}

private fun environmentVariable(name: String): String? = environmentVariableValue(name)?.toString()

@Suppress("UnusedParameter")
private fun environmentVariableValue(name: String): JsString? =
    js("typeof process !== 'undefined' && typeof process.env[name] === 'string' ? process.env[name] : null")
