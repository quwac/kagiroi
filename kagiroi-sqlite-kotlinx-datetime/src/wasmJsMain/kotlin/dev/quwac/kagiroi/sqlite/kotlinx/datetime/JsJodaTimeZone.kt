@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.sqlite.kotlinx.datetime

@JsModule("@js-joda/timezone")
private external object JsJodaTimeZoneModule

@Suppress("UNUSED_EXPRESSION")
internal actual fun initializeTimeZoneSupport() {
    JsJodaTimeZoneModule
}
