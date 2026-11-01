package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.alterTable

private object WrongAlterRows : Table("wrong_alter_rows") {
    val id = int("id")
    val email = string("email")
}

private object WrongAlterParent : Table("wrong_alter_parent") {
    val id = long("id")
}

fun wrongTypedAlterTable() = alterTable(WrongAlterRows) {
    setDefault(WrongAlterRows.id, "three")
    addCheck { WrongAlterRows.email }
    addForeignKey(WrongAlterRows.id).references(WrongAlterParent.id)
}
