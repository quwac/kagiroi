package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.createTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConstraintDdlTest {
    @Test
    fun rendersNamedCompositePrimaryUniqueForeignCheckAndOracleDeleteActions() {
        assertEquals(
            "CREATE TABLE \"constraint_child\" (\"organization_id\" NUMBER(19,0) NOT NULL, " +
                "\"user_id\" NUMBER(19,0) NOT NULL, \"optional_parent_id\" NUMBER(19,0), " +
                "\"quantity\" NUMBER(10,0) NOT NULL, " +
                "CONSTRAINT \"pk_constraint_child\" PRIMARY KEY (\"organization_id\", \"user_id\"), " +
                "CONSTRAINT \"uq_constraint_child\" UNIQUE (\"organization_id\", \"user_id\"), " +
                "CONSTRAINT \"fk_constraint_parent\" FOREIGN KEY (\"organization_id\", \"user_id\") " +
                "REFERENCES \"constraint_parent\" (\"organization_id\", \"user_id\") ON DELETE CASCADE, " +
                "CONSTRAINT \"fk_constraint_optional_parent\" FOREIGN KEY (\"optional_parent_id\") " +
                "REFERENCES \"constraint_parent\" (\"organization_id\") ON DELETE SET NULL, " +
                "CONSTRAINT \"quantity_positive\" CHECK (\"quantity\" > 0))",
            OracleDialect.render(createTable(OracleConstraintChild)).sql,
        )
    }

    @Test
    fun rejectsOnUpdateBecauseOracleDoesNotProvideIt() {
        assertFailsWith<UnsupportedDdlOperation> {
            OracleDialect.render(createTable(OracleOnUpdateChild))
        }
    }
}

private object OracleConstraintParent : Table("constraint_parent") {
    val organizationId = long("organization_id")
    val userId = long("user_id")

    init {
        primaryKey(organizationId, userId, name = "pk_constraint_parent")
    }
}

private object OracleConstraintChild : Table("constraint_child") {
    val organizationId = long("organization_id")
    val userId = long("user_id")
    val optionalParentId = long("optional_parent_id").nullable()
    val quantity = int("quantity")

    init {
        primaryKey(organizationId, userId, name = "pk_constraint_child")
        unique(organizationId, userId, name = "uq_constraint_child")
        foreignKey(organizationId, userId, name = "fk_constraint_parent")
            .references(OracleConstraintParent.organizationId, OracleConstraintParent.userId)
            .onDelete(ForeignKeyAction.Cascade)
        foreignKey(optionalParentId, name = "fk_constraint_optional_parent")
            .references(OracleConstraintParent.organizationId)
            .onDelete(ForeignKeyAction.SetNull)
        checkConstraint("quantity_positive") { quantity gt 0 }
    }
}

private object OracleOnUpdateChild : Table("constraint_on_update_child") {
    val parentId = long("parent_id")

    init {
        foreignKey(parentId).references(OracleConstraintParent.organizationId).onUpdate(ForeignKeyAction.Cascade)
    }
}
