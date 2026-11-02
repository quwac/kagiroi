package dev.quwac.kagiroi.postgres.kotlinx.datetime

@JsModule("@js-joda/timezone")
@JsNonModule
private external object JsJodaTimeZoneModule

@Suppress("UNUSED_EXPRESSION")
internal actual fun initializeTimeZoneSupport() {
    JsJodaTimeZoneModule
}
