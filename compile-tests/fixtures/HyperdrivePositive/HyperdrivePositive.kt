import dev.quwac.kagiroi.postgres.hyperdrive.HyperdrivePostgresDatabase

fun database(connectionString: String) = HyperdrivePostgresDatabase(connectionString)
