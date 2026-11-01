package dev.quwac.kagiroi.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.DoubleQuotedQuestionMarkDialect
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.gte
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BigNumberColumnsTest {
    @Test
    fun mapsExactNumbersThroughDecimalText() {
        val integer = BigInteger.parse("999999999999999999999999999999")
        val decimal = BigDecimal.parse("123456789012.123456789012345678")
        val smallDecimal = BigDecimal.parse("0.0000001")

        assertEquals(integer, BigNumberTextMappings.integer.fromStorageValue(integer.toString()))
        assertEquals(decimal, BigNumberTextMappings.decimal.fromStorageValue(decimal.toString()))
        assertEquals("0.0000001", BigNumberTextMappings.decimal.toStorageValue(smallDecimal))
        assertEquals(smallDecimal, BigNumberTextMappings.decimal.fromStorageValue("0.0000001"))
    }

    @Test
    fun definesAndSelectsTypedBigNumberColumns() {
        val amount: Column<BigDecimal> = Ledger.amount
        val sequence: Column<BigInteger?> = Ledger.sequence
        val total: Column<BigDecimal> = Ledger.total
        val minimum = BigDecimal.parse("12345678901234567890.12")
        val query = select(amount, sequence, total).from(Ledger).where(amount gte minimum)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertSame(BigDecimalColumnType, amount.type)
        assertSame(BigIntegerColumnType, sequence.type)
        assertTrue(sequence.isNullable)
        assertTrue(total.isComputed)
        assertEquals(
            "SELECT \"ledger\".\"amount\", \"ledger\".\"sequence\", \"ledger\".\"total\" " +
                "FROM \"ledger\" WHERE \"ledger\".\"amount\" >= ?",
            rendered.sql,
        )
        assertSame(minimum, rendered.parameters.single().value)
    }
}

private object Ledger : Table("ledger") {
    val amount = bigDecimal("amount")
    val sequence = bigInteger("sequence").nullable()
    val total = bigDecimal("total").computed()
}
