package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.sql.QueryExpression
import dev.quwac.kagiroi.sql.SingleProjection
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.exists
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import dev.quwac.kagiroi.sql.with
import dev.quwac.kagiroi.sql.where

private object CurrentUsers : Table("current_users") {
    val id = long("id")
    val name = string("name")
}

private object ArchivedUsers : Table("archived_users") {
    val id = long("id")
    val name = string("name")
}

fun validSetOperations(): String {
    val userId = CurrentUsers.id.alias("user_id")
    val current = select(userId, CurrentUsers.name).from(CurrentUsers)
    val archived = select(ArchivedUsers.id, ArchivedUsers.name).from(ArchivedUsers)
    val currentIds = select(CurrentUsers.id).from(CurrentUsers)
    val archivedIds = select(ArchivedUsers.id).from(ArchivedUsers)
    val combined = current.unionAll(archived).orderBy(userId.asc()).limit(100)
    val common = current.intersect(archived)
    val commonWithDuplicates = current.intersectAll(archived)
    val remainingWithDuplicates = current.exceptAll(archived)
    val mixed = current.unionAll(archived).intersectAll(current).exceptAll(archived)
    val combinedUsers = combined.asTable("combined_users")
    val combinedId: SqlExpression<Long> = combinedUsers[userId]
    val cte = combined.asCte("combined_users")
    val cteId: SqlExpression<Long> = cte[userId]
    val commonIds = currentIds.intersectAll(archivedIds)
    val scalar: SqlExpression<Long?> = commonIds.scalar()
    val membership = CurrentUsers.id.inSubquery(commonIds)
    val n = literal(1).alias("n")
    var numbers: QueryExpression<Int, SingleProjection> = select(n)
    numbers = numbers.unionAll(select(literal(2)))
    val numbersTable = numbers.asTable("numbers")
    val numbersN: SqlExpression<Int> = numbersTable[n]
    val filteredNumbers = select(numbersN).from(numbersTable).where(numbersN eq 2)
    val outer = with(cte) { select(cteId).from(cte).where(exists(commonWithDuplicates)) }
    return combined.toString() + combinedId + scalar + membership + filteredNumbers + outer +
        remainingWithDuplicates + mixed
}
