package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.sum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class SetOperationQueryTest {
    @Test
    fun unionsCompatibleSchemasAndPreservesTheLeftProjectionIdentity() {
        val userId = SetUsers.id.alias("user_id")
        val left = select(userId, SetUsers.name)
            .from(SetUsers)
            .where(SetUsers.id gt 10L)
        val right = select(SetInvitations.userId, SetInvitations.name)
            .from(SetInvitations)
            .where(SetInvitations.accepted eq true)

        val query: QueryExpression<Long, MultipleProjections> = left.union(right)
        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertIs<SetOperationQuery<Long, MultipleProjections>>(query)
        assertSame(userId, query.columns.first())
        assertEquals(
            "SELECT \"set_users\".\"id\" AS \"user_id\", \"set_users\".\"name\" FROM \"set_users\" " +
                "WHERE \"set_users\".\"id\" > ? UNION SELECT \"set_invitations\".\"user_id\", " +
                "\"set_invitations\".\"name\" FROM \"set_invitations\" WHERE \"set_invitations\".\"accepted\" = ?",
            rendered.sql,
        )
        assertEquals(listOf<Any>(10L, true), rendered.parameters.map { it.value })

        val allIntersection = left.intersectAll(right)
        assertSame(userId, allIntersection.columns.first())
    }

    @Test
    fun rendersDistinctAndAllOperationsWithOuterOrderingAndPagination() {
        val userId = SetUsers.id.alias("user_id")
        val left = select(userId, SetUsers.name).from(SetUsers).where(SetUsers.active eq true)
        val right = select(SetInvitations.userId, SetInvitations.name).from(SetInvitations)

        val union = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(left.union(right))
        val all = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            left.unionAll(right).orderBy(userId.desc()).limit(100).offset(20),
        )

        assertEquals(true, union.sql.contains(" UNION "))
        assertEquals(
            "SELECT \"set_users\".\"id\" AS \"user_id\", \"set_users\".\"name\" FROM \"set_users\" " +
                "WHERE \"set_users\".\"active\" = ? UNION ALL SELECT \"set_invitations\".\"user_id\", " +
                "\"set_invitations\".\"name\" FROM \"set_invitations\" ORDER BY \"user_id\" DESC LIMIT ? OFFSET ?",
            all.sql,
        )
        assertEquals(listOf<Any>(true, 100, 20), all.parameters.map { it.value })
    }

    @Test
    fun rendersDistinctAndAllIntersectionsDifferencesAndExplicitBinaryGrouping() {
        val first = select(SetUsers.id).from(SetUsers)
        val second = select(SetInvitations.userId).from(SetInvitations)
        val third = select(SetArchivedUsers.id).from(SetArchivedUsers)

        assertEquals(
            "SELECT \"set_users\".\"id\" FROM \"set_users\" INTERSECT SELECT \"set_invitations\".\"user_id\" " +
                "FROM \"set_invitations\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(first.intersect(second)).sql,
        )
        assertEquals(
            "SELECT \"set_users\".\"id\" FROM \"set_users\" EXCEPT SELECT \"set_invitations\".\"user_id\" " +
                "FROM \"set_invitations\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(first.except(second)).sql,
        )
        assertEquals(
            "SELECT * FROM (SELECT \"set_users\".\"id\" FROM \"set_users\" UNION " +
                "SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\") " +
                "AS \"kagiroi_set_operand\" INTERSECT ALL " +
                "SELECT \"set_archived_users\".\"id\" FROM \"set_archived_users\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(first.union(second).intersectAll(third)).sql,
        )
        assertEquals(
            "SELECT \"set_users\".\"id\" FROM \"set_users\" UNION SELECT * FROM " +
                "(SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\" INTERSECT ALL " +
                "SELECT \"set_archived_users\".\"id\" FROM \"set_archived_users\") " +
                "AS \"kagiroi_set_operand\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(first.union(second.intersectAll(third))).sql,
        )
        assertEquals(
            "SELECT * FROM (SELECT * FROM (SELECT \"set_users\".\"id\" FROM \"set_users\" UNION ALL " +
                "SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\") " +
                "AS \"kagiroi_set_operand\" INTERSECT SELECT \"set_archived_users\".\"id\" " +
                "FROM \"set_archived_users\") AS \"kagiroi_set_operand\" EXCEPT ALL " +
                "SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                first.unionAll(second).intersect(third).exceptAll(second),
            ).sql,
        )
    }

    @Test
    fun rejectsIncompatibleMultipleProjectionSchemasAtCompositionTime() {
        val countMismatchLeft = select(SetUsers.id, SetUsers.name).from(SetUsers)
        val countMismatchRight = select(SetInvitations.userId, SetInvitations.name, SetInvitations.accepted)
            .from(SetInvitations)
        val typeMismatchRight = select(SetInvitations.userId, SetInvitations.score).from(SetInvitations)
        val nullableMismatchRight = select(SetInvitations.userId, SetInvitations.optionalName).from(SetInvitations)

        assertFailsWith<IllegalArgumentException> { countMismatchLeft.intersectAll(countMismatchRight) }
        assertFailsWith<IllegalArgumentException> { countMismatchLeft.exceptAll(typeMismatchRight) }
        assertFailsWith<IllegalArgumentException> { countMismatchLeft.intersectAll(nullableMismatchRight) }

        val stringStorage = select(StorageMappedTestExpression(StringColumnType))
        val intStorage = select(StorageMappedTestExpression(IntColumnType))
        assertFailsWith<IllegalArgumentException> { stringStorage.exceptAll(intStorage) }
    }

    @Test
    fun reusesProjectionRelationsAndScalarMembershipAndExistsSubqueries() {
        val userId = SetUsers.id.alias("user_id")
        val combined = select(userId, SetUsers.name).from(SetUsers)
            .unionAll(select(SetInvitations.userId, SetInvitations.name).from(SetInvitations))
        val derived = combined.asTable("combined_users")
        val derivedId: SqlExpression<Long> = derived[userId]
        val cte = combined.asCte("combined_users")
        val cteId: SqlExpression<Long> = cte[userId]
        val scalar = select(userId).from(SetUsers)
            .union(select(SetInvitations.userId).from(SetInvitations))
            .scalar()
        val membership = SetArchivedUsers.id.inSubquery(
            select(SetUsers.id).from(SetUsers).union(select(SetInvitations.userId).from(SetInvitations)),
        )
        val outer = with(cte) { select(cteId).from(cte).where(exists(combined)) }

        assertEquals(
            "SELECT \"combined_users\".\"user_id\" FROM (SELECT \"set_users\".\"id\" AS \"user_id\", " +
                "\"set_users\".\"name\" FROM \"set_users\" UNION ALL SELECT \"set_invitations\".\"user_id\", " +
                "\"set_invitations\".\"name\" FROM \"set_invitations\") AS \"combined_users\" " +
                "WHERE \"combined_users\".\"user_id\" > ?",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(derivedId).from(derived).where(derivedId gt 10L),
            ).sql,
        )
        assertEquals(
            "SELECT \"set_archived_users\".\"id\" FROM \"set_archived_users\" WHERE \"set_archived_users\".\"id\" " +
                "IN (SELECT \"set_users\".\"id\" FROM \"set_users\" UNION SELECT \"set_invitations\".\"user_id\" " +
                "FROM \"set_invitations\")",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(SetArchivedUsers.id).from(SetArchivedUsers).where(membership),
            ).sql,
        )
        assertEquals(true, scalar.isNullable)
        assertEquals(
            "WITH \"combined_users\" AS (SELECT \"set_users\".\"id\" AS \"user_id\", \"set_users\".\"name\" " +
                "FROM \"set_users\" UNION ALL SELECT \"set_invitations\".\"user_id\", " +
                "\"set_invitations\".\"name\" FROM \"set_invitations\") SELECT \"combined_users\".\"user_id\" " +
                "FROM \"combined_users\" WHERE EXISTS " +
                "(SELECT \"set_users\".\"id\" AS \"user_id\", \"set_users\".\"name\" FROM \"set_users\" UNION ALL " +
                "SELECT \"set_invitations\".\"user_id\", \"set_invitations\".\"name\" FROM \"set_invitations\")",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(outer).sql,
        )

        val row = sqlRow(combined.columns, listOf<Any?>(7L, "Ada"))
        assertEquals(7L, row[userId])
    }

    @Test
    fun reusesAllSetQueriesAsScalarInExistsAndInSubqueries() {
        val userId = SetUsers.id.alias("user_id")
        val common = select(userId).from(SetUsers)
            .intersectAll(select(SetInvitations.userId).from(SetInvitations))
        val scalar = common.scalar()
        val membership = SetArchivedUsers.id.inSubquery(common)
        val existence = exists(common)
        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(SetArchivedUsers.id).from(SetArchivedUsers).where(membership and existence),
        )

        assertEquals(true, scalar.isNullable)
        assertEquals(
            "SELECT \"set_archived_users\".\"id\" FROM \"set_archived_users\" WHERE " +
                "(\"set_archived_users\".\"id\" IN (SELECT \"set_users\".\"id\" AS \"user_id\" FROM \"set_users\" " +
                "INTERSECT ALL SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\") AND EXISTS " +
                "(SELECT \"set_users\".\"id\" AS \"user_id\" FROM \"set_users\" INTERSECT ALL " +
                "SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\"))",
            rendered.sql,
        )
    }

    @Test
    fun permitsGroupedOperandsAndBoundsOrderedOperandsThroughDerivedBoundaries() {
        val userTotal = sum(SetUsers.score).alias("total")
        val invitationTotal = sum(SetInvitations.score).alias("total")
        val grouped = select(SetUsers.name, userTotal)
            .from(SetUsers)
            .groupBy(SetUsers.name)
            .union(select(SetInvitations.name, invitationTotal).from(SetInvitations).groupBy(SetInvitations.name))
        val bounded = select(SetUsers.id).from(SetUsers).orderBy(SetUsers.id.asc()).limit(2)
            .union(select(SetInvitations.userId).from(SetInvitations))

        assertEquals(true, SqlRenderer(DoubleQuotedQuestionMarkDialect).render(grouped).sql.contains("GROUP BY"))
        assertEquals(
            "SELECT * FROM (SELECT \"set_users\".\"id\" FROM \"set_users\" ORDER BY " +
                "\"set_users\".\"id\" ASC LIMIT ?) AS \"kagiroi_set_operand\" UNION " +
                "SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(bounded).sql,
        )
    }

    @Test
    fun retainsParameterOrderAcrossAllOperandsAndOuterSetModifiers() {
        val left = select(SetUsers.id).from(SetUsers).where(SetUsers.id gt 10L)
        val right = select(SetInvitations.userId).from(SetInvitations).where(SetInvitations.userId gt 20L)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            left.intersectAll(right).orderBy(SetUsers.id.asc()).limit(3).offset(1),
        )

        assertEquals(
            "SELECT \"set_users\".\"id\" FROM \"set_users\" WHERE \"set_users\".\"id\" > ? " +
                "INTERSECT ALL SELECT \"set_invitations\".\"user_id\" FROM \"set_invitations\" " +
                "WHERE \"set_invitations\".\"user_id\" > ? ORDER BY \"id\" ASC LIMIT ? OFFSET ?",
            rendered.sql,
        )
        assertEquals(listOf<Any>(10L, 20L, 3, 1), rendered.parameters.map { it.value })
    }
}

private object SetUsers : Table("set_users") {
    val id = long("id")
    val name = string("name")
    val active = boolean("active")
    val score = long("score")
}

private object SetInvitations : Table("set_invitations") {
    val userId = long("user_id")
    val name = string("name")
    val optionalName = string("optional_name").nullable()
    val accepted = boolean("accepted")
    val score = int("score")
}

private object SetArchivedUsers : Table("set_archived_users") {
    val id = long("id")
}

private class StorageMappedTestExpression(override val storageType: ColumnType<*>) : SqlExpression<String> {
    override val referencedColumns: List<dev.quwac.kagiroi.schema.Column<*>> = emptyList()
    override val type: ColumnType<*> = StringColumnType
    override val isNullable: Boolean = false

    override fun toStorageValue(value: String): Any = value

    override fun fromStorageValue(value: Any?): String = requireNotNull(value).toString()
}
