package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.stringType
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.gt

private object AlterOrganizations : Table("alter_organizations") {
    val id = int("id").primaryKey()
}

private object AlterUsers : Table("alter_users") {
    val id = int("id")
    val organizationId = int("organization_id")
    val email = string("email").nullable()
    val status = string("status").nullable()
    val quantity = int("quantity")
}

fun validTypedAlterTable(): Int = alterTable(AlterUsers) {
    addPrimaryKey(AlterUsers.id, name = "pk_alter_users")
    addUnique(AlterUsers.organizationId, AlterUsers.email, name = "uq_alter_users_organization_email")
    addForeignKey(AlterUsers.organizationId, name = "fk_alter_users_organization").references(AlterOrganizations.id)
    addCheck("ck_alter_users_quantity") { AlterUsers.quantity gt 0 }
    alterColumnType(AlterUsers.status, stringType())
    setNotNull(AlterUsers.email)
    setDefault(AlterUsers.status, "active")
    dropDefault(AlterUsers.status)
    dropNotNull(AlterUsers.email)
    dropPrimaryKey("pk_alter_users")
    dropUnique("uq_alter_users_organization_email")
    dropForeignKey("fk_alter_users_organization")
    dropCheck("ck_alter_users_quantity")
}.operations.size
