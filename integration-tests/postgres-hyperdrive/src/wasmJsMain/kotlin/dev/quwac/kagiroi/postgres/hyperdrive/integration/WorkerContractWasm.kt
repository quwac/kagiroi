@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.postgres.hyperdrive.integration

import dev.quwac.kagiroi.postgres.hyperdrive.CloudflareHyperdriveBinding
import dev.quwac.kagiroi.postgres.hyperdrive.HyperdrivePostgresDatabase
import dev.quwac.kagiroi.postgres.hyperdrive.postgresDriverContract
import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.js.JsExport
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.js.unsafeCast

/** Runs the run hyperdrive postgres contract result. */
@JsExport
fun runHyperdrivePostgresContract(binding: JsAny): Promise<JsString> = Promise { resolve, reject ->
    val operation: suspend () -> JsString = {
        postgresDriverContract(
            HyperdrivePostgresDatabase(binding.unsafeCast<CloudflareHyperdriveBinding>()) {
                install(PostgresKotlinxDateTime)
                install(PostgresQuwacBigNumber)
            },
            ::println,
        )
        "ok".toJsString()
    }
    operation.startCoroutine(object : Continuation<JsString> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<JsString>) {
            result.fold(resolve) { error -> reject(jsError(error.toString())) }
        }
    })
}

/** Provides the main result. */
fun main() = Unit

@Suppress("UnusedParameter")
private fun jsError(message: String): JsAny = js("new Error(message)")
