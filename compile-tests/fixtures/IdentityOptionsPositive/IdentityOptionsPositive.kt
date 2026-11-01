package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.primaryKey

private object IdentityOptions : Table("identity_options") {
    val defaultIdentity = long("default_identity").identity().primaryKey()
    val startOnly = long("start_only").identity(startWith = 1000L)
    val incrementOnly = long("increment_only").identity(incrementBy = 10L)
    val startAndIncrement = long("start_and_increment").identity(startWith = 1000L, incrementBy = 10L)
}
