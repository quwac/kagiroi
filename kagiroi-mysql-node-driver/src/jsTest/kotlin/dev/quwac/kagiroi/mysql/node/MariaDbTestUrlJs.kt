package dev.quwac.kagiroi.mysql.node

@Suppress("UnsafeCastFromDynamic", "UnusedParameter")
internal actual fun mariaDbTestUrl(): String? =
    js("typeof process !== 'undefined' ? process.env.KAGIROI_MARIADB_TEST_URL : undefined") as? String
