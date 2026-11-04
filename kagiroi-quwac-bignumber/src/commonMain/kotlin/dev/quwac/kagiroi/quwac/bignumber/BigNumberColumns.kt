package dev.quwac.kagiroi.quwac.bignumber

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.schema.columnMapping

/** Represents big integer column type. */
object BigIntegerColumnType : ColumnType<BigInteger>("BigInteger"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.quwac.bigInteger")
}

/** Represents big decimal column type. */
object BigDecimalColumnType : ColumnType<BigDecimal>("BigDecimal"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.quwac.bigDecimal")
}

/**
 * Exact decimal-text mappings for physical exact-numeric columns.
 *
 * Use these with a dialect's `decimal` or `numeric` factory when the application
 * selects the database precision and scale explicitly. The generic [BigIntegerColumnType]
 * and [BigDecimalColumnType] remain available for dialect extensions that choose their
 * own physical representation.
 */
object BigNumberTextMappings {
    /** Maps [BigInteger] to canonical decimal text. */
    val integer = columnMapping(fromStorage = BigInteger::parse, toStorage = BigInteger::toString)

    /** Maps [BigDecimal] to non-exponent decimal text accepted by exact-numeric SQL columns. */
    val decimal = columnMapping(fromStorage = BigDecimal::parse, toStorage = BigDecimal::toPlainString)
}

/** Provides the big integer result. */
fun Table.bigInteger(name: String): WritableColumn<BigInteger> = column(name, BigIntegerColumnType)

/** Provides the big decimal result. */
fun Table.bigDecimal(name: String): WritableColumn<BigDecimal> = column(name, BigDecimalColumnType)
