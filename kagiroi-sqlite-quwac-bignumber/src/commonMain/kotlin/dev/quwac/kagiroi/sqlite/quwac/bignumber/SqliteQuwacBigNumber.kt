package dev.quwac.kagiroi.sqlite.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.quwac.bignumber.BigDecimalColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigIntegerColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sqlite.SqliteExtension

/** Lossless canonical decimal TEXT mappings for SQLite. */
object SqliteQuwacBigNumber : SqliteExtension {
    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(BigIntegerColumnType, StringColumnType, BigInteger::toString, BigInteger::parse),
        SqlTypeCodec(BigDecimalColumnType, StringColumnType, BigDecimal::toString, BigDecimal::parse),
    )
}
