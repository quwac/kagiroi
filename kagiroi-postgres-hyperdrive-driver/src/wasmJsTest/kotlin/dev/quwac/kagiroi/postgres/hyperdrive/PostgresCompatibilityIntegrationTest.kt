@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.unsafeCast
import kotlin.test.Test

/** Represents postgres compatibility integration test. */
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

    /** Provides the postgres driver contract result. */
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

private fun postgresTestConnectionString(): String? = environmentVariable("KAGIROI_POSTGRES_TEST_URL")?.toString()

private fun testBinding(connectionString: String): CloudflareHyperdriveBinding =
    bindingObject(connectionString).unsafeCast()

@Suppress("UnusedParameter")
private fun environmentVariable(name: String): JsString? =
    js("typeof process !== 'undefined' && typeof process.env[name] === 'string' ? process.env[name] : null")

@Suppress("UnusedParameter")
private fun bindingObject(connectionString: String): JsAny = js("({ connectionString })")
