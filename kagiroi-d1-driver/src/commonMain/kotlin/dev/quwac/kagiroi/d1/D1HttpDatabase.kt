package dev.quwac.kagiroi.d1

import kotlinx.coroutines.CancellationException

/** Opens the D1 account HTTP API on JS and Wasm; native Workers should use a D1 binding. Sessions are unavailable. */
fun d1HttpDatabase(
    accountId: String,
    databaseId: String,
    apiToken: String,
    configure: D1DatabaseConfiguration.() -> Unit = {},
): D1Database {
    require(accountId.isNotBlank()) { "D1 account ID must not be blank" }
    require(databaseId.isNotBlank()) { "D1 database ID must not be blank" }
    require(apiToken.isNotBlank()) { "D1 API token must not be blank" }
    return D1Database.create(D1HttpEndpoint(accountId, databaseId, apiToken), configure)
}

private class D1HttpEndpoint(accountId: String, databaseId: String, private val apiToken: String) : D1Endpoint {
    private val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/d1/database/$databaseId/query"
    override val supportsSessions: Boolean = false

    override suspend fun execute(statement: D1Statement): D1ExecuteMetadata = single(statement).metadata
    override suspend fun query(statement: D1Statement): D1RawRows = single(statement).rows
    override suspend fun batch(statements: List<D1Statement>): List<D1BatchOutcome> {
        require(statements.isNotEmpty()) { "D1 batch must not be empty" }
        return request(statements, batch = true).map { D1BatchOutcome(it.metadata, it.rows) }
    }

    override fun session(start: D1SessionStart): D1SessionEndpoint =
        throw UnsupportedOperationException("D1 HTTP API does not support D1 Sessions")

    private suspend fun single(statement: D1Statement): D1HttpResult =
        request(listOf(statement), batch = false).singleOrNull() ?: throw D1DriverException(
            D1FailureKind.EXECUTION,
            null,
            "D1 HTTP API returned an unexpected number of query results",
        )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun request(statements: List<D1Statement>, batch: Boolean): List<D1HttpResult> = try {
        requestD1Http(url, apiToken, statements, batch)
    } catch (error: CancellationException) {
        throw error
    } catch (error: D1DriverException) {
        throw error
    } catch (error: Throwable) {
        throw D1DriverException(D1FailureKind.EXECUTION, null, error.message ?: "D1 HTTP request failed", error)
    }
}

internal data class D1HttpResult(val metadata: D1ExecuteMetadata, val rows: D1RawRows)
internal expect suspend fun requestD1Http(
    url: String,
    token: String,
    statements: List<D1Statement>,
    batch: Boolean,
): List<D1HttpResult>
