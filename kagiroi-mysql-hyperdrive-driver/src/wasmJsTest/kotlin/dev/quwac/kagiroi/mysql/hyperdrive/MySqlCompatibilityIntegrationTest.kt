@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.unsafeCast
import kotlin.test.Test

class MySqlCompatibilityIntegrationTest {
    @Test
    fun mySqlDriverContract() = runTest {
        val connectionString = mySqlTestConnectionString() ?: return@runTest
        mySqlDriverContract(
            HyperdriveMySqlDatabase(testBinding(connectionString)) {
                install(MySqlKotlinxDateTime)
                install(MySqlQuwacBigNumber)
            },
        )
    }
}

private fun mySqlTestConnectionString(): String? = environmentVariable("KAGIROI_MYSQL_TEST_URL")?.toString()

private fun testBinding(connectionString: String): CloudflareHyperdriveBinding =
    bindingObject(connectionString).unsafeCast()

@Suppress("UnusedParameter")
private fun environmentVariable(name: String): JsString? =
    js("typeof process !== 'undefined' && typeof process.env[name] === 'string' ? process.env[name] : null")

@Suppress("UnusedParameter")
private fun bindingObject(connectionString: String): JsAny = js(
    "(() => { const url = new URL(connectionString); return { host: url.hostname, " +
        "user: decodeURIComponent(url.username), password: decodeURIComponent(url.password), " +
        "database: url.pathname.slice(1), port: Number(url.port || 3306) }; })()",
)
