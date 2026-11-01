package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.transform
import dev.quwac.kagiroi.schema.unique
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SelectQueryTest {
    @Test
    fun buildsSelectAst() {
        val userId = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        val predicate = Users.id eq userId
        val query = select(Users.id, Users.name)
            .from(Users)
            .where(predicate)
            .orderBy(Users.name.asc())
            .limit(20)
            .offset(40)

        assertEquals(listOf(Users.id, Users.name), query.columns)
        assertSame(Users, query.from)
        assertSame(predicate, query.predicate)
        assertEquals(SortDirection.ASCENDING, query.order.single().direction)
        assertEquals(20, query.limit)
        assertEquals(40, query.offset)
    }

    @Test
    fun rendersComparisonsLogicalOperatorsAndNullChecks() {
        val query = select(Users.id)
            .from(Users)
            .where(
                (Users.age gte 18) and
                    ((Users.name neq "blocked") or Users.age.isNull()),
            )
            .orderBy(Users.name.desc(), Users.id.asc())

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"users\".\"id\" FROM \"users\" " +
                "WHERE (\"users\".\"age\" >= ? AND (\"users\".\"name\" != ? OR \"users\".\"age\" IS NULL)) " +
                "ORDER BY \"users\".\"name\" DESC, \"users\".\"id\" ASC",
            rendered.sql,
        )
        assertEquals(listOf(18, "blocked"), rendered.parameters.map { it.value })
        assertSame(Users.age, rendered.parameters[0].column)
        assertSame(Users.name, rendered.parameters[1].column)
    }

    @Test
    fun appendsRepeatedOrderByCalls() {
        val query = select(Users.id)
            .from(Users)
            .orderBy(Users.name.asc())
            .orderBy(Users.id.desc())

        assertEquals(listOf(Users.name, Users.id), query.order.map { it.column })
        assertEquals(listOf(SortDirection.ASCENDING, SortDirection.DESCENDING), query.order.map { it.direction })
    }

    @Test
    fun retainsExplicitNullOrderingAndRejectsItWithoutDialectSupport() {
        val query = select(Users.id)
            .from(Users)
            .orderBy(Users.name.asc().nullsLast())

        assertEquals(NullOrdering.LAST, query.order.single().nullOrdering)
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)
        }
    }

    @Test
    fun keepsAllValuesOutOfSqlText() {
        val unsafeValue = "Robert'); DROP TABLE users; --"
        val query = select(Users.id)
            .from(Users)
            .where((Users.name eq unsafeValue) and (Users.age lt 65))
            .limit(10)
            .offset(5)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"users\".\"id\" FROM \"users\" " +
                "WHERE (\"users\".\"name\" = ? AND \"users\".\"age\" < ?) LIMIT ? OFFSET ?",
            rendered.sql,
        )
        assertEquals(listOf(unsafeValue, 65, 10, 5), rendered.parameters.map { it.value })
        assertTrue(unsafeValue !in rendered.sql)
    }

    @Test
    fun rendersTheSameModelWithAnotherDialect() {
        val query = select(Users.name)
            .from(Users)
            .where(Users.name eq "Ada")
            .limit(1)
        val questionMarks = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)
        val numbered = SqlRenderer(NumberedDialect).render(query)

        assertEquals("SELECT \"users\".\"name\" FROM \"users\" WHERE \"users\".\"name\" = ? LIMIT ?", questionMarks.sql)
        assertEquals("SELECT `users`.`name` FROM `users` WHERE `users`.`name` = $1 LIMIT $2", numbered.sql)
        assertEquals(questionMarks.parameters.map { it.value }, numbered.parameters.map { it.value })
    }

    @Test
    fun supportsEveryComparisonOperator() {
        val predicates = listOf(
            Users.age eq 1,
            Users.age neq 2,
            Users.age lt 3,
            Users.age lte 4,
            Users.age gt 5,
            Users.age gte 6,
            Users.age.isNull(),
            Users.age.isNotNull(),
        )

        assertEquals(8, predicates.size)
    }

    @Test
    fun selectsComputedColumnsWithoutLosingTheirType() {
        val displayName: Column<String> = Users.displayName
        val query = select(displayName).from(Users)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertTrue(displayName.isComputed)
        assertEquals("SELECT \"users\".\"display_name\" FROM \"users\"", rendered.sql)
    }

    @Test
    fun rejectsInvalidBounds() {
        val query = select(Users.id).from(Users)
        assertFailsWith<IllegalArgumentException> { query.limit(-1) }
        assertFailsWith<IllegalArgumentException> { query.offset(-1) }
    }

    @Test
    fun bindsEnumComparisonsAsTheirDeclaredStorageValues() {
        val query = select(Accounts.status, Accounts.role)
            .from(Accounts)
            .where((Accounts.status eq AccountStatus.ACTIVE) and (Accounts.role eq AccountRole.ADMIN))

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"accounts\".\"status\", \"accounts\".\"role\" FROM \"accounts\" " +
                "WHERE (\"accounts\".\"status\" = ? AND \"accounts\".\"role\" = ?)",
            rendered.sql,
        )
        assertEquals(listOf(100, "admin"), rendered.parameters.map { it.value })
        assertSame(Accounts.status, rendered.parameters[0].column)
        assertSame(Accounts.role, rendered.parameters[1].column)
    }

    @Test
    fun bindsTransformedClassesAsTheirStorageValues() {
        val accountId = AccountId("account-123")
        val query = select(MappedAccounts.id)
            .from(MappedAccounts)
            .where(MappedAccounts.id eq accountId)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"mapped_accounts\".\"id\" FROM \"mapped_accounts\" WHERE \"mapped_accounts\".\"id\" = ?",
            rendered.sql,
        )
        assertEquals(listOf("account-123"), rendered.parameters.map { it.value })
        assertSame(MappedAccounts.id, rendered.parameters.single().column)
    }

    @Test
    fun rejectsSelectColumnsOutsideTheFromTable() {
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(Users.id, Accounts.status).from(Users))
        }
    }

    @Test
    fun rejectsReplacedColumnReferences() {
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(AliasedUsers.rawId).from(AliasedUsers))
        }
    }

    @Test
    fun rejectsPredicateColumnsOutsideTheFromTable() {
        val query = select(Users.id).from(Users)

        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query.where(Accounts.status eq AccountStatus.ACTIVE))
        }
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                query.where((Users.age gte 18) and (Accounts.role eq AccountRole.ADMIN)),
            )
        }
    }

    @Test
    fun rejectsOrderColumnsOutsideTheFromTable() {
        val query = select(Users.id).from(Users)

        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query.orderBy(Accounts.status.asc()))
        }
    }
}

private object Users : Table("users") {
    val id = uuid("id").primaryKey()
    val name = string("name")
    val age = int("age").nullable()
    val displayName = string("display_name").computed()
}

private object NumberedDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override fun parameterMarker(position: Int): String = "$$position"
}

private enum class AccountStatus {
    ACTIVE,
    SUSPENDED,
}

private enum class AccountRole {
    ADMIN,
    MEMBER,
}

private object Accounts : Table("accounts") {
    val status = int("status").asEnum(AccountStatus.entries) {
        AccountStatus.ACTIVE mapsTo 100
        AccountStatus.SUSPENDED mapsTo 200
    }
    val role = string("role").asEnum(AccountRole.entries) {
        AccountRole.ADMIN mapsTo "admin"
        AccountRole.MEMBER mapsTo "member"
    }
}

private data class AccountId(val value: String)

private object MappedAccounts : Table("mapped_accounts") {
    val id = string("id").transform(
        fromStorage = ::AccountId,
        toStorage = { it.value },
    )
}

private object AliasedUsers : Table("aliased_users") {
    val rawId = string("id")
    val id = rawId.unique()
}

@Suppress("unused")
private fun typedColumns(id: Column<Uuid>, name: Column<String>, age: Column<Int?>) = Unit
