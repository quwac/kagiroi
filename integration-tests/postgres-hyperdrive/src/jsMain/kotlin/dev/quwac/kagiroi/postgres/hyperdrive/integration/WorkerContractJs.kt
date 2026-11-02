@file:OptIn(ExperimentalJsExport::class)

package dev.quwac.kagiroi.postgres.hyperdrive.integration

import dev.quwac.kagiroi.postgres.hyperdrive.CloudflareHyperdriveBinding
import dev.quwac.kagiroi.postgres.hyperdrive.HyperdrivePostgresDatabase
import dev.quwac.kagiroi.postgres.hyperdrive.postgresDriverContract
import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

@JsExport
fun runHyperdrivePostgresContract(binding: CloudflareHyperdriveBinding): Promise<String> = Promise { resolve, reject ->
    val operation: suspend () -> String = {
        postgresDriverContract(
            HyperdrivePostgresDatabase(binding) {
                install(PostgresKotlinxDateTime)
                install(PostgresQuwacBigNumber)
            },
            ::println,
        )
        "ok"
    }
    operation.startCoroutine(object : Continuation<String> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<String>) {
            result.fold(resolve) { error -> reject(jsError(error.toString())) }
        }
    })
}

fun main() = Unit

@Suppress("UnusedParameter")
private fun jsError(message: String): Throwable = js("new Error(message)")
