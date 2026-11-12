package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.sql.testing.postgresRlsContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PGliteRlsIntegrationTest {
    @Test
    fun nativeSecurityAndReusedSessionContext() = runTest {
        val database = PGliteDatabase.inMemory()
        try {
            postgresRlsContract(database)
        } finally {
            database.close()
        }
    }
}
