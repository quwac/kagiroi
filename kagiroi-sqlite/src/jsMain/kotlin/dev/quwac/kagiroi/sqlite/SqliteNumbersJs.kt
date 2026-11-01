package dev.quwac.kagiroi.sqlite

internal actual fun rawSignedNumberBinding(value: Number): SqlitePhysicalValue = if (isNativeNumber(value)) {
    numericBinding(value.toDouble())
} else {
    SqlitePhysicalValue.Integer(value.toLong().toString())
}

@Suppress("UnusedParameter")
private fun isNativeNumber(value: Number): Boolean = js("typeof value === 'number'")
