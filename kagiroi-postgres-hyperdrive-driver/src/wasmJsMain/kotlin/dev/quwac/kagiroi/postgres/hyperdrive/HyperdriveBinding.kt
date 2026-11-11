@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.postgres.hyperdrive

import kotlin.js.JsAny

/** The Cloudflare Workers Hyperdrive binding passed through an environment object. */
external interface CloudflareHyperdriveBinding : JsAny {
    /** Value associated with connection string. */
    val connectionString: String
}

/** Provides the hyperdrive postgres database result. */
fun HyperdrivePostgresDatabase(
    binding: CloudflareHyperdriveBinding,
    configure: HyperdrivePostgresDatabaseConfiguration.() -> Unit = {},
): HyperdrivePostgresDatabase = HyperdrivePostgresDatabase(binding.connectionString, configure)
