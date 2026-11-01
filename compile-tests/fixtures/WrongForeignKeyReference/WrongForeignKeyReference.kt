package fixtures

import dev.quwac.kagiroi.schema.Table

private object Parents : Table("parents") {
    val id = string("id")
}

private object Children : Table("children") {
    val parentId = long("parent_id")

    init {
        foreignKey(parentId).references(Parents.id)
    }
}
