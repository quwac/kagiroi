package dev.quwac.kagiroi.sqlserver.quwac.bignumber

import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings
import dev.quwac.kagiroi.sqlserver.SqlServerColumnType
import dev.quwac.kagiroi.sqlserver.SqlServerTypes

/**
 * Exact SQL Server DECIMAL and NUMERIC declarations for the Pure Kotlin BigNumber types.
 *
 * SQL Server requires a declared precision and scale. The caller chooses those domain
 * constraints; this module never rounds a Kotlin value to make it fit.
 */
object SqlServerQuwacBigNumber {
    /** Provides the decimal integer result. */
    fun decimalInteger(precision: Int): SqlServerColumnType<dev.quwac.bignumber.BigInteger, String> =
        SqlServerTypes.decimal(precision, 0, BigNumberTextMappings.integer)

    /** Provides the decimal result. */
    fun decimal(precision: Int, scale: Int): SqlServerColumnType<dev.quwac.bignumber.BigDecimal, String> =
        SqlServerTypes.decimal(precision, scale, BigNumberTextMappings.decimal)

    /** Provides the numeric result. */
    fun numeric(precision: Int, scale: Int): SqlServerColumnType<dev.quwac.bignumber.BigDecimal, String> =
        SqlServerTypes.numeric(precision, scale, BigNumberTextMappings.decimal)
}
