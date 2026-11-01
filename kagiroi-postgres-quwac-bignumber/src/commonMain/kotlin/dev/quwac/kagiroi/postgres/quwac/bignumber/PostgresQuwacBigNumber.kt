package dev.quwac.kagiroi.postgres.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.postgres.PostgresExtension
import dev.quwac.kagiroi.postgres.PostgresType
import dev.quwac.kagiroi.postgres.PostgresTypeMapping
import dev.quwac.kagiroi.quwac.bignumber.BigDecimalColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigIntegerColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec

/** Lossless NUMERIC mappings for the Pure Kotlin arbitrary-precision types. */
object PostgresQuwacBigNumber : PostgresExtension {
    override val typeMappings: List<PostgresTypeMapping<*>> = listOf(
        PostgresTypeMapping(BigIntegerColumnType, PostgresType.NUMERIC),
        PostgresTypeMapping(BigDecimalColumnType, PostgresType.NUMERIC),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(BigIntegerColumnType, StringColumnType, BigInteger::toString, BigInteger::parse),
        SqlTypeCodec(BigDecimalColumnType, StringColumnType, BigDecimal::toString, BigDecimal::parse),
    )
}
