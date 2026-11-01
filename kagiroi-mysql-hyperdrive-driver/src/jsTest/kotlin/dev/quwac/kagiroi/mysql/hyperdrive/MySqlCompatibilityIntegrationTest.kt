@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlinx.coroutines.test.runTest
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

private fun mySqlTestConnectionString(): String? =
    js("typeof process !== 'undefined' ? process.env.KAGIROI_MYSQL_TEST_URL : undefined") as? String

@Suppress("UnusedParameter")
private fun testBinding(connectionString: String): CloudflareHyperdriveBinding = js(
    "(() => { const url = new URL(connectionString); return { host: url.hostname, " +
        "user: decodeURIComponent(url.username), password: decodeURIComponent(url.password), " +
        "database: url.pathname.slice(1), port: Number(url.port || 3306) }; })()",
).unsafeCast<CloudflareHyperdriveBinding>()
