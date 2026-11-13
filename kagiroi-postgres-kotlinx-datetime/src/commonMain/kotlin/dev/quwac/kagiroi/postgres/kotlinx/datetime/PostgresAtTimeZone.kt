package dev.quwac.kagiroi.postgres.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.schema.InstantColumnType
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.resultExpression
import kotlinx.datetime.LocalDateTime
import kotlin.js.JsName
import kotlin.time.Instant

/** Converts an instant to the calendar date-time in [zone] using PostgreSQL AT TIME ZONE. */
@JsName("instantAtTimeZone")
fun SqlExpression<Instant>.atTimeZone(zone: String): SqlFunctionExpression<LocalDateTime> {
    require(zone.isNotBlank()) { "Time zone must not be blank" }
    return SqlFunctionExpression(
        SqlFunction.PostgresAtTimeZone,
        listOf(this, literal(zone)),
        resultExpression(KotlinxLocalDateTimeColumnType),
    )
}

/** Interprets a local date-time in [zone] as an instant using PostgreSQL AT TIME ZONE. */
@JsName("localDateTimeAtTimeZone")
fun SqlExpression<LocalDateTime>.atTimeZone(zone: String): SqlFunctionExpression<Instant> {
    require(zone.isNotBlank()) { "Time zone must not be blank" }
    return SqlFunctionExpression(
        SqlFunction.PostgresAtTimeZone,
        listOf(this, literal(zone)),
        resultExpression(InstantColumnType),
    )
}
