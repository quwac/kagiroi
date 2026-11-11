package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.InvalidBinding
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class D1HttpDatabaseTest {
    @Test
    fun executeReadsMetadataAndSendsExactParametersAndAuthorization() = runTest {
        withResponse("""{"success":true,"result":[{"success":true,"meta":{"changes":3},"results":[]}]}""") { fixture ->
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            assertEquals(
                3L,
                database.executeRaw(
                    "UPDATE users SET name = ? WHERE id = ?",
                    listOf("日本語", Long.MAX_VALUE),
                ).affectedRows,
            )
            assertEquals(
                D1HttpRequest(
                    "https://api.cloudflare.com/client/v4/accounts/account/d1/database/database/query",
                    "Bearer synthetic-token",
                    """{"sql":"UPDATE users SET name = ? WHERE id = ?","params":["日本語","9223372036854775807"]}""",
                ),
                capturedD1HttpRequest(fixture),
            )
            database.close()
        }
    }

    @Test
    fun queryReadsTextIntegersRealsAndNull() = runTest {
        withResponse(
            """{"result":[{"success":true,"results":[{"name":"日本語","count":7,"ratio":1.5,"missing":null}]}]}""",
        ) {
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            val row = database.queryRaw("SELECT values").toList().single()
            assertEquals("日本語", row.string("name"))
            assertEquals(7L, row.long("count"))
            assertEquals(1.5, row.double("ratio"))
            assertNull(row["missing"])
            database.close()
        }
    }

    @Test
    fun batchPreservesRowsMetadataAndRequestShape() = runTest {
        withResponse(
            """{"result":[{"success":true,"meta":{"changes":1,"rows_read":2},"results":[{"name":"Ada"}]}]}""",
        ) { fixture ->
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            val result = database.batch { raw("SELECT name WHERE id = ?", listOf(1)) }.single()
            assertEquals("Ada", result.rows.single().string("name"))
            assertEquals(1L, result.result.affectedRows)
            assertEquals(2L, result.metadata.rowsRead)
            assertEquals(
                """{"batch":[{"sql":"SELECT name WHERE id = ?","params":["1"]}]}""",
                capturedD1HttpRequest(fixture)?.body,
            )
            assertNull(database.capability(D1Capabilities.Sessions))
            assertFailsWith<IllegalStateException> { database.session() }
            database.close()
        }
    }

    @Test
    fun rejectsFailedStatements() = runTest {
        withResponse("""{"result":[{"success":false,"error":{"code":123,"message":"statement failed"}}]}""") {
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            assertFailsWith<ExecutionFailure> { database.executeRaw("invalid SQL") }
            database.close()
        }
    }

    @Test
    fun rejectsFailedHttpResponses() = runTest {
        withResponse("""{"success":false,"errors":[{"message":"service unavailable"}]}""", status = 503) {
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            assertFailsWith<ExecutionFailure> { database.executeRaw("SELECT 1") }
            database.close()
        }
    }

    @Test
    fun rejectsBlobBindingsBeforeCallingFetch() = runTest {
        withResponse("""{"result":[]}""") { fixture ->
            val database = d1HttpDatabase("account", "database", "synthetic-token")
            assertFailsWith<InvalidBinding> { database.executeRaw("SELECT ?", listOf(byteArrayOf(1))) }
            assertNull(capturedD1HttpRequest(fixture))
            database.close()
        }
    }

    @Test
    fun rejectsBlankAccountConfiguration() {
        assertFailsWith<IllegalArgumentException> { d1HttpDatabase(" ", "database", "token") }
        assertFailsWith<IllegalArgumentException> { d1HttpDatabase("account", " ", "token") }
        assertFailsWith<IllegalArgumentException> { d1HttpDatabase("account", "database", " ") }
    }
}

private suspend fun withResponse(json: String, status: Int = 200, block: suspend (Any) -> Unit) {
    val fixture = replaceD1HttpFetch(json, status)
    try {
        block(fixture)
    } finally {
        restoreD1HttpFetch(fixture)
    }
}

internal data class D1HttpRequest(val url: String, val authorization: String, val body: String)
internal expect fun replaceD1HttpFetch(json: String, status: Int): Any
internal expect fun restoreD1HttpFetch(previous: Any)
internal expect fun capturedD1HttpRequest(fixture: Any): D1HttpRequest?
