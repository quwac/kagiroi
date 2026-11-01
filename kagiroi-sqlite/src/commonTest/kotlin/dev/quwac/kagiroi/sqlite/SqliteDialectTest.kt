package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.concat
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.deleteAllFrom
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.fullOuterJoinUsing
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.gte
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.isNotNull
import dev.quwac.kagiroi.sql.isNull
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.leftJoinUsing
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.or
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.rightJoinUsing
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SqliteDialectTest {
    @Test
    fun rendersNativeUsingJoinsForEverySupportedOuterKind() {
        val employee = SqliteUsers.alias("employee")
        val manager = SqliteUsers.alias("manager")
        val inner = select(employee[SqliteUsers.id], manager[SqliteUsers.name])
            .from(employee)
            .innerJoinUsing(manager) { using(employee[SqliteUsers.id], manager[SqliteUsers.id]) }
        val left = select(employee[SqliteUsers.id], manager[SqliteUsers.name].optional)
            .from(employee)
            .leftJoinUsing(manager) {
                using(employee[SqliteUsers.id], manager[SqliteUsers.id])
                using(employee[SqliteUsers.name], manager[SqliteUsers.name])
            }
        val right = select(employee[SqliteUsers.id].optional, manager[SqliteUsers.name])
            .from(employee)
            .rightJoinUsing(manager) { using(employee[SqliteUsers.id], manager[SqliteUsers.id]) }
        val full = select(employee[SqliteUsers.id].optional, manager[SqliteUsers.name].optional)
            .from(employee)
            .fullOuterJoinUsing(manager) { using(employee[SqliteUsers.id], manager[SqliteUsers.id]) }

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"users\" AS \"employee\" " +
                "INNER JOIN \"users\" AS \"manager\" USING (\"id\")",
            renderer.render(inner).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"users\" AS \"employee\" " +
                "LEFT JOIN \"users\" AS \"manager\" USING (\"id\", \"name\")",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"users\" AS \"employee\" " +
                "RIGHT JOIN \"users\" AS \"manager\" USING (\"id\")",
            renderer.render(right).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"users\" AS \"employee\" " +
                "FULL OUTER JOIN \"users\" AS \"manager\" USING (\"id\")",
            renderer.render(full).sql,
        )
    }

    @Test
    fun rendersCrossJoinWithoutOn() {
        val employee = SqliteUsers.alias("employee")
        val coworker = SqliteUsers.alias("coworker")
        val query = select(employee[SqliteUsers.id], coworker[SqliteUsers.name])
            .from(employee)
            .crossJoin(coworker)

        assertEquals(
            "SELECT \"employee\".\"id\", \"coworker\".\"name\" FROM \"users\" AS \"employee\" " +
                "CROSS JOIN \"users\" AS \"coworker\"",
            renderer.render(query).sql,
        )
    }

    @Test
    fun rendersAliasedInnerLeftAndFullOuterJoins() {
        val employee = SqliteUsers.alias("employee")
        val manager = SqliteUsers.alias("manager")
        val reviewer = SqliteUsers.alias("reviewer")
        val query = select(
            employee[SqliteUsers.id],
            manager[SqliteUsers.name],
            reviewer[SqliteUsers.name].optional,
        ).from(employee)
            .innerJoin(manager, on = employee[SqliteUsers.id] eq manager[SqliteUsers.id])
            .leftJoin(reviewer, on = employee[SqliteUsers.id] eq reviewer[SqliteUsers.id])

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\", \"reviewer\".\"name\" " +
                "FROM \"users\" AS \"employee\" INNER JOIN \"users\" AS \"manager\" ON " +
                "\"employee\".\"id\" = \"manager\".\"id\" LEFT JOIN \"users\" AS \"reviewer\" ON " +
                "\"employee\".\"id\" = \"reviewer\".\"id\"",
            renderer.render(query).sql,
        )

        val full = select(employee[SqliteUsers.id].optional, manager[SqliteUsers.id].optional)
            .from(employee)
            .fullOuterJoin(manager, on = employee[SqliteUsers.id] eq manager[SqliteUsers.id])

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"id\" FROM \"users\" AS \"employee\" " +
                "FULL OUTER JOIN \"users\" AS \"manager\" ON \"employee\".\"id\" = \"manager\".\"id\"",
            renderer.render(full).sql,
        )
    }

    @Test
    fun rendersPortableFunctionNames() {
        val normalized = lower(SqliteUsers.name).alias("normalized_name")
        val characterLength = length(SqliteUsers.name).alias("name_length")
        val rowCount = count().alias("row_count")

        assertEquals(
            "SELECT LOWER(\"users\".\"name\") AS \"normalized_name\", " +
                "LENGTH(\"users\".\"name\") AS \"name_length\", COUNT(*) AS \"row_count\" FROM \"users\" " +
                "GROUP BY LOWER(\"users\".\"name\"), LENGTH(\"users\".\"name\")",
            renderer.render(
                select(normalized, characterLength, rowCount).from(SqliteUsers).groupBy(normalized, characterLength),
            ).sql,
        )
    }

    @Test
    fun rendersSqliteSpecificSubstringConcatAndCurrentTimestamp() {
        val rendered = renderer.render(
            select(
                substring(SqliteUsers.name, 1, 2).alias("prefix"),
                concat(SqliteUsers.name, SqliteUsers.name).alias("doubled"),
                currentTimestamp().alias("observed_at"),
            ).from(SqliteUsers),
        )

        assertEquals(
            "SELECT substr(\"users\".\"name\", ?, ?) AS \"prefix\", " +
                "(\"users\".\"name\" || \"users\".\"name\") AS \"doubled\", " +
                "strftime('%Y-%m-%dT%H:%M:%fZ', 'now') AS \"observed_at\" FROM \"users\"",
            rendered.sql,
        )
        assertEquals(listOf(1, 2), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersSqliteCurrentDateAndTimeInUtc() {
        assertEquals("date('now')", SqliteDialect.renderFunction(SqlFunction.CurrentDate, emptyList()))
        assertEquals("time('now')", SqliteDialect.renderFunction(SqlFunction.CurrentTime, emptyList()))
        assertEquals(
            "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')",
            SqliteDialect.renderFunction(SqlFunction.CurrentTimestamp, emptyList()),
        )
    }

    @Test
    fun quotesTableAndColumnIdentifiersIncludingKeywordsAndQuotes() {
        val query = select(QuotedIdentifiers.reserved, QuotedIdentifiers.quoted)
            .from(QuotedIdentifiers)

        val rendered = renderer.render(query)

        assertEquals(
            "SELECT \"order\"\"items\".\"select\", \"order\"\"items\".\"say\"\"hello\" " +
                "FROM \"order\"\"items\"",
            rendered.sql,
        )
        assertEquals(emptyList(), rendered.parameters)
    }

    @Test
    fun rendersSelectClausesAndKeepsValuesOutOfSql() {
        val unsafeName = "Alice\" OR 1 = 1 --"
        val query = select(SqliteUsers.id, SqliteUsers.name)
            .from(SqliteUsers)
            .where(
                (SqliteUsers.age gte 18) and
                    ((SqliteUsers.name eq unsafeName) or SqliteUsers.nickname.isNull()),
            )
            .orderBy(SqliteUsers.name.desc(), SqliteUsers.id.asc())
            .limit(10)
            .offset(5)

        val rendered = renderer.render(query)

        assertEquals(
            "SELECT \"users\".\"id\", \"users\".\"name\" FROM \"users\" " +
                "WHERE (\"users\".\"age\" >= ? AND " +
                "(\"users\".\"name\" = ? OR \"users\".\"nickname\" IS NULL)) " +
                "ORDER BY \"users\".\"name\" DESC, \"users\".\"id\" ASC LIMIT ? OFFSET ?",
            rendered.sql,
        )
        assertEquals(listOf(18, unsafeName, 10, 5), rendered.parameters.map { it.value })
        assertFalse(unsafeName in rendered.sql)
    }

    @Test
    fun rendersSingleAndMultipleRowInsertsInParameterOrder() {
        val singleRow = insertInto(SqliteUsers).values {
            set(SqliteUsers.id, 1L)
            set(SqliteUsers.name, "Alice")
            set(SqliteUsers.nickname, null)
            set(SqliteUsers.active, true)
        }
        val multipleRows = insertInto(SqliteUsers)
            .values {
                set(SqliteUsers.id, 2L)
                set(SqliteUsers.name, "Bob")
            }
            .values {
                set(SqliteUsers.name, "Carol")
                set(SqliteUsers.id, 3L)
            }

        val renderedSingleRow = renderer.render(singleRow)
        val renderedMultipleRows = renderer.render(multipleRows)

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"name\", \"nickname\", \"active\") VALUES (?, ?, ?, ?)",
            renderedSingleRow.sql,
        )
        assertEquals(listOf(1L, "Alice", null, true), renderedSingleRow.parameters.map { it.value })
        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?), (?, ?)",
            renderedMultipleRows.sql,
        )
        assertEquals(listOf(2L, "Bob", 3L, "Carol"), renderedMultipleRows.parameters.map { it.value })
    }

    @Test
    fun rendersUpdateParametersFromSetBeforeWhere() {
        val query = update(SqliteUsers) {
            set(SqliteUsers.name, "Alice")
            set(SqliteUsers.nickname, "ally")
        }.where((SqliteUsers.id eq 7L) and SqliteUsers.nickname.isNotNull())

        val rendered = renderer.render(query)

        assertEquals(
            "UPDATE \"users\" SET \"name\" = ?, \"nickname\" = ? " +
                "WHERE (\"users\".\"id\" = ? AND \"users\".\"nickname\" IS NOT NULL)",
            rendered.sql,
        )
        assertEquals(listOf("Alice", "ally", 7L), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersDeleteWithAndWithoutWhere() {
        val qualified = renderer.render(deleteFrom(SqliteUsers).where(SqliteUsers.id eq 7L))
        val allRows = renderer.render(deleteAllFrom(SqliteUsers))

        assertEquals("DELETE FROM \"users\" WHERE \"users\".\"id\" = ?", qualified.sql)
        assertEquals(listOf(7L), qualified.parameters.map { it.value })
        assertEquals("DELETE FROM \"users\"", allRows.sql)
        assertEquals(emptyList(), allRows.parameters)
    }

    @Test
    fun exposesSqliteStorageClassesWithoutChoosingBindingConversions() {
        assertEquals(
            listOf(
                SqliteStorageClass.INTEGER,
                SqliteStorageClass.REAL,
                SqliteStorageClass.TEXT,
                SqliteStorageClass.BLOB,
                SqliteStorageClass.NULL,
            ),
            SqliteStorageClass.entries,
        )
    }
}

private val renderer = SqlRenderer(SqliteDialect)

private object SqliteUsers : Table("users") {
    val id = long("id")
    val name = string("name")
    val age = int("age")
    val nickname = string("nickname").nullable()
    val active = boolean("active")
}

private object QuotedIdentifiers : Table("order\"items") {
    val reserved = string("select")
    val quoted = string("say\"hello")
}
