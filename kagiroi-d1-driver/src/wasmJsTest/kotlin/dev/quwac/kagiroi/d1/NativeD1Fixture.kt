@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.d1

import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.JsModule
import kotlin.js.Promise

@JsModule("miniflare")
private external object NativeMiniflareModule : JsAny {
    val Miniflare: JsAny

    @Suppress("UnusedParameter")
    fun convertV4MiniflareOptions(options: JsAny): JsAny
}

private external interface NativeMiniflare : JsAny {
    fun getD1Database(name: String): Promise<JsAny>
    fun dispose(): Promise<JsAny?>
}

internal actual suspend fun withNativeD1(block: suspend (Any) -> Unit) {
    val host = miniflare(NativeMiniflareModule.Miniflare, NativeMiniflareModule.convertV4MiniflareOptions(options()))
    try {
        block(host.getD1Database("DB").await())
    } finally {
        host.dispose().await()
    }
}

@Suppress("UnusedParameter")
private fun miniflare(constructor: JsAny, config: JsAny): NativeMiniflare = js("new constructor(config)")
private fun options(): JsAny = js(
    "({ modules: true, script: 'export default { fetch() { return new Response(); } }', d1Databases: ['DB'] })",
)
