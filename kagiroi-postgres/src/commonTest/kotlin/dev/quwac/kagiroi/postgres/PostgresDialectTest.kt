package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.concat
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.round
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.fullOuterJoinUsing
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.leftJoinUsing
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.rightJoinUsing
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresDialectTest {
    @Test
    fun rendersNativeUsingJoinsForEverySupportedOuterKind() {
        val employee = QuotedUsers.alias("employee")
        val manager = QuotedUsers.alias("manager")
        val renderer = SqlRenderer(PostgresDialect)
        val inner = select(employee[QuotedUsers.id], manager[QuotedUsers.name])
            .from(employee)
            .innerJoinUsing(manager) { using(employee[QuotedUsers.id], manager[QuotedUsers.id]) }
        val left = select(employee[QuotedUsers.id], manager[QuotedUsers.name].optional)
            .from(employee)
            .leftJoinUsing(manager) {
                using(employee[QuotedUsers.id], manager[QuotedUsers.id])
                using(employee[QuotedUsers.name], manager[QuotedUsers.name])
            }
        val right = select(employee[QuotedUsers.id].optional, manager[QuotedUsers.name])
            .from(employee)
            .rightJoinUsing(manager) { using(employee[QuotedUsers.id], manager[QuotedUsers.id]) }
        val full = select(employee[QuotedUsers.id].optional, manager[QuotedUsers.name].optional)
            .from(employee)
            .fullOuterJoinUsing(manager) { using(employee[QuotedUsers.id], manager[QuotedUsers.id]) }

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"na\"\"me\" FROM \"app\".\"user\" AS " +
                "\"employee\" INNER JOIN \"app\".\"user\" AS \"manager\" USING (\"id\")",
            renderer.render(inner).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"na\"\"me\" FROM \"app\".\"user\" AS " +
                "\"employee\" LEFT JOIN \"app\".\"user\" AS \"manager\" USING (\"id\", \"na\"\"me\")",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"na\"\"me\" FROM \"app\".\"user\" AS " +
                "\"employee\" RIGHT JOIN \"app\".\"user\" AS \"manager\" USING (\"id\")",
            renderer.render(right).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"na\"\"me\" FROM \"app\".\"user\" AS " +
                "\"employee\" FULL OUTER JOIN \"app\".\"user\" AS \"manager\" USING (\"id\")",
            renderer.render(full).sql,
        )
    }

    @Test
    fun rendersCrossJoinWithoutOn() {
        val employee = QuotedUsers.alias("employee")
        val coworker = QuotedUsers.alias("coworker")
        val query = select(employee[QuotedUsers.id], coworker[QuotedUsers.name])
            .from(employee)
            .crossJoin(coworker)

        assertEquals(
            "SELECT \"employee\".\"id\", \"coworker\".\"na\"\"me\" FROM \"app\".\"user\" AS \"employee\" " +
                "CROSS JOIN \"app\".\"user\" AS \"coworker\"",
            SqlRenderer(PostgresDialect).render(query).sql,
        )
    }

    @Test
    fun rendersAliasedInnerLeftAndFullOuterJoins() {
        val employee = QuotedUsers.alias("employee")
        val manager = QuotedUsers.alias("manager")
        val reviewer = QuotedUsers.alias("reviewer")
        val query = select(
            employee[QuotedUsers.id],
            manager[QuotedUsers.name],
            reviewer[QuotedUsers.name].optional,
        ).from(employee)
            .innerJoin(manager, on = employee[QuotedUsers.id] eq manager[QuotedUsers.id])
            .leftJoin(reviewer, on = employee[QuotedUsers.id] eq reviewer[QuotedUsers.id])

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"na\"\"me\", \"reviewer\".\"na\"\"me\" " +
                "FROM \"app\".\"user\" AS \"employee\" INNER JOIN \"app\".\"user\" AS \"manager\" ON " +
                "\"employee\".\"id\" = \"manager\".\"id\" LEFT JOIN \"app\".\"user\" AS \"reviewer\" ON " +
                "\"employee\".\"id\" = \"reviewer\".\"id\"",
            SqlRenderer(PostgresDialect).render(query).sql,
        )

        val full = select(employee[QuotedUsers.id].optional, manager[QuotedUsers.id].optional)
            .from(employee)
            .fullOuterJoin(manager, on = employee[QuotedUsers.id] eq manager[QuotedUsers.id])

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"id\" FROM \"app\".\"user\" AS \"employee\" " +
                "FULL OUTER JOIN \"app\".\"user\" AS \"manager\" ON \"employee\".\"id\" = \"manager\".\"id\"",
            SqlRenderer(PostgresDialect).render(full).sql,
        )
    }

    @Test
    fun rendersPortableFunctionNames() {
        val normalized = lower(QuotedUsers.name).alias("normalized_name")
        val characterLength = length(QuotedUsers.name).alias("name_length")
        val rowCount = count().alias("row_count")

        assertEquals(
            "SELECT LOWER(\"app\".\"user\".\"na\"\"me\") AS \"normalized_name\", " +
                "LENGTH(\"app\".\"user\".\"na\"\"me\") AS \"name_length\", COUNT(*) AS \"row_count\" " +
                "FROM \"app\".\"user\" GROUP BY LOWER(\"app\".\"user\".\"na\"\"me\"), " +
                "LENGTH(\"app\".\"user\".\"na\"\"me\")",
            SqlRenderer(PostgresDialect).render(
                select(normalized, characterLength, rowCount).from(QuotedUsers).groupBy(normalized, characterLength),
            ).sql,
        )
    }

    @Test
    fun rendersPostgresSpecificSubstringConcatAndCurrentTimestamp() {
        val rendered = SqlRenderer(PostgresDialect).render(
            select(
                substring(QuotedUsers.name, 1, 2).alias("prefix"),
                concat(QuotedUsers.name, QuotedUsers.name).alias("doubled"),
                currentTimestamp().alias("observed_at"),
            ).from(QuotedUsers),
        )

        assertEquals(
            "SELECT SUBSTRING(\"app\".\"user\".\"na\"\"me\" FROM ($1)::integer FOR ($2)::integer) AS \"prefix\", " +
                "(\"app\".\"user\".\"na\"\"me\" || \"app\".\"user\".\"na\"\"me\") AS \"doubled\", " +
                "CURRENT_TIMESTAMP AS \"observed_at\" FROM \"app\".\"user\"",
            rendered.sql,
        )
        assertEquals(listOf("1", "2"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersPostgresRoundWithBoundDigitsAsNumericAndInteger() {
        val rendered = SqlRenderer(PostgresDialect).render(
            select(round(QuotedUsers.id, digits = 2).alias("rounded")).from(QuotedUsers),
        )

        assertEquals(
            "SELECT ROUND((\"app\".\"user\".\"id\")::numeric, ($1)::integer) AS \"rounded\" " +
                "FROM \"app\".\"user\"",
            rendered.sql,
        )
        assertEquals(listOf("2"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersPostgresCurrentDateAndTime() {
        assertEquals("CURRENT_DATE", PostgresDialect.renderFunction(SqlFunction.CurrentDate, emptyList()))
        assertEquals("CURRENT_TIME", PostgresDialect.renderFunction(SqlFunction.CurrentTime, emptyList()))
        assertEquals("CURRENT_TIMESTAMP", PostgresDialect.renderFunction(SqlFunction.CurrentTimestamp, emptyList()))
    }

    @Test
    fun rendersNumberedParametersAndEscapedIdentifiers() {
        val rendered = SqlRenderer(PostgresDialect).render(
            select(QuotedUsers.id, QuotedUsers.name)
                .from(QuotedUsers)
                .where(QuotedUsers.id eq 42L)
                .limit(10)
                .offset(5),
        )

        assertEquals(
            "SELECT \"app\".\"user\".\"id\", \"app\".\"user\".\"na\"\"me\" " +
                "FROM \"app\".\"user\" WHERE \"app\".\"user\".\"id\" = $1 LIMIT $2 OFFSET $3",
            rendered.sql,
        )
        assertEquals(listOf("42", "10", "5"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun quotesReservedAndEmbeddedQuoteIdentifiers() {
        assertEquals("\"select\"", PostgresDialect.quoteIdentifier("select"))
        assertEquals("\"a\"\"b\"", PostgresDialect.quoteIdentifier("a\"b"))
    }
}

private object QuotedUsers : Table("user", schemaName = "app") {
    val id = long("id")
    val name = string("na\"me")
}
