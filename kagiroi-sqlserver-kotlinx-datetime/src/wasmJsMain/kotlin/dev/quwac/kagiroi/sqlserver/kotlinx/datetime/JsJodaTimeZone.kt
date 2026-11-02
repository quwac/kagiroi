@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.sqlserver.kotlinx.datetime

import kotlin.js.JsAny

@JsModule("@js-joda/timezone")
private external object JsJodaTimeZoneModule : JsAny

internal actual fun initializeTimeZoneSupport() {
    JsJodaTimeZoneModule
}
