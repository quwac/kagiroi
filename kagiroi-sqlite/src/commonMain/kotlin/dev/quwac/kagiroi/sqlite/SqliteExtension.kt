package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.DurationColumnType
import dev.quwac.kagiroi.schema.InstantColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec
import kotlin.time.Duration
import kotlin.time.Instant

/** An explicit set of SQLite-specific value mappings installed into a SQLite database. */
interface SqliteExtension {
    val typeCodecs: List<SqlTypeCodec<*, *>>
}

/** SQLite mappings for the time types defined by kagiroi-schema. */
object SqliteStandardTypes : SqliteExtension {
    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(
            type = InstantColumnType,
            storageType = StringColumnType,
            encode = Instant::toString,
            decode = Instant::parse,
        ),
        SqlTypeCodec(
            type = DurationColumnType,
            storageType = StringColumnType,
            encode = Duration::toIsoString,
            decode = Duration::parseIsoString,
        ),
    )
}
