package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.identity

private object InvalidIdentity : Table("invalid_identity") {
    val id = string("id").identity()
}
