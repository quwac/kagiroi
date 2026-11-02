package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecursiveCommonTableExpressionTest {
    @Test
    fun rendersAUnionAllRecursiveCteWithAnchorProjectionIdentityAndParameterOrder() {
        val n = literal(1).alias("n")
        val numbers = recursiveCte(name = "numbers", anchor = select(n)) { self ->
            val current: SqlExpression<Int> = self[n]
            select(current + 1).from(self).where(current lt 10)
        }
        val number = numbers[n]
        val typedNumber: SqlExpression<Int> = number
        val query = with(numbers) {
            select(number).from(numbers).orderBy(number.asc())
        }

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertSame(n.type, typedNumber.type)
        assertSame(n.storageType, typedNumber.storageType)
        assertTrue(!typedNumber.isNullable)
        assertEquals(
            "WITH RECURSIVE \"numbers\"(\"n\") AS (SELECT ? AS \"n\" UNION ALL " +
                "SELECT (\"numbers\".\"n\" + ?) FROM \"numbers\" WHERE \"numbers\".\"n\" < ?) " +
                "SELECT \"numbers\".\"n\" FROM \"numbers\" ORDER BY \"numbers\".\"n\" ASC",
            rendered.sql,
        )
        assertEquals(listOf<Any>(1, 1, 10), rendered.parameters.map { it.value })
        assertEquals(7, sqlRow(listOf(number), listOf<Any?>(7))[number])
    }

    @Test
    fun supportsAJoinToSelfAndKeepsAnchorOutputHandlesForMultipleColumns() {
        val depth = literal(0).alias("depth")
        val tree = recursiveCte(
            name = "tree",
            anchor = select(RecursiveNodes.id, RecursiveNodes.parentId, depth)
                .from(RecursiveNodes)
                .where(RecursiveNodes.id eq 1),
        ) { self ->
            val parentId: SqlExpression<Int> = self[RecursiveNodes.id]
            val currentDepth: SqlExpression<Int> = self[depth]
            select(RecursiveNodes.id, RecursiveNodes.parentId, currentDepth + 1)
                .from(RecursiveNodes)
                .innerJoin(self, on = RecursiveNodes.parentId eq parentId)
                .where(currentDepth lt 3)
        }
        val treeId: SqlExpression<Int> = tree[RecursiveNodes.id]
        val treeParentId: SqlExpression<Int> = tree[RecursiveNodes.parentId]
        val treeDepth: SqlExpression<Int> = tree[depth]

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            with(tree) {
                select(treeId, treeParentId, treeDepth).from(tree).orderBy(treeDepth.asc(), treeId.asc())
            },
        )

        assertTrue(
            rendered.sql.contains(
                "INNER JOIN \"tree\" ON \"recursive_nodes\".\"parent_id\" = \"tree\".\"id\"",
            ),
        )
        assertEquals(listOf<Any>(0, 1, 1, 3), rendered.parameters.map { it.value })
    }

    @Test
    fun rejectsMissingOrLeakedRecursiveSelfRelations() {
        val n = literal(1).alias("n")
        val noSelf = recursiveCte(name = "numbers", anchor = select(n)) { select(n) }
        assertFailsWith<IllegalArgumentException> {
            with(noSelf) { select(noSelf[n]).from(noSelf) }
        }

        lateinit var leaked: RecursiveCteSelf
        val numbers = recursiveCte(name = "numbers", anchor = select(n)) { self ->
            leaked = self
            select(self[n] + 1).from(self)
        }
        with(numbers) { select(numbers[n]).from(numbers) }

        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(leaked[n]).from(leaked))
        }
    }

    @Test
    fun keepsRecursiveDefinitionsOrderedWithOrdinaryCtes() {
        val base = select(RecursiveNodes.id).from(RecursiveNodes).where(RecursiveNodes.id gt 0).asCte("base")
        val baseId = base[RecursiveNodes.id]
        val n = literal(1).alias("n")
        val numbers = recursiveCte(name = "numbers", anchor = select(n).from(base)) { self ->
            val current = self[n]
            select(current + 1).from(self).where(current lt 2)
        }
        val number = numbers[n]
        val later = select(number).from(numbers).asCte("later")
        val laterNumber = later[number]

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            with(base, numbers, later) { select(laterNumber).from(later) },
        )

        assertTrue(rendered.sql.startsWith("WITH RECURSIVE \"base\" AS"))
        assertTrue(rendered.sql.contains(", \"numbers\"(\"n\") AS"))
        assertTrue(rendered.sql.contains(", \"later\" AS"))
        assertEquals(listOf<Any>(0, 1, 1, 2), rendered.parameters.map { it.value })
    }
}

private object RecursiveNodes : Table("recursive_nodes") {
    val id = int("id")
    val parentId = int("parent_id")
}
