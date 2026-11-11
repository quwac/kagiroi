package dev.quwac.kagiroi.d1

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class D1NativeBindingTest {
    @Test
    fun commonHandleRetainsNativeQueriesAndSessions() = runTest {
        withNativeD1 { native ->
            val database = D1Database(D1Binding(native))
            try {
                database.executeRaw("CREATE TABLE names (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
                database.executeRaw("INSERT INTO names (id, name) VALUES (?, ?)", listOf(1, "日本語"))
                val rows = database.queryRaw("SELECT name FROM names WHERE id = ?", listOf(1)).toList()
                assertEquals("日本語", rows.single().string("name"))
                val session = database.session(D1SessionStart.Primary)
                assertEquals("日本語", session.queryRaw("SELECT name FROM names").toList().single().string("name"))
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun rejectsValuesWithoutD1Operations() {
        assertFailsWith<IllegalArgumentException> { D1Binding("invalid") }
        assertFailsWith<IllegalArgumentException> { D1Binding(42) }
        assertFailsWith<IllegalArgumentException> { D1Binding(object {}) }
    }
}

internal expect suspend fun withNativeD1(block: suspend (Any) -> Unit)
