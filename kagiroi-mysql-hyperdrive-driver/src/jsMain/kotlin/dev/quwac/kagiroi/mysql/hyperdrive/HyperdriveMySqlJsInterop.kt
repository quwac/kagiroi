package dev.quwac.kagiroi.mysql.hyperdrive

@Suppress("UnusedParameter")
internal fun connectionConfig(binding: CloudflareHyperdriveBinding): dynamic = js(
    "({ host: binding.host, user: binding.user, password: binding.password, database: binding.database, " +
        "port: binding.port, disableEval: true, rowsAsArray: true, supportBigNumbers: true, " +
        "bigNumberStrings: true, jsonStrings: true, dateStrings: true, timezone: 'Z', " +
        "typeCast: (field, next) => field.type === 'GEOMETRY' ? field.buffer() : next() })",
)

internal fun connectionStringConfig(connectionString: String): dynamic =
    connectionConfig(connectionBinding(connectionString))

@Suppress("UnusedParameter")
private fun connectionBinding(connectionString: String): CloudflareHyperdriveBinding = js(
    """(() => {
        const url = new URL(connectionString);
        if (url.protocol !== 'mysql:') throw new Error('Expected a Hyperdrive MySQL connection string');
        return {
            host: url.hostname.replace(/^\[|\]$/g, ''),
            user: decodeURIComponent(url.username),
            password: decodeURIComponent(url.password),
            database: decodeURIComponent(url.pathname.slice(1)),
            port: Number(url.port || 3306)
        };
    })()""",
)
