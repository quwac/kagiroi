package dev.quwac.kagiroi.postgres.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresDialect
import dev.quwac.kagiroi.postgres.PostgresResultValue
import dev.quwac.kagiroi.postgres.PostgresType
import dev.quwac.kagiroi.postgres.PostgresTypeRegistry
import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.postgres.PostgresValueCodec
import dev.quwac.kagiroi.quwac.bignumber.BigDecimalColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigIntegerColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.createTable
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresQuwacBigNumberTest {
    @Test
    fun mapsArbitraryPrecisionValuesToNumeric() {
        val registry = PostgresTypeRegistry(listOf(PostgresQuwacBigNumber))

        assertEquals(PostgresType.NUMERIC, registry.typeOf(BigIntegerColumnType))
        assertEquals(PostgresType.NUMERIC, registry.typeOf(BigDecimalColumnType))
    }

    @Test
    fun mapsBigNumberValuesThroughExplicitPhysicalNumericDeclarations() {
        val integer = BigInteger.parse("9999999999999999999999999999999999999999")
        val decimal = BigDecimal.parse("123456789012.123456789012345678")
        val codec = PostgresValueCodec(SqlTypeCodecRegistry(emptyList()))

        assertEquals(
            "CREATE TABLE \"exact_big_numbers\" (\"integer_value\" NUMERIC(40,0) NOT NULL, " +
                "\"decimal_value\" NUMERIC(30,18) NOT NULL)",
            PostgresDialect.render(createTable(ExactBigNumbers)).sql,
        )
        assertEquals(PostgresBinding.Text(integer.toString()), codec.binding(integer, ExactBigNumbers.integerValue))
        assertEquals(PostgresBinding.Text(decimal.toString()), codec.binding(decimal, ExactBigNumbers.decimalValue))
        assertEquals(
            integer,
            codec.decode(
                ExactBigNumbers.integerValue,
                PostgresResultValue(PostgresType.NUMERIC.objectId, integer.toString()),
            ),
        )
        assertEquals(
            decimal,
            codec.decode(
                ExactBigNumbers.decimalValue,
                PostgresResultValue(PostgresType.NUMERIC.objectId, decimal.toString()),
            ),
        )
    }
}

private object ExactBigNumbers : Table("exact_big_numbers") {
    val integerValue = column("integer_value", PostgresTypes.numeric(40, 0, BigNumberTextMappings.integer))
    val decimalValue = column("decimal_value", PostgresTypes.numeric(30, 18, BigNumberTextMappings.decimal))
}
