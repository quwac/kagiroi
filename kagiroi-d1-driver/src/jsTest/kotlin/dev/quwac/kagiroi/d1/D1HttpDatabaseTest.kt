@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.sql.ExecutionFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class D1HttpDatabaseTest {
    @Test
    fun executeReadsStatementMetadata() = runTest {
        val previous = replaceFetch(
            """{"success":true,"result":[{"success":true,"meta":{"changes":3},"results":[]}]}""",
        )
        try {
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            assertEquals(3L, database.executeRaw("UPDATE users SET active = 1").affectedRows)
            database.close()
        } finally {
            restoreFetch(previous)
        }
    }

    @Test
    fun executeRejectsFailedStatement() = runTest {
        val previous = replaceFetch(
            """{"success":true,"result":[{"success":false,"error":{"message":"statement failed"}}]}""",
        )
        try {
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            assertFailsWith<ExecutionFailure> { database.executeRaw("invalid SQL") }
            database.close()
        } finally {
            restoreFetch(previous)
        }
    }
}

@Suppress("UnusedParameter")
private fun replaceFetch(json: String): dynamic = js(
    "(() => { const previous = globalThis.fetch; globalThis.fetch = () => Promise.resolve({ ok: true, " +
        "json: () => Promise.resolve(JSON.parse(json)) }); return previous; })()",
)

@Suppress("UnusedParameter")
private fun restoreFetch(previous: dynamic) {
    js("globalThis.fetch = previous")
}
