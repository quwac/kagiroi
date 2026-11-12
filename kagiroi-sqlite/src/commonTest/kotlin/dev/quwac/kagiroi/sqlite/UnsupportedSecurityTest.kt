package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.EnableRowLevelSecurity
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import kotlin.test.Test
import kotlin.test.assertFailsWith

class UnsupportedSecurityTest {
    @Test
    fun rejectsNativeSecurityWithoutEmulation() {
        val table = object : Table("documents") {
            val id = int("id")
        }
        assertFailsWith<UnsupportedDdlOperation> { SqliteDialect.render(EnableRowLevelSecurity(table)) }
    }
}
