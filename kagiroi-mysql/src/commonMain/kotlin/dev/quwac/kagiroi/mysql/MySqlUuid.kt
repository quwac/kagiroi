package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn
import kotlin.uuid.Uuid

/** Declares a UUID stored as MySQL `BINARY(16)` instead of the default `CHAR(36)`. */
fun Table.binaryUuid(name: String): WritableColumn<Uuid> = column(name, MySqlTypes.binaryUuid)
