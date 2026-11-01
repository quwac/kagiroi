@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PostgresCompatibilityIntegrationTest {
    @Test
    fun postgresDriverContract() = runTest {
        val connectionString = postgresTestConnectionString() ?: return@runTest
        postgresDriverContract(
            HyperdrivePostgresDatabase(testBinding(connectionString)) {
                install(PostgresKotlinxDateTime)
                install(PostgresQuwacBigNumber)
            },
        )
    }
}

private fun postgresTestConnectionString(): String? =
    js("typeof process !== 'undefined' ? process.env.KAGIROI_POSTGRES_TEST_URL : undefined") as? String

@Suppress("UnusedParameter")
private fun testBinding(connectionString: String): CloudflareHyperdriveBinding =
    js("({ connectionString })").unsafeCast<CloudflareHyperdriveBinding>()
