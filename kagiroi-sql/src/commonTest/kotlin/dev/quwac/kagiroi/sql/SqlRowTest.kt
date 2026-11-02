package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.transform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SqlRowTest {
    @Test
    fun mapsSelectedColumnsByTheirKotlinTypes() {
        val storageValues = listOf("account-123", null, 200, "external-456")
        val row = sqlRow(
            listOf(RowAccounts.id, RowAccounts.score, RowAccounts.status, RowAccounts.reference),
            storageValues,
        )
        val account = RowAccount(
            id = row[RowAccounts.id],
            score = row[RowAccounts.score],
            status = row[RowAccounts.status],
            reference = row[RowAccounts.reference],
        )

        assertEquals("account-123", account.id)
        assertNull(account.score)
        assertEquals(RowAccountStatus.SUSPENDED, account.status)
        assertEquals(RowAccountReference("external-456"), account.reference)
    }

    @Test
    fun usesProjectionOrderForDriverValues() {
        val row = sqlRow(
            listOf(RowAccounts.reference, RowAccounts.id),
            listOf("external-first", "account-second"),
        )
        val result = row[RowAccounts.reference] to row[RowAccounts.id]

        assertEquals(RowAccountReference("external-first"), result.first)
        assertEquals("account-second", result.second)
    }

    @Test
    fun rejectsColumnsThatWereNotSelected() {
        val row = sqlRow(listOf(RowAccounts.id), listOf("account-123"))

        assertFailsWith<IllegalArgumentException> { row[RowAccounts.score] }
        assertFailsWith<IllegalArgumentException> { row[OtherRowAccounts.id] }
    }

    @Test
    fun enforcesColumnNullabilityWhileReading() {
        val row = sqlRow(listOf(RowAccounts.id), listOf(null))

        assertFailsWith<IllegalArgumentException> { row[RowAccounts.id] }
    }

    @Test
    fun rendersQueriesIndependentlyFromResultHandling() {
        val query = select(RowAccounts.id).from(RowAccounts)

        assertEquals(
            "SELECT \"row_accounts\".\"id\" FROM \"row_accounts\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql,
        )
    }
}

private enum class RowAccountStatus {
    ACTIVE,
    SUSPENDED,
}

private data class RowAccountReference(val value: String)

private data class RowAccount(
    val id: String,
    val score: Int?,
    val status: RowAccountStatus,
    val reference: RowAccountReference,
)

private object RowAccounts : Table("row_accounts") {
    val id = string("id")
    val score = int("score").nullable()
    val status = int("status").asEnum(RowAccountStatus.entries) {
        RowAccountStatus.ACTIVE mapsTo 100
        RowAccountStatus.SUSPENDED mapsTo 200
    }
    val reference = string("reference").transform(
        fromStorage = ::RowAccountReference,
        toStorage = { it.value },
    )
}

private object OtherRowAccounts : Table("other_row_accounts") {
    val id: Column<String> = string("id")
}
