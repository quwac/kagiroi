@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mariadb.MariaDbDialect
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.unsafeCast
import kotlin.test.Test

/** Opt-in MariaDB origin contract through the same Kotlin/Wasm Hyperdrive transport. */
class MariaDbCompatibilityIntegrationTest {
    /** Provides the maria db hyperdrive compatible contract result. */
    @Test
    fun mariaDbHyperdriveCompatibleContract() = runTest {
        val connectionString = mariaDbTestConnectionString() ?: return@runTest
        mySqlDriverContract(
            HyperdriveMySqlDatabase(mariaDbBinding(connectionString)) {
                install(MySqlKotlinxDateTime)
                install(MySqlQuwacBigNumber)
                dialect(MariaDbDialect.configured(listOf(MySqlKotlinxDateTime, MySqlQuwacBigNumber)))
            },
        )
    }
}

private fun mariaDbTestConnectionString(): String? = mariaDbEnvironmentVariable()?.toString()

private fun mariaDbBinding(connectionString: String): CloudflareHyperdriveBinding =
    mariaDbBindingObject(connectionString).unsafeCast()

@Suppress("UnusedParameter")
private fun mariaDbEnvironmentVariable(): JsString? = js(
    "typeof process !== 'undefined' && typeof process.env.KAGIROI_MARIADB_TEST_URL === 'string' ? process.env.KAGIROI_MARIADB_TEST_URL : null",
)

@Suppress("UnusedParameter")
private fun mariaDbBindingObject(connectionString: String): JsAny = js(
    "(() => { const url = new URL(connectionString); return { host: url.hostname, " +
        "user: decodeURIComponent(url.username), password: decodeURIComponent(url.password), " +
        "database: url.pathname.slice(1), port: Number(url.port || 3306) }; })()",
)
