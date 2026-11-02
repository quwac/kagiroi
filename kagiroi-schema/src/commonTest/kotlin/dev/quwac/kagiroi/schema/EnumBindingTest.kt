package dev.quwac.kagiroi.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnumBindingTest {
    @Test
    fun bindsIntegerAndStringColumnsWithExplicitStableValues() {
        val status: Column<AccountStatus> = Accounts.status
        val role: Column<AccountRole?> = Accounts.role

        assertEquals(100, status.toStorageValue(AccountStatus.ACTIVE))
        assertEquals(AccountStatus.SUSPENDED, status.fromStorageValue(200))
        assertSame(IntColumnType, status.storageType)
        assertSame(StringColumnType, role.storageType)
        assertIs<EnumColumnType<*, *>>(status.type)
        assertEquals("admin", role.toStorageValue(AccountRole.ADMIN))
        assertEquals(AccountRole.MEMBER, role.fromStorageValue("member"))
        assertEquals(null, role.toStorageValue(null))
        assertEquals(null, role.fromStorageValue(null))
        assertTrue(role.isNullable)
    }

    @Test
    fun preservesColumnMetadata() {
        assertTrue(Accounts.status.isUnique)
        assertTrue(Accounts.calculatedStatus.isComputed)
    }

    @Test
    fun supportsReusableBindings() {
        val priority: Column<Priority> = Tasks.priority

        assertSame(IntColumnType, priority.storageType)
        assertEquals(10, priority.toStorageValue(Priority.HIGH))
        assertEquals(Priority.LOW, priority.fromStorageValue(20))
    }

    @Test
    fun rejectsIncompleteAndAmbiguousBindings() {
        assertFailsWith<IllegalArgumentException> {
            enumBinding<AccountStatus, Int>(AccountStatus.entries) {
                AccountStatus.ACTIVE mapsTo 100
            }
        }
        assertFailsWith<IllegalArgumentException> {
            enumBinding<AccountStatus, Int>(AccountStatus.entries) {
                AccountStatus.ACTIVE mapsTo 100
                AccountStatus.ACTIVE mapsTo 200
            }
        }
        assertFailsWith<IllegalArgumentException> {
            enumBinding<AccountStatus, Int>(AccountStatus.entries) {
                AccountStatus.ACTIVE mapsTo 100
                AccountStatus.SUSPENDED mapsTo 100
            }
        }
        assertFailsWith<IllegalArgumentException> {
            enumBinding<AccountStatus, Int>(listOf(AccountStatus.ACTIVE)) {
                AccountStatus.SUSPENDED mapsTo 200
            }
        }
    }

    @Test
    fun rejectsUnknownStoredValuesExplicitly() {
        val exception = assertFailsWith<UnknownEnumValueException> {
            Accounts.status.fromStorageValue(999)
        }

        assertEquals(999, exception.storageValue)
    }
}

private enum class AccountStatus {
    ACTIVE,
    SUSPENDED,
}

private enum class AccountRole {
    ADMIN,
    MEMBER,
}

private enum class Priority {
    HIGH,
    LOW,
}

private object Accounts : Table("accounts") {
    val status = int("status").asEnum(AccountStatus.entries) {
        AccountStatus.ACTIVE mapsTo 100
        AccountStatus.SUSPENDED mapsTo 200
    }.unique()
    val role = string("role").asEnum(AccountRole.entries) {
        AccountRole.ADMIN mapsTo "admin"
        AccountRole.MEMBER mapsTo "member"
    }.nullable()
    val calculatedStatus = int("calculated_status").asEnum(AccountStatus.entries) {
        AccountStatus.ACTIVE mapsTo 100
        AccountStatus.SUSPENDED mapsTo 200
    }.computed()
}

private val priorityBinding: EnumBinding<Priority, Int> = enumBinding(Priority.entries) {
    Priority.HIGH mapsTo 10
    Priority.LOW mapsTo 20
}

private object Tasks : Table("tasks") {
    val priority = int("priority").asEnum(priorityBinding)
}
