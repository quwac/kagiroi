package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select

private object SubqueryUsers : Table("subquery_users") {
    val id = long("id")
    val name = string("name")
}

private object SubqueryNames : Table("subquery_names") {
    val value = string("value")
}

val wrongInType = SubqueryUsers.id.inSubquery(select(SubqueryNames.value).from(SubqueryNames))
val wrongInProjection = SubqueryUsers.id.inSubquery(
    select(SubqueryUsers.id, SubqueryUsers.name).from(SubqueryUsers),
)
val wrongScalar = select(SubqueryUsers.id, SubqueryUsers.name).from(SubqueryUsers).scalar()

val derived = select(SubqueryUsers.id).from(SubqueryUsers).asTable("users")
val wrongDerivedType: SqlExpression<String> = derived[SubqueryUsers.id]

val cte = select(SubqueryUsers.id).from(SubqueryUsers).asCte("users_cte")
val wrongCteType: SqlExpression<String> = cte[SubqueryUsers.id]
