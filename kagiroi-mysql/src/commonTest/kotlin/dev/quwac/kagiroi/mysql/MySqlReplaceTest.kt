package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.values
import kotlin.test.Test
import kotlin.test.assertEquals

class MySqlReplaceTest {
    @Test
    fun rendersReplaceValues() {
        val query = replaceInto(ReplaceRows).values {
            set(ReplaceRows.id, 1)
            set(ReplaceRows.name, "new")
        }
        assertEquals(
            "REPLACE INTO `replace_rows` (`id`, `name`) VALUES (?, ?)",
            SqlRenderer(MySqlDialect).render(query).sql,
        )
    }
}

private object ReplaceRows : Table("replace_rows") {
    val id = int("id")
    val name = string("name")
}
