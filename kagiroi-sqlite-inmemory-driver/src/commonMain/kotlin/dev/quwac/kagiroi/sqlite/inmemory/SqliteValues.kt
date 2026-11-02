package dev.quwac.kagiroi.sqlite.inmemory

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sqlite.SqliteValueCodec

internal fun binding(value: Any?, expression: SqlExpression<*>? = null, codecs: SqlTypeCodecRegistry): SqliteBinding =
    SqliteValueCodec(codecs).binding(value, expression)

internal fun decode(column: SqlExpression<*>, value: SqliteValue, codecs: SqlTypeCodecRegistry): Any? =
    SqliteValueCodec(codecs).decode(column, value)

internal fun rawValue(value: SqliteValue): Any? = SqliteValueCodec(SqlTypeCodecRegistry(emptyList())).rawValue(value)
