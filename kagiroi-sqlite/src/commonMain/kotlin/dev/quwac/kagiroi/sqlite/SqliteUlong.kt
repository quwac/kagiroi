package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn

/** Declares a full-range `ULong` column stored as zero-padded 20-digit SQLite `TEXT`. */
fun Table.textUlong(name: String): WritableColumn<ULong> = column(name, SqliteTypes.textUlong)
