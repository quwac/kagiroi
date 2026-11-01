package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.createTable
import kotlin.test.Test
import kotlin.test.assertEquals

class ConstraintDdlTest {
    @Test
    fun rendersNamedCompositePrimaryUniqueForeignCheckAndReferentialActions() {
        assertEquals(
            "CREATE TABLE `constraint_child` (`organization_id` BIGINT NOT NULL, `user_id` BIGINT NOT NULL, " +
                "`optional_parent_id` BIGINT, `quantity` INT NOT NULL, " +
                "CONSTRAINT `pk_constraint_child` PRIMARY KEY (`organization_id`, `user_id`), " +
                "CONSTRAINT `uq_constraint_child` UNIQUE (`organization_id`, `user_id`), " +
                "CONSTRAINT `fk_constraint_parent` FOREIGN KEY (`organization_id`, `user_id`) " +
                "REFERENCES `constraint_parent` (`organization_id`, `user_id`) " +
                "ON DELETE CASCADE ON UPDATE NO ACTION, " +
                "CONSTRAINT `fk_constraint_optional_parent` FOREIGN KEY (`optional_parent_id`) " +
                "REFERENCES `constraint_parent` (`organization_id`) ON DELETE SET NULL, " +
                "CONSTRAINT `quantity_positive` CHECK (`quantity` > 0))",
            MySqlDialect.render(createTable(MySqlConstraintChild)).sql,
        )
    }
}

private object MySqlConstraintParent : Table("constraint_parent") {
    val organizationId = long("organization_id")
    val userId = long("user_id")

    init {
        primaryKey(organizationId, userId, name = "pk_constraint_parent")
    }
}

private object MySqlConstraintChild : Table("constraint_child") {
    val organizationId = long("organization_id")
    val userId = long("user_id")
    val optionalParentId = long("optional_parent_id").nullable()
    val quantity = int("quantity")

    init {
        primaryKey(organizationId, userId, name = "pk_constraint_child")
        unique(organizationId, userId, name = "uq_constraint_child")
        foreignKey(organizationId, userId, name = "fk_constraint_parent")
            .references(MySqlConstraintParent.organizationId, MySqlConstraintParent.userId)
            .onDelete(ForeignKeyAction.Cascade)
            .onUpdate(ForeignKeyAction.NoAction)
        foreignKey(optionalParentId, name = "fk_constraint_optional_parent")
            .references(MySqlConstraintParent.organizationId)
            .onDelete(ForeignKeyAction.SetNull)
        checkConstraint("quantity_positive") { quantity gt 0 }
    }
}
