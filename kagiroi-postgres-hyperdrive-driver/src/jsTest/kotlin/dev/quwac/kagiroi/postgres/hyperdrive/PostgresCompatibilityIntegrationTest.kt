@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PostgresCompatibilityIntegrationTest {
    /** Verifies native RLS and context cleanup through the Hyperdrive driver. */
    @Test
    fun nativeRlsContext() = runTest {
        val connectionString = postgresTestConnectionString() ?: return@runTest
        val database = HyperdrivePostgresDatabase(testBinding(connectionString))
        try {
            dev.quwac.kagiroi.sql.testing.postgresRlsContract(database)
        } finally {
            database.close()
        }
    }

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
