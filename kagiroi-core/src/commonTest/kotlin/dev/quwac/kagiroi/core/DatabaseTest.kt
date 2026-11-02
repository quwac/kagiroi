package dev.quwac.kagiroi.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DatabaseTest {
    @Test
    fun looksUpCapabilitiesByTypedIdentityKey() {
        val transactions = CapabilityKey<Transactions>("transactions")
        val sameName = CapabilityKey<Transactions>("transactions")
        val implementation = Transactions()
        val database = TestDatabase(transactions, implementation)

        assertSame(implementation, database.capability(transactions))
        assertFailsWith<IllegalStateException> { database.requireCapability(sameName) }
    }
}

private class Transactions : DatabaseCapability

private class TestDatabase(
    private val transactions: CapabilityKey<Transactions>,
    private val implementation: Transactions,
) : Database {
    @Suppress("UNCHECKED_CAST")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? =
        if (key === transactions) implementation as C else null
}
