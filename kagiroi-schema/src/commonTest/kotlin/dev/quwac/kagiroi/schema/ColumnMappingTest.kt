package dev.quwac.kagiroi.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ColumnMappingTest {
    @Test
    fun transformsScalarColumnsToApplicationClasses() {
        val id: Column<UserId> = MappedAccounts.id
        val rank: Column<AccountRank> = MappedAccounts.rank

        assertEquals("user-123", id.toStorageValue(UserId("user-123")))
        assertEquals(UserId("user-456"), id.fromStorageValue("user-456"))
        assertEquals(7, rank.toStorageValue(AccountRank(7)))
        assertEquals(AccountRank(9), rank.fromStorageValue(9))
        assertSame(StringColumnType, id.storageType)
        assertSame(IntColumnType, rank.storageType)
        assertIs<MappedColumnType<*, *>>(id.type)
    }

    @Test
    fun supportsReusableMappingsAndNullability() {
        val ownerId: Column<UserId?> = MappedAccounts.ownerId

        assertEquals("owner-1", ownerId.toStorageValue(UserId("owner-1")))
        assertEquals(UserId("owner-2"), ownerId.fromStorageValue("owner-2"))
        assertEquals(null, ownerId.toStorageValue(null))
        assertEquals(null, ownerId.fromStorageValue(null))
        assertTrue(ownerId.isNullable)
    }

    @Test
    fun preservesColumnMetadata() {
        assertTrue(MappedAccounts.id.isPrimaryKey)
        assertTrue(MappedAccounts.id.isUnique)
        assertTrue(MappedAccounts.calculatedRank.isComputed)
    }

    @Test
    fun propagatesApplicationValidationFailures() {
        assertFailsWith<IllegalArgumentException> {
            MappedAccounts.rank.fromStorageValue(-1)
        }
    }

    @Test
    fun rejectsASecondValueMapping() {
        assertFailsWith<IllegalArgumentException> {
            MappedAccounts.id.transform(
                fromStorage = { LabeledUserId(it) },
                toStorage = { it.value },
            )
        }
    }
}

private data class UserId(val value: String)

private data class LabeledUserId(val value: UserId)

private data class AccountRank(val value: Int) {
    init {
        require(value >= 0) { "Account rank must not be negative" }
    }
}

private val userIdMapping = columnMapping(
    fromStorage = ::UserId,
    toStorage = { it.value },
)

private object MappedAccounts : Table("mapped_accounts") {
    val id = string("id").transform(
        fromStorage = ::UserId,
        toStorage = { it.value },
    ).primaryKey()
    val ownerId = string("owner_id").transform(userIdMapping).nullable()
    val rank = int("rank").transform(
        fromStorage = ::AccountRank,
        toStorage = { it.value },
    )
    val calculatedRank = int("calculated_rank").transform(
        fromStorage = ::AccountRank,
        toStorage = { it.value },
    ).computed()
}
