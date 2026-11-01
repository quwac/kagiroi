package dev.quwac.kagiroi.sqlserver.kotlinx.datetime

@JsModule("@js-joda/timezone")
@JsNonModule
private external object JsJodaTimeZoneModule

internal actual fun initializeTimeZoneSupport() {
    JsJodaTimeZoneModule
}
