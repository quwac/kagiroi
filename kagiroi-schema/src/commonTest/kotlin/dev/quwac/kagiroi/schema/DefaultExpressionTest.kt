package dev.quwac.kagiroi.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DefaultExpressionTest {
    @Test
    fun storesOrdinaryTypedExpressionsAsStructuralDefaultMetadata() {
        val expression = TypedDefaults.normalized.defaultExpression?.expression as SqlFunctionExpression<*>

        assertEquals(SqlFunction.Lower, expression.function)
        assertEquals(
            TypedDefaults.normalized.defaultExpression,
            EquivalentTypedDefaults.normalized.defaultExpression,
        )
        assertEquals(
            TypedDefaults.createdAt.defaultExpression,
            TypedDefaults.currentTimestamp.defaultExpression,
        )
        assertEquals(
            SqlFunction.CurrentTimestamp,
            (TypedDefaults.createdAt.defaultExpression?.expression as SqlFunctionExpression<*>).function,
        )
        assertEquals(
            TypedDefaults.numbers,
            (TypedDefaults.sequenceValue.defaultExpression?.expression as SequenceNextValue).sequence,
        )
    }

    @Test
    fun preservesExplicitDefaultNullSeparatelyFromNoDefault() {
        assertNull(TypedDefaults.noDefault.defaultExpression)
        assertEquals(
            null,
            (TypedDefaults.optionalLabel.defaultExpression?.expression as SqlLiteral<*>).value,
        )
    }

    @Test
    fun acceptsTypedCastAndOrdinaryNumericExpressionDefaults() {
        assertEquals(
            NumericBinaryOperator.Add,
            (TypedDefaults.total.defaultExpression?.expression as NumericBinaryExpression<*>).operator,
        )
        assertEquals(
            IntColumnType,
            TypedDefaults.castValue.defaultExpression?.expression?.type,
        )
    }

    @Test
    fun rejectsColumnReferencesInDefaultExpressions() {
        val table = object : Table("referencing_default_rows") {
            val source = string("source")

            fun defaultFromSource(): WritableColumn<String> = string("normalized").default(lower(source))
        }

        assertFailsWith<IllegalArgumentException> {
            table.defaultFromSource()
        }
    }

    @Test
    fun rejectsAggregateWindowAndUnsafeFunctionsInDefaultExpressions() {
        assertFailsWith<IllegalArgumentException> {
            object : Table("aggregate_default_rows") {
                val value = long("value").default(count())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("window_default_rows") {
                val value = long("value").default(rowNumber())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("unsafe_function_default_rows") {
                val value = string("value").default(unsafeFunction("vendor_default", literal("value")))
            }
        }
    }

    @Test
    fun rejectsConflictingIdentityGeneratedAndDefaultMetadata() {
        assertFailsWith<IllegalArgumentException> { IdentityDefaultRows }
        assertFailsWith<IllegalArgumentException> { SequenceIdentityDefaultRows }
        assertFailsWith<IllegalArgumentException> { GeneratedDefaultRows }
    }
}

private object TypedDefaults : Table("typed_defaults") {
    val numbers = sequence("typed_defaults_numbers")
    val noDefault = string("no_default")
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
    val optionalLabel = string("optional_label").nullable().default(null)
    val total = int("total").default(literal(10) + literal(20))
    val castValue = int("cast_value").default(cast(literal("123"), intType()))
    val sequenceValue = long("sequence_value").default(numbers.nextValue())
    val createdAt = instant("created_at").default(currentInstant())
    val currentTimestamp = instant("current_timestamp").default(currentTimestamp())
}

private object EquivalentTypedDefaults : Table("equivalent_typed_defaults") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

private object IdentityDefaultRows : Table("identity_default_rows") {
    val id = long("id").identity().default(1L)
}

private object GeneratedDefaultRows : Table("generated_default_rows") {
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").default(0).generated(price * quantity, GeneratedColumnStorage.Stored)
}

private object SequenceIdentityDefaultRows : Table("sequence_identity_default_rows") {
    private val values = sequence("sequence_identity_default_rows_values")
    val id = long("id").default(values.nextValue()).identity()
}
