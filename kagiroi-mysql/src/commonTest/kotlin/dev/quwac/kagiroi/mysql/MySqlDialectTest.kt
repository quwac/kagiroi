package dev.quwac.kagiroi.mysql

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
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.isDistinctFrom
import dev.quwac.kagiroi.sql.isNotDistinctFrom
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.leftJoinUsing
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.rightJoin
import dev.quwac.kagiroi.sql.rightJoinUsing
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MySqlDialectTest {
    @Test
    fun rendersNativeInnerLeftAndRightUsingJoins() {
        val employee = QuotedUsers.alias("employee")
        val manager = QuotedUsers.alias("manager")
        val renderer = SqlRenderer(MySqlDialect)
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

        assertEquals(
            "SELECT `employee`.`id`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "INNER JOIN `app`.`user` AS `manager` USING (`id`)",
            renderer.render(inner).sql,
        )
        assertEquals(
            "SELECT `employee`.`id`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "LEFT JOIN `app`.`user` AS `manager` USING (`id`, `na``me`)",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT `employee`.`id`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "RIGHT JOIN `app`.`user` AS `manager` USING (`id`)",
            renderer.render(right).sql,
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
            "SELECT `employee`.`id`, `coworker`.`na``me` FROM `app`.`user` AS `employee` " +
                "CROSS JOIN `app`.`user` AS `coworker`",
            SqlRenderer(MySqlDialect).render(query).sql,
        )
    }

    @Test
    fun rendersLeftAndRightJoinsAndRejectsFullOuterJoin() {
        val employee = QuotedUsers.alias("employee")
        val manager = QuotedUsers.alias("manager")
        val renderer = SqlRenderer(MySqlDialect)
        val left = select(employee[QuotedUsers.name], manager[QuotedUsers.name].optional)
            .from(employee)
            .leftJoin(manager, on = employee[QuotedUsers.id] eq manager[QuotedUsers.id])
        val right = select(employee[QuotedUsers.name].optional, manager[QuotedUsers.name])
            .from(employee)
            .rightJoin(manager, on = employee[QuotedUsers.id] eq manager[QuotedUsers.id])

        assertEquals(
            "SELECT `employee`.`na``me`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "LEFT JOIN `app`.`user` AS `manager` ON `employee`.`id` = `manager`.`id`",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT `employee`.`na``me`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "RIGHT JOIN `app`.`user` AS `manager` ON `employee`.`id` = `manager`.`id`",
            renderer.render(right).sql,
        )

        val full = select(employee[QuotedUsers.id].optional, manager[QuotedUsers.id].optional)
            .from(employee)
            .fullOuterJoin(manager, on = employee[QuotedUsers.id] eq manager[QuotedUsers.id])
        val failure = assertFailsWith<UnsupportedCapability> { renderer.render(full) }

        assertEquals("full-join", failure.capabilityName)
    }

    @Test
    fun rendersPortableFunctionNamesWithCharacterLength() {
        val normalized = lower(QuotedUsers.name).alias("normalized_name")
        val characterLength = length(QuotedUsers.name).alias("name_length")
        val rowCount = count().alias("row_count")

        assertEquals(
            "SELECT LOWER(`app`.`user`.`na``me`) AS `normalized_name`, " +
                "CHAR_LENGTH(`app`.`user`.`na``me`) AS `name_length`, COUNT(*) AS `row_count` FROM `app`.`user` " +
                "GROUP BY LOWER(`app`.`user`.`na``me`), CHAR_LENGTH(`app`.`user`.`na``me`)",
            SqlRenderer(MySqlDialect).render(
                select(normalized, characterLength, rowCount).from(QuotedUsers).groupBy(normalized, characterLength),
            ).sql,
        )
    }

    @Test
    fun rendersMySqlSpecificSubstringConcatAndCurrentTimestamp() {
        val rendered = SqlRenderer(MySqlDialect).render(
            select(
                substring(QuotedUsers.name, 1, 2).alias("prefix"),
                concat(QuotedUsers.name, QuotedUsers.name).alias("doubled"),
                currentTimestamp().alias("observed_at"),
            ).from(QuotedUsers),
        )

        assertEquals(
            "SELECT SUBSTRING(`app`.`user`.`na``me`, ?, ?) AS `prefix`, " +
                "CONCAT(`app`.`user`.`na``me`, `app`.`user`.`na``me`) AS `doubled`, " +
                "UTC_TIMESTAMP(6) AS `observed_at` FROM `app`.`user`",
            rendered.sql,
        )
        assertEquals(listOf("1", "2"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersMySqlCurrentDateAndTime() {
        assertEquals("CURRENT_DATE", MySqlDialect.renderFunction(SqlFunction.CurrentDate, emptyList()))
        assertEquals("CURRENT_TIME", MySqlDialect.renderFunction(SqlFunction.CurrentTime, emptyList()))
        assertEquals("UTC_TIMESTAMP(6)", MySqlDialect.renderFunction(SqlFunction.CurrentTimestamp, emptyList()))
    }

    @Test
    fun rendersQuestionMarkParametersAndEscapedIdentifiers() {
        val rendered = SqlRenderer(MySqlDialect).render(
            select(QuotedUsers.id, QuotedUsers.name)
                .from(QuotedUsers)
                .where(QuotedUsers.id eq 42L)
                .limit(10)
                .offset(5),
        )

        assertEquals(
            "SELECT `app`.`user`.`id`, `app`.`user`.`na``me` " +
                "FROM `app`.`user` WHERE `app`.`user`.`id` = ? LIMIT ? OFFSET ?",
            rendered.sql,
        )
        assertEquals(listOf("42", "10", "5"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersNullSafeComparisonsWithMySqlNativeEquality() {
        val rendered = SqlRenderer(MySqlDialect).render(
            select(QuotedUsers.id)
                .from(QuotedUsers)
                .where(
                    (QuotedUsers.name isNotDistinctFrom "Ada") and
                        (QuotedUsers.name isDistinctFrom "Grace"),
                ),
        )

        assertEquals(
            "SELECT `app`.`user`.`id` FROM `app`.`user` WHERE " +
                "((`app`.`user`.`na``me` <=> ?) AND NOT (`app`.`user`.`na``me` <=> ?))",
            rendered.sql,
        )
        assertEquals(listOf("Ada", "Grace"), rendered.parameters.map { it.value })
    }

    @Test
    fun quotesReservedAndEmbeddedBacktickIdentifiers() {
        assertEquals("`select`", MySqlDialect.quoteIdentifier("select"))
        assertEquals("`a``b`", MySqlDialect.quoteIdentifier("a`b"))
    }
}

private object QuotedUsers : Table("user", schemaName = "app") {
    val id = long("id")
    val name = string("na`me")
}
