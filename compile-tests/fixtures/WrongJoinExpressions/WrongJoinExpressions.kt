package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.select

private object JoinUsers : Table("join_users") {
    val id = long("id")
    val name = string("name")
}

private object JoinOrganizations : Table("join_organizations") {
    val id = long("id")
    val name = string("name")
}

val invalidNullableOuterExpression: SqlExpression<String> = lower(JoinOrganizations.name.optional)
val invalidJoinCondition = select(JoinUsers.id)
    .from(JoinUsers)
    .innerJoin(JoinOrganizations, on = JoinUsers.id)
val invalidJoinValueType = JoinUsers.name eq JoinOrganizations.id
val invalidUsingExpression = select(JoinUsers.id)
    .from(JoinUsers)
    .innerJoinUsing(JoinOrganizations) {
        using(lower(JoinUsers.name), lower(JoinOrganizations.name))
    }
val invalidUsingValueType = select(JoinUsers.id)
    .from(JoinUsers)
    .innerJoinUsing(JoinOrganizations) {
        using(JoinUsers.id, JoinOrganizations.name)
    }
