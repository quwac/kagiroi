package fixtures

import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.postgres.kotlinx.datetime.atTimeZone
import dev.quwac.kagiroi.schema.InstantColumnType
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.currentInstant
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.typeUsing
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

object UtcRecords : Table("utc_records") {
    val createdAt = localDateTime("created_at")
        .typeUsing(InstantColumnType) { previous -> previous.atTimeZone("UTC") }
        .default(currentInstant().atTimeZone("UTC"))
}

val local: SqlExpression<LocalDateTime> = currentInstant().atTimeZone("UTC")
val instant: SqlExpression<Instant> = UtcRecords.createdAt.atTimeZone("UTC")
