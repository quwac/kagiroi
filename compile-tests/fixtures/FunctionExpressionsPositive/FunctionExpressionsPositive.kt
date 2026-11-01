package fixtures

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.abs
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.avg
import dev.quwac.kagiroi.schema.ceil
import dev.quwac.kagiroi.schema.concat
import dev.quwac.kagiroi.schema.coalesce
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.floor
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.ltrim
import dev.quwac.kagiroi.schema.max
import dev.quwac.kagiroi.schema.min
import dev.quwac.kagiroi.schema.notLike
import dev.quwac.kagiroi.schema.nullIf
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.power
import dev.quwac.kagiroi.schema.replace
import dev.quwac.kagiroi.schema.round
import dev.quwac.kagiroi.schema.rtrim
import dev.quwac.kagiroi.schema.sqrt
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.schema.trim
import dev.quwac.kagiroi.schema.upper
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.where

private object FunctionUsers : Table("function_users") {
    val id = long("id")
    val email = string("email")
    val name = string("name")
    val nickname = string("nickname").nullable()
    val amount = int("amount")
    val firstName = string("first_name")
    val lastName = string("last_name")
    val normalizedEmail = string("normalized_email").generated(lower(email), GeneratedColumnStorage.Stored)

    init {
        checkConstraint("name_not_empty") { length(name) gt 0 }
    }
}

fun validFunctionExpressions(normalizedEmail: String): String {
    val lowered: SqlExpression<String> = lower(FunctionUsers.email)
    val nullableLowered: SqlExpression<String?> = lower(FunctionUsers.nickname)
    val nameLength: SqlExpression<Long> = length(FunctionUsers.name)
    val nullableLength: SqlExpression<Long?> = length(FunctionUsers.nickname)
    val magnitude: SqlExpression<Int> = abs(FunctionUsers.amount)
    val total: SqlExpression<Long?> = sum(FunctionUsers.amount)
    val average: SqlExpression<Double?> = avg(FunctionUsers.amount)
    val smallest: SqlExpression<Int?> = min(FunctionUsers.amount)
    val largest: SqlExpression<Int?> = max(FunctionUsers.amount)
    val displayName: SqlExpression<String> = coalesce(FunctionUsers.nickname, literal("anonymous"))
    val nullName: SqlExpression<String?> = nullIf(FunctionUsers.name, "anonymous")
    val trimmedStart: SqlExpression<String?> = ltrim(FunctionUsers.nickname)
    val trimmedEnd: SqlExpression<String?> = rtrim(FunctionUsers.nickname)
    val prefix: SqlExpression<String> = substring(FunctionUsers.name, start = 1, length = 20)
    val replaced: SqlExpression<String> = replace(FunctionUsers.name, old = "a", new = "A")
    val fullName: SqlExpression<String> = concat(FunctionUsers.firstName, " ", FunctionUsers.lastName)
    val rounded: SqlExpression<Double> = round(FunctionUsers.amount, digits = 2)
    val floored: SqlExpression<Double> = floor(FunctionUsers.amount)
    val ceiling: SqlExpression<Double> = ceil(FunctionUsers.amount)
    val root: SqlExpression<Double?> = sqrt(FunctionUsers.amount)
    val squared: SqlExpression<Double> = power(FunctionUsers.amount, 2)
    val normalized = lower(FunctionUsers.email).alias("normalized_email")
    val query = select(
        FunctionUsers.id,
        normalized,
        upper(trim(FunctionUsers.name)),
        nameLength,
        magnitude,
        displayName,
        nullName,
        trimmedStart,
        trimmedEnd,
        prefix,
        replaced,
        fullName,
        rounded,
        floored,
        ceiling,
        root,
        squared,
        currentTimestamp(),
        count(),
        total,
        average,
        smallest,
        largest,
    ).from(FunctionUsers)
        .where((lowered eq normalizedEmail) and (FunctionUsers.name notLike "%bot%"))
        .orderBy(lower(FunctionUsers.name).asc())
    val readNormalized: (SqlRow) -> String = { row -> row[normalized] }
    return query.toString() + readNormalized.toString() + lowered + nullableLowered + nullableLength
}
