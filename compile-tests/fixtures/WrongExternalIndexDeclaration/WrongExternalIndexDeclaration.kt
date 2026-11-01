package fixtures

import dev.quwac.kagiroi.schema.Table

private object IndexUsers : Table("index_users") {
    val name = string("name")
}

val invalidExternalIndex = IndexUsers.name.index("index_users_name_idx")
