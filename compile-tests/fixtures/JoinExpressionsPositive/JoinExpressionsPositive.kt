package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.coalesce
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.leftJoinUsing
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.rightJoin
import dev.quwac.kagiroi.sql.select

private object JoinUsers : Table("join_users") {
    val id = long("id")
    val organizationId = long("organization_id").nullable()
    val managerId = long("manager_id").nullable()
    val name = string("name")
}

private object JoinOrganizations : Table("join_organizations") {
    val id = long("id")
    val organizationId = long("organization_id")
    val name = string("name")
}

fun validJoinExpressions(): String {
    val userId: SqlExpression<Long> = JoinUsers[JoinUsers.id]
    val organizationName = JoinOrganizations.name.optional
    val normalizedOrganizationName: SqlExpression<String?> = lower(organizationName)
    val displayName: SqlExpression<String> = coalesce(organizationName, JoinUsers[JoinUsers.name])
    val query = select(userId, organizationName, normalizedOrganizationName, displayName)
        .from(JoinUsers)
        .leftJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)
        .orderBy(organizationName.asc())
    val readOrganizationName: (SqlRow) -> String? = { row -> row[JoinOrganizations.name.optional] }

    val employee = JoinUsers.alias("employee")
    val manager = JoinUsers.alias("manager")
    val managerNameForInnerJoin: SqlExpression<String> = manager[JoinUsers.name]
    val managerName = manager[JoinUsers.name].optional
    val selfJoin = select(employee[JoinUsers.name], managerName)
        .from(employee)
        .leftJoin(manager, on = employee[JoinUsers.managerId] eq manager[JoinUsers.id])
    val readManagerName: (SqlRow) -> String? = { row -> row[managerName] }

    val rightJoin = select(JoinUsers.name.optional, JoinOrganizations[JoinOrganizations.name])
        .from(JoinUsers)
        .rightJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)
    val fullJoin = select(
        JoinUsers.name.optional,
        JoinOrganizations.name.optional,
    ).from(JoinUsers)
        .fullOuterJoin(JoinOrganizations, on = JoinUsers.organizationId eq JoinOrganizations.id)

    val crossJoinedOrganizationName: SqlExpression<String> = JoinOrganizations.name
    val crossJoin = select(JoinUsers.id, crossJoinedOrganizationName)
        .from(JoinUsers)
        .crossJoin(JoinOrganizations)
    val usingJoin = select(JoinUsers.id, JoinOrganizations.name)
        .from(JoinUsers)
        .innerJoinUsing(JoinOrganizations) {
            using(JoinUsers.organizationId, JoinOrganizations.organizationId)
        }
    val usingLeftJoin = select(JoinUsers.id, JoinOrganizations.name.optional)
        .from(JoinUsers)
        .leftJoinUsing(JoinOrganizations) {
            using(JoinUsers.organizationId, JoinOrganizations.organizationId)
        }

    return query.toString() + selfJoin + rightJoin + fullJoin + crossJoin + readOrganizationName + readManagerName +
        managerNameForInnerJoin + crossJoinedOrganizationName + usingJoin + usingLeftJoin
}
