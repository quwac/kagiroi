package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Sequence
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.sequence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SequenceExecutionTest {
    @Test
    fun readsTheTypedNextValueThroughTheDialectOwnedQuery() = runTest {
        val execution = SequenceExecution(SequenceTestDialect, 42L)

        assertEquals(42L, execution.nextSequenceValue(sequence("invoice_numbers")))
        assertEquals("SELECT NEXT invoice_numbers AS kagiroi_sequence_value", execution.rawQueries.single())
    }

    @Test
    fun rejectsDialectsWithoutIndependentSequences() = runTest {
        val execution = SequenceExecution(DoubleQuotedQuestionMarkDialect, 42L)

        assertFailsWith<UnsupportedCapability> { execution.nextSequenceValue(sequence("invoice_numbers")) }
    }
}

private object SequenceTestDialect : SqlSequenceDialect {
    override fun quoteIdentifier(identifier: String): String = identifier

    override fun parameterMarker(position: Int): String = "?"

    override fun renderNextValue(sequence: Sequence): RenderedSql = RenderedSql(
        "SELECT NEXT ${sequence.name} AS kagiroi_sequence_value",
        emptyList(),
    )
}

private class SequenceExecution(override val dialect: SqlDialect, private val value: Long) : SqlExecution {
    val rawQueries = mutableListOf<String>()

    override suspend fun execute(statement: RenderedSql): ExecuteResult = ExecuteResult(0)

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = emptyFlow()

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = ExecuteResult(0)

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> {
        rawQueries += sql
        return flowOf(rawSqlRow(listOf("kagiroi_sequence_value"), listOf(value)))
    }
}
