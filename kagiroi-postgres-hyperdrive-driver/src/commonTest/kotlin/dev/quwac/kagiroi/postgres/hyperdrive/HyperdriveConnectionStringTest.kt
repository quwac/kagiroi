package dev.quwac.kagiroi.postgres.hyperdrive

import kotlin.test.Test
import kotlin.test.assertFailsWith

class HyperdriveConnectionStringTest {
    @Test
    fun rejectsBlankConnectionStringsBeforeOpeningAConnection() {
        for (value in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> { HyperdrivePostgresDatabase(value) }
        }
    }
}
