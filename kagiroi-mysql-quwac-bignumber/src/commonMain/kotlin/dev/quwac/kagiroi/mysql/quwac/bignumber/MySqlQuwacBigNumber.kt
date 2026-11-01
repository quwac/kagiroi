package dev.quwac.kagiroi.mysql.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.mysql.MySqlExtension
import dev.quwac.kagiroi.mysql.MySqlTypeMapping
import dev.quwac.kagiroi.quwac.bignumber.BigDecimalColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigIntegerColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec

/**
 * BigNumber transport codecs for explicitly sized MySQL-compatible DECIMAL columns.
 *
 * Generic Typed DDL has no precision or scale model. It therefore must not render bare
 * DECIMAL, whose server default is DECIMAL(10,0), for an arbitrary-precision Kotlin value.
 * Applications can instead select `MySqlTypes.decimal(p, s, BigNumberTextMappings.decimal)`
 * or the corresponding integer mapping. Those physical types render Typed DDL and carry their
 * own parameter and row codec.
 */
object MySqlQuwacBigNumber : MySqlExtension {
    override val typeMappings: List<MySqlTypeMapping<*>> = emptyList()

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(BigIntegerColumnType, StringColumnType, BigInteger::toString, BigInteger::parse),
        SqlTypeCodec(BigDecimalColumnType, StringColumnType, BigDecimal::toString, BigDecimal::parse),
    )
}
