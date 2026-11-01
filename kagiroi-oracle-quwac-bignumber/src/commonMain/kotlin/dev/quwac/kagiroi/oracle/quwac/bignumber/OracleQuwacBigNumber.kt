package dev.quwac.kagiroi.oracle.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.oracle.OracleExtension
import dev.quwac.kagiroi.oracle.OracleType
import dev.quwac.kagiroi.oracle.OracleTypeMapping
import dev.quwac.kagiroi.quwac.bignumber.BigDecimalColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigIntegerColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec

/** Lossless Oracle NUMBER mappings backed by Pure Kotlin arbitrary-precision values. */
object OracleQuwacBigNumber : OracleExtension {
    override val typeMappings: List<OracleTypeMapping<*>> = listOf(
        OracleTypeMapping(BigIntegerColumnType, OracleType.NUMBER),
        OracleTypeMapping(BigDecimalColumnType, OracleType.NUMBER),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(BigIntegerColumnType, StringColumnType, BigInteger::toString, BigInteger::parse),
        SqlTypeCodec(BigDecimalColumnType, StringColumnType, BigDecimal::toString, BigDecimal::parse),
    )
}
