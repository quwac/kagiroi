package dev.quwac.kagiroi.d1

/** A validated native D1 handle. Conversion retains the host object without copying or serializing it. */
class D1Binding(native: Any) {
    internal val endpoint: D1Endpoint = createD1Endpoint(native)
}

/** Creates a D1 database from a common handle; native operations remain inside the driver. */
fun D1Database(binding: D1Binding, configure: D1DatabaseConfiguration.() -> Unit = {}): D1Database =
    D1Database.create(binding.endpoint, configure)

internal expect fun createD1Endpoint(native: Any): D1Endpoint
