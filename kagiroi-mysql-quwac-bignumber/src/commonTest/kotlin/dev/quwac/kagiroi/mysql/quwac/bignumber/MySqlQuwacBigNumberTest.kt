package dev.quwac.kagiroi.mysql.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlDialect
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import dev.quwac.kagiroi.mysql.MySqlType
import dev.quwac.kagiroi.mysql.MySqlTypeRegistry
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.mysql.MySqlValueCodec
import dev.quwac.kagiroi.quwac.bignumber.BigDecimalColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigIntegerColumnType
import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.createTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MySqlQuwacBigNumberTest {
    @Test
    fun leavesDecimalPrecisionAndScaleOutsideGenericDdl() {
        val registry = MySqlTypeRegistry(listOf(MySqlQuwacBigNumber))

        assertTrue(MySqlQuwacBigNumber.typeMappings.isEmpty())
        assertNull(registry.typeOf(BigIntegerColumnType))
        assertNull(registry.typeOf(BigDecimalColumnType))
    }

    @Test
    fun refusesBareDecimalForArbitraryPrecisionTypedDdl() {
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.configured(listOf(MySqlQuwacBigNumber)).render(createTable(BigNumbers))
        }
    }

    @Test
    fun mapsBigNumberValuesThroughExplicitPhysicalDecimalDeclarations() {
        val integer = BigInteger.parse("999999999999999999999999999999")
        val decimal = BigDecimal.parse("123456789012.123456789012345678")
        val codec = MySqlValueCodec(SqlTypeCodecRegistry(emptyList()))

        assertEquals(
            "CREATE TABLE `exact_big_numbers` (`integer_value` DECIMAL(30,0) NOT NULL, " +
                "`decimal_value` DECIMAL(30,18) NOT NULL)",
            MySqlDialect.render(createTable(ExactBigNumbers)).sql,
        )
        assertEquals(MySqlBinding.Text(integer.toString()), codec.binding(integer, ExactBigNumbers.integerValue))
        assertEquals(MySqlBinding.Text(decimal.toString()), codec.binding(decimal, ExactBigNumbers.decimalValue))
        assertEquals(
            integer,
            codec.decode(
                ExactBigNumbers.integerValue,
                MySqlResultValue(MySqlType.DECIMAL.protocolType, MySqlScalar.Text(integer.toString())),
            ),
        )
        assertEquals(
            decimal,
            codec.decode(
                ExactBigNumbers.decimalValue,
                MySqlResultValue(MySqlType.DECIMAL.protocolType, MySqlScalar.Text(decimal.toString())),
            ),
        )
    }
}

private object BigNumbers : Table("big_numbers") {
    val amount = bigDecimal("amount")
}

private object ExactBigNumbers : Table("exact_big_numbers") {
    val integerValue = column("integer_value", MySqlTypes.decimal(30, 0, BigNumberTextMappings.integer))
    val decimalValue = column("decimal_value", MySqlTypes.decimal(30, 18, BigNumberTextMappings.decimal))
}
