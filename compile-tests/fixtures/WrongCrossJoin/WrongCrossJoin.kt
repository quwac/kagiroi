package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.select

private object CrossUsers : Table("cross_users") {
    val id = long("id")
}

private object CrossRoles : Table("cross_roles") {
    val id = long("id")
}

val invalidCrossJoinCondition = select(CrossUsers.id)
    .from(CrossUsers)
    .crossJoin(CrossRoles, on = CrossUsers.id eq CrossRoles.id)
