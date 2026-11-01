package dev.quwac.kagiroi.sqlite

internal actual fun rawSignedNumberBinding(value: Number): SqlitePhysicalValue =
    if (value is Long) SqlitePhysicalValue.Integer(value.toString()) else numericBinding(value.toDouble())
