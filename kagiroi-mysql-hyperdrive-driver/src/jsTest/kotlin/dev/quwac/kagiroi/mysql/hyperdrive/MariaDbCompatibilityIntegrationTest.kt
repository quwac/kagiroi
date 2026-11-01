@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mariadb.MariaDbDialect
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Opt-in MariaDB origin contract through the same Hyperdrive mysql2 transport. */
class MariaDbCompatibilityIntegrationTest {
    @Test
    fun mariaDbHyperdriveCompatibleContract() = runTest {
        val connectionString = mariaDbTestConnectionString() ?: return@runTest
        mySqlDriverContract(
            HyperdriveMySqlDatabase(testMariaDbBinding(connectionString)) {
                install(MySqlKotlinxDateTime)
                install(MySqlQuwacBigNumber)
                dialect(MariaDbDialect.configured(listOf(MySqlKotlinxDateTime, MySqlQuwacBigNumber)))
            },
        )
    }
}

private fun mariaDbTestConnectionString(): String? =
    js("typeof process !== 'undefined' ? process.env.KAGIROI_MARIADB_TEST_URL : undefined") as? String

@Suppress("UnusedParameter")
private fun testMariaDbBinding(connectionString: String): CloudflareHyperdriveBinding = js(
    "(() => { const url = new URL(connectionString); return { host: url.hostname, " +
        "user: decodeURIComponent(url.username), password: decodeURIComponent(url.password), " +
        "database: url.pathname.slice(1), port: Number(url.port || 3306) }; })()",
).unsafeCast<CloudflareHyperdriveBinding>()
