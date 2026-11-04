@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.mysql.hyperdrive.integration

import dev.quwac.kagiroi.mariadb.MariaDbDialect
import dev.quwac.kagiroi.mysql.hyperdrive.CloudflareHyperdriveBinding
import dev.quwac.kagiroi.mysql.hyperdrive.HyperdriveMySqlDatabase
import dev.quwac.kagiroi.mysql.hyperdrive.mySqlDriverContract
import dev.quwac.kagiroi.mysql.hyperdrive.mySqlDriverSmoke
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.js.JsExport
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.js.unsafeCast

/** Runs the run hyperdrive my sql contract result. */
@JsExport
fun runHyperdriveMySqlContract(binding: JsAny): Promise<JsString> = Promise { resolve, reject ->
    val operation: suspend () -> JsString = {
        mySqlDriverContract(
            HyperdriveMySqlDatabase(binding.unsafeCast<CloudflareHyperdriveBinding>()) {
                install(MySqlKotlinxDateTime)
                install(MySqlQuwacBigNumber)
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

/** Runs the run hyperdrive my sql smoke result. */
@JsExport
fun runHyperdriveMySqlSmoke(binding: JsAny): Promise<JsString> = Promise { resolve, reject ->
    val operation: suspend () -> JsString = {
        mySqlDriverSmoke(configuredDatabase(binding), ::println)
        "ok".toJsString()
    }
    operation.startCoroutine(completion(resolve, reject))
}

/** Runs the run hyperdrive maria db contract result. */
@JsExport
fun runHyperdriveMariaDbContract(binding: JsAny): Promise<JsString> = Promise { resolve, reject ->
    val operation: suspend () -> JsString = {
        mySqlDriverContract(configuredMariaDbDatabase(binding), ::println)
        "ok".toJsString()
    }
    operation.startCoroutine(completion(resolve, reject))
}

/** Runs the run hyperdrive maria db smoke result. */
@JsExport
fun runHyperdriveMariaDbSmoke(binding: JsAny): Promise<JsString> = Promise { resolve, reject ->
    val operation: suspend () -> JsString = {
        mySqlDriverSmoke(configuredMariaDbDatabase(binding), ::println)
        "ok".toJsString()
    }
    operation.startCoroutine(completion(resolve, reject))
}

private fun configuredDatabase(binding: JsAny) = HyperdriveMySqlDatabase(
    binding.unsafeCast<CloudflareHyperdriveBinding>(),
) {
    install(MySqlKotlinxDateTime)
    install(MySqlQuwacBigNumber)
}

private fun configuredMariaDbDatabase(binding: JsAny) = HyperdriveMySqlDatabase(
    binding.unsafeCast<CloudflareHyperdriveBinding>(),
) {
    install(MySqlKotlinxDateTime)
    install(MySqlQuwacBigNumber)
    dialect(MariaDbDialect.configured(listOf(MySqlKotlinxDateTime, MySqlQuwacBigNumber)))
}

private fun completion(resolve: (JsString) -> Unit, reject: (JsAny) -> Unit) = object : Continuation<JsString> {
    override val context = EmptyCoroutineContext

    override fun resumeWith(result: Result<JsString>) {
        result.fold(resolve) { error -> reject(jsError(error.toString())) }
    }
}

/** Provides the main result. */
fun main() = Unit

@Suppress("UnusedParameter")
private fun jsError(message: String): JsAny = js("new Error(message)")
