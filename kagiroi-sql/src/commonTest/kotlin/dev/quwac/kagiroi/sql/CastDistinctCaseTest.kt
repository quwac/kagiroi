package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.case
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.countDistinct
import dev.quwac.kagiroi.schema.elseValue
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.isNotNull
import dev.quwac.kagiroi.schema.longType
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.stringType
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.schema.whenThen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CastDistinctCaseTest {
    @Test
    fun preservesCastTypeNullabilityAliasAndComparison() {
        val idText: SqlExpression<String> = cast(CastCaseOrders.id, stringType())
        val optionalText: SqlExpression<String?> = cast(CastCaseOrders.optionalId, stringType())
        val idTextAlias = idText.alias("id_text")

        val rendered = SqlRenderer(CastDialect).render(
            select(idTextAlias, optionalText)
                .from(CastCaseOrders)
                .where(idText eq "42"),
        )

        assertEquals(false, idText.isNullable)
        assertEquals(true, optionalText.isNullable)
        assertEquals(
            "SELECT CAST(\"cast_case_orders\".\"id\" AS TEXT) AS \"id_text\", " +
                "CAST(\"cast_case_orders\".\"optional_id\" AS TEXT) FROM \"cast_case_orders\" " +
                "WHERE CAST(\"cast_case_orders\".\"id\" AS TEXT) = ?",
            rendered.sql,
        )
        assertEquals(listOf("42"), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersSearchedCaseBranchesAsParametersAndKeepsExpressionTypes() {
        val category: SqlExpression<String> = case<String> {
            whenThen(CastCaseOrders.amount gte 100L, "high")
            whenThen(CastCaseOrders.amount gte 50L, "medium")
            elseValue("low")
        }
        val displayName: SqlExpression<String?> = case<String?> {
            whenThen(CastCaseOrders.nickname.isNotNull(), CastCaseOrders.nickname)
            elseValue(CastCaseOrders.name)
        }

        val rendered = SqlRenderer(CastDialect).render(
            select(category.alias("category"), displayName.alias("display_name"))
                .from(CastCaseOrders)
                .where(category eq "high")
                .orderBy(category.desc()),
        )

        assertEquals(
            "SELECT CASE WHEN \"cast_case_orders\".\"amount\" >= ? THEN ? WHEN \"cast_case_orders\".\"amount\" >= ? " +
                "THEN ? ELSE ? END AS \"category\", CASE WHEN \"cast_case_orders\".\"nickname\" IS NOT NULL " +
                "THEN \"cast_case_orders\".\"nickname\" ELSE \"cast_case_orders\".\"name\" END AS \"display_name\" " +
                "FROM \"cast_case_orders\" WHERE CASE WHEN \"cast_case_orders\".\"amount\" >= ? THEN ? WHEN " +
                "\"cast_case_orders\".\"amount\" >= ? THEN ? ELSE ? END = ? ORDER BY CASE WHEN " +
                "\"cast_case_orders\".\"amount\" >= ? THEN ? WHEN \"cast_case_orders\".\"amount\" >= ? THEN ? ELSE ? END DESC",
            rendered.sql,
        )
        assertEquals(
            listOf(
                100L, "high", 50L, "medium", "low",
                100L, "high", 50L, "medium", "low", "high",
                100L, "high", 50L, "medium", "low",
            ),
            rendered.parameters.map { it.value },
        )
    }

    @Test
    fun composesCaseWithAggregateAndWindowAggregate() {
        val completed: SqlExpression<Long> = case<Long> {
            whenThen(CastCaseOrders.active eq true, 1L)
            elseValue(0L)
        }
        val total = sum(completed).alias("completed_total")
        val running = sum(completed).over {
            partitionBy(CastCaseOrders.organizationId)
            orderBy(CastCaseOrders.id.asc())
        }.alias("running_completed")

        val totalRendered = SqlRenderer(CastDialect).render(select(total).from(CastCaseOrders))
        val runningRendered = SqlRenderer(CastDialect).render(select(running).from(CastCaseOrders))

        assertEquals(
            "SELECT SUM(CAST(CASE WHEN \"cast_case_orders\".\"active\" = ? THEN ? ELSE ? END AS BIGINT)) " +
                "AS \"completed_total\" " +
                "FROM \"cast_case_orders\"",
            totalRendered.sql,
        )
        assertEquals(
            "SELECT SUM(CAST(CASE WHEN \"cast_case_orders\".\"active\" = ? THEN ? ELSE ? END AS BIGINT)) OVER " +
                "(PARTITION BY \"cast_case_orders\".\"organization_id\" ORDER BY \"cast_case_orders\".\"id\" ASC) " +
                "AS \"running_completed\" FROM \"cast_case_orders\"",
            runningRendered.sql,
        )
        assertEquals(listOf(true, 1L, 0L), totalRendered.parameters.map { it.value })
        assertEquals(listOf(true, 1L, 0L), runningRendered.parameters.map { it.value })
    }

    @Test
    fun keepsSelectDistinctSeparateFromAggregateAndSetDistinct() {
        val distinct = select(CastCaseOrders.organizationId, CastCaseOrders.name)
            .distinct()
            .from(CastCaseOrders)
            .orderBy(CastCaseOrders.organizationId.asc())
        val count = countDistinct(CastCaseOrders.organizationId).alias("organization_count")

        assertEquals(
            "SELECT DISTINCT \"cast_case_orders\".\"organization_id\", \"cast_case_orders\".\"name\" " +
                "FROM \"cast_case_orders\" ORDER BY \"cast_case_orders\".\"organization_id\" ASC",
            SqlRenderer(CastDialect).render(distinct).sql,
        )
        assertEquals(
            "SELECT COUNT(DISTINCT \"cast_case_orders\".\"organization_id\") AS \"organization_count\" " +
                "FROM \"cast_case_orders\"",
            SqlRenderer(CastDialect).render(select(count).from(CastCaseOrders)).sql,
        )
        assertFailsWith<IllegalArgumentException> { distinct.distinct() }
        assertEquals(
            "SELECT DISTINCT \"cast_case_orders\".\"organization_id\" FROM \"cast_case_orders\" UNION " +
                "SELECT \"cast_case_orders\".\"organization_id\" FROM \"cast_case_orders\"",
            SqlRenderer(CastDialect).render(
                select(CastCaseOrders.organizationId).distinct().from(CastCaseOrders)
                    .union(select(CastCaseOrders.organizationId).from(CastCaseOrders)),
            ).sql,
        )
    }

    @Test
    fun rendersNestedCastAndUsesTheSelectedTargetForEveryLevel() {
        val nested = cast(cast(CastCaseOrders.id, stringType()), longType())

        assertEquals(
            "SELECT CAST(CAST(\"cast_case_orders\".\"id\" AS TEXT) AS BIGINT) FROM \"cast_case_orders\"",
            SqlRenderer(CastDialect).render(select(nested).from(CastCaseOrders)).sql,
        )
    }

    @Test
    fun supportsNestedCaseAndTheValueFreeGeneratedColumnSubset() {
        val nested = case<String> {
            whenThen(
                CastCaseOrders.active eq true,
                case<String> {
                    whenThen(CastCaseOrders.amount gte 100L, "very_high")
                    elseValue("active")
                },
            )
            elseValue("inactive")
        }

        assertEquals(
            "SELECT CASE WHEN \"cast_case_orders\".\"active\" = ? THEN CASE WHEN " +
                "\"cast_case_orders\".\"amount\" >= ? THEN ? ELSE ? END ELSE ? END FROM \"cast_case_orders\"",
            SqlRenderer(CastDialect).render(select(nested).from(CastCaseOrders)).sql,
        )
        assertEquals(
            "(CAST(\"id\" AS TEXT))",
            renderGeneratedColumnExpression(
                requireNotNull(CastCaseGenerated.idText.generatedExpression),
                CastDialect::quoteIdentifier,
                CastDialect::renderFunction,
                CastDialect::renderCastTarget,
            ),
        )
        assertEquals(
            "(CASE WHEN \"id\" = \"other_id\" THEN \"primary_name\" ELSE \"fallback_name\" END)",
            renderGeneratedColumnExpression(
                requireNotNull(CastCaseGenerated.category.generatedExpression),
                CastDialect::quoteIdentifier,
                CastDialect::renderFunction,
                CastDialect::renderCastTarget,
            ),
        )
    }
}

private object CastDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override fun parameterMarker(position: Int): String = "?"

    override fun renderCastTarget(target: SqlCastTarget<*>): String = when (target.type.name) {
        "String" -> "TEXT"
        "Long" -> "BIGINT"
        else -> error("Unexpected test CAST target: ${target.type.name}")
    }
}

private object CastCaseOrders : Table("cast_case_orders") {
    val id = long("id")
    val optionalId = long("optional_id").nullable()
    val organizationId = long("organization_id")
    val amount = long("amount")
    val active = boolean("active")
    val name = string("name")
    val nickname = string("nickname").nullable()
}

private object CastCaseGenerated : Table("cast_case_generated") {
    val id = long("id")
    val otherId = long("other_id")
    val primaryName = string("primary_name")
    val fallbackName = string("fallback_name")
    val idText = string("id_text").generated(cast(id, stringType()), GeneratedColumnStorage.Stored)
    val category = string("category").generated(
        case<String> {
            whenThen(id eq otherId, primaryName)
            elseValue(fallbackName)
        },
        GeneratedColumnStorage.Stored,
    )
}
