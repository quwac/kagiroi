package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with

private object Nodes : Table("nodes") {
    val id = int("id")
    val parentId = int("parent_id")
}

fun typedRecursiveCtes(): String {
    val n = literal(1).alias("n")
    val numbers = recursiveCte(name = "numbers", anchor = select(n)) { self ->
        val current: SqlExpression<Int> = self[n]
        select(current + 1).from(self).where(current lt 10)
    }
    val number: SqlExpression<Int> = numbers[n]
    val numbersQuery = with(numbers) { select(numbers[n]).from(numbers).where(numbers[n] gt 0) }

    val depth = literal(0).alias("depth")
    val tree = recursiveCte(
        name = "tree",
        anchor = select(Nodes.id, Nodes.parentId, depth).from(Nodes).where(Nodes.id eq 1),
    ) { self ->
        select(Nodes.id, Nodes.parentId, self[depth] + 1)
            .from(Nodes)
            .innerJoin(self, on = Nodes.parentId eq self[Nodes.id])
    }
    val treeDepth: SqlExpression<Int> = tree[depth]
    val treeQuery = with(tree) { select(tree[Nodes.id], treeDepth).from(tree) }

    return number.toString() + numbersQuery + treeQuery
}
