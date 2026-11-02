package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.createIndex

private object IndexUsers : Table("index_users") {
    val name = string("name")
}

val invalidPredicateIndex = createIndex("index_users_name_idx", IndexUsers) {
    on(IndexUsers.name)
    where(IndexUsers.name)
}
