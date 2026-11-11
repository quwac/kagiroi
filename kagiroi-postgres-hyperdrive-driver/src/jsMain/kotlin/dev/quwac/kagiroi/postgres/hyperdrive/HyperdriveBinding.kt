package dev.quwac.kagiroi.postgres.hyperdrive

/** The Cloudflare Workers Hyperdrive binding passed through an environment object. */
external interface CloudflareHyperdriveBinding {
    /** Value associated with connection string. */
    val connectionString: String
}

/** Provides the hyperdrive postgres database result. */
fun HyperdrivePostgresDatabase(
    binding: CloudflareHyperdriveBinding,
    configure: HyperdrivePostgresDatabaseConfiguration.() -> Unit = {},
): HyperdrivePostgresDatabase = HyperdrivePostgresDatabase(binding.connectionString, configure)
