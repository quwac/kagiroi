@file:OptIn(ExperimentalJsExport::class)

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
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

@JsExport
fun runHyperdriveMySqlContract(binding: CloudflareHyperdriveBinding): Promise<String> = Promise { resolve, reject ->
    val operation: suspend () -> String = {
        mySqlDriverContract(
            HyperdriveMySqlDatabase(binding) {
                install(MySqlKotlinxDateTime)
                install(MySqlQuwacBigNumber)
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

@JsExport
fun runHyperdriveMySqlSmoke(binding: CloudflareHyperdriveBinding): Promise<String> = Promise { resolve, reject ->
    val operation: suspend () -> String = {
        mySqlDriverSmoke(configuredDatabase(binding), ::println)
        "ok"
    }
    operation.startCoroutine(completion(resolve, reject))
}

@JsExport
fun runHyperdriveMariaDbContract(binding: CloudflareHyperdriveBinding): Promise<String> = Promise { resolve, reject ->
    val operation: suspend () -> String = {
        mySqlDriverContract(configuredMariaDbDatabase(binding), ::println)
        "ok"
    }
    operation.startCoroutine(completion(resolve, reject))
}

@JsExport
fun runHyperdriveMariaDbSmoke(binding: CloudflareHyperdriveBinding): Promise<String> = Promise { resolve, reject ->
    val operation: suspend () -> String = {
        mySqlDriverSmoke(configuredMariaDbDatabase(binding), ::println)
        "ok"
    }
    operation.startCoroutine(completion(resolve, reject))
}

private fun configuredDatabase(binding: CloudflareHyperdriveBinding) = HyperdriveMySqlDatabase(binding) {
    install(MySqlKotlinxDateTime)
    install(MySqlQuwacBigNumber)
}

private fun configuredMariaDbDatabase(binding: CloudflareHyperdriveBinding) = HyperdriveMySqlDatabase(binding) {
    install(MySqlKotlinxDateTime)
    install(MySqlQuwacBigNumber)
    dialect(MariaDbDialect.configured(listOf(MySqlKotlinxDateTime, MySqlQuwacBigNumber)))
}

private fun completion(resolve: (String) -> Unit, reject: (Throwable) -> Unit) = object : Continuation<String> {
    override val context = EmptyCoroutineContext

    override fun resumeWith(result: Result<String>) {
        result.fold(resolve) { error -> reject(jsError(error.toString())) }
    }
}

fun main() = Unit

@Suppress("UnusedParameter")
private fun jsError(message: String): Throwable = js("new Error(message)")
